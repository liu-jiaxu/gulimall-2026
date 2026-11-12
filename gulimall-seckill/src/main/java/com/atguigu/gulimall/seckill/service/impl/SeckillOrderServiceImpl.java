package com.atguigu.gulimall.seckill.service.impl;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.dao.SeckillOrderDao;
import com.atguigu.gulimall.seckill.dao.SeckillSessionDao;
import com.atguigu.gulimall.seckill.entity.SeckillOrderEntity;
import com.atguigu.gulimall.seckill.entity.SeckillSessionEntity;
import com.atguigu.gulimall.seckill.feign.WareFeignClient;
import com.atguigu.gulimall.seckill.mq.message.SeckillOrderCreateMessage;
import com.atguigu.gulimall.seckill.service.SeckillOrderService;
import com.atguigu.gulimall.seckill.service.SeckillStockService;
import com.atguigu.gulimall.seckill.vo.SeckillOrderResultVo;
import com.atguigu.gulimall.seckill.vo.SeckillOrderSubmitVo;
import com.atguigu.gulimall.seckill.vo.SeckillReserveResult;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀抢购主流程
 */
@Slf4j
@Service
public class SeckillOrderServiceImpl implements SeckillOrderService {

    private static final DateTimeFormatter ORDER_SN_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Autowired
    private SeckillOrderDao seckillOrderDao;

    @Autowired
    private SeckillStockService seckillStockService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MqMessageService mqMessageService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Autowired
    private WareFeignClient wareFeignClient;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public SeckillOrderResultVo submit(SeckillOrderSubmitVo vo, MemberResponseVo member) {
        // 1. 基础参数
        if (member == null || member.getId() == null) {
            throw new RRException(BizCodeEnum.SECKILL_NOT_LOGIN);
        }
        if (vo == null || vo.getSessionId() == null || vo.getSkuId() == null
                || vo.getAddrId() == null || vo.getRequestId() == null
                || vo.getRequestId().isBlank() || vo.getRequestId().length() > 64) {
            throw new RRException(BizCodeEnum.SECKILL_PARAM_INVALID);
        }
        int quantity = vo.getQuantity() == null ? 1 : vo.getQuantity();
        if (quantity <= 0) {
            throw new RRException(BizCodeEnum.SECKILL_PARAM_INVALID);
        }

        // 2. 同一 requestId 直接返回原结果，客户端重试不会多扣
        SeckillOrderEntity sameRequest = seckillOrderDao.selectOne(
                new QueryWrapper<SeckillOrderEntity>()
                        .eq("request_id", vo.getRequestId())
                        .last("LIMIT 1"));
        if (sameRequest != null) {
            return toResult(sameRequest);
        }

        // 3. 商品信息以 Redis hash 为准，不信任前端价格
        String infoKey = SeckillConstant.sessionSkusKey(vo.getSessionId());
        Object json = stringRedisTemplate.opsForHash().get(infoKey, vo.getSkuId().toString());
        if (json == null) {
            throw new RRException(BizCodeEnum.SECKILL_SESSION_INVALID);
        }
        SeckillSkuVo sku;
        try {
            sku = objectMapper.readValue(String.valueOf(json), SeckillSkuVo.class);
        } catch (Exception e) {
            log.error("[seckill-submit] 商品信息解析失败 sessionId={} skuId={}",
                    vo.getSessionId(), vo.getSkuId(), e);
            throw new RRException(BizCodeEnum.SECKILL_SESSION_INVALID);
        }

        // 4. 随机码必须和上架时写进 hash 的一致
        if (vo.getRandomCode() == null || !Objects.equals(vo.getRandomCode(), sku.getRandomCode())) {
            throw new RRException(BizCodeEnum.SECKILL_RANDOM_CODE_INVALID);
        }
        if (sku.getWareId() == null || sku.getSeckillPrice() == null) {
            throw new RRException(BizCodeEnum.SECKILL_SESSION_INVALID);
        }

        // 5. 时间窗口
        long now = System.currentTimeMillis();
        if (sku.getStartTime() == null || now < sku.getStartTime()) {
            throw new RRException(BizCodeEnum.SECKILL_NOT_STARTED);
        }
        if (sku.getEndTime() == null || now >= sku.getEndTime()) {
            throw new RRException(BizCodeEnum.SECKILL_ENDED);
        }

        // 6. 生成订单号，作为全链路幂等键
        String orderSn = generateOrderSn(member.getId());

        // 7. Lua 原子预扣：库存、限购、防重、marker
        int limit = sku.getSeckillLimit() == null ? 1 : sku.getSeckillLimit().intValue();
        if (limit <= 0) {
            limit = 1;
        }
        SeckillReserveResult reserve = seckillStockService.tryReserve(
                vo.getSessionId(), vo.getSkuId(), member.getId(), quantity, limit,
                orderSn, sku.getStartTime(), sku.getEndTime());
        if (reserve.duplicate()) {
            // 10 秒内重复点击：返回第一次的订单号
            SeckillOrderEntity duplicated = seckillOrderDao.selectOne(
                    new QueryWrapper<SeckillOrderEntity>()
                            .eq("order_sn", reserve.getExistingOrderSn())
                            .last("LIMIT 1"));
            if (duplicated != null) {
                return toResult(duplicated);
            }
            SeckillOrderResultVo processing = new SeckillOrderResultVo();
            processing.setOrderSn(reserve.getExistingOrderSn());
            processing.setStatus(SeckillOrderStatus.WAIT_BUILD);
            processing.setStatusText(SeckillOrderStatus.text(SeckillOrderStatus.WAIT_BUILD));
            return processing;
        }
        if (!reserve.success()) {
            throw new RRException(mapReserveCode(reserve.getCode()));
        }

        // 8. 本地事务：落预扣记录 + 写可靠消息；失败回补 Redis
        try {
            createReservedOrder(vo, member, sku, orderSn, quantity);
        } catch (Exception e) {
            log.error("[seckill-submit] 落预扣记录或投递建单消息失败，回补 Redis orderSn={}", orderSn, e);
            seckillStockService.releaseReservation(
                    vo.getSessionId(), vo.getSkuId(), member.getId(), quantity, orderSn);
            throw new RRException(BizCodeEnum.SECKILL_ORDER_BUILD_FAILED);
        }

        // 9. 返回排队状态
        SeckillOrderResultVo result = new SeckillOrderResultVo();
        result.setOrderSn(orderSn);
        result.setStatus(SeckillOrderStatus.WAIT_BUILD);
        result.setStatusText(SeckillOrderStatus.text(SeckillOrderStatus.WAIT_BUILD));
        result.setPayAmount(sku.getSeckillPrice().multiply(BigDecimal.valueOf(quantity)));
        return result;
    }

    @Override
    public SeckillOrderResultVo getResult(String orderSn, String requestId, Long memberId) {
        // 1. 参数
        boolean bySn = orderSn != null && !orderSn.isBlank();
        boolean byRequest = requestId != null && !requestId.isBlank();
        if (!bySn && !byRequest) {
            throw new RRException(BizCodeEnum.SECKILL_PARAM_INVALID);
        }

        // 2. 只允许查自己的秒杀单
        SeckillOrderEntity order = seckillOrderDao.selectOne(
                new QueryWrapper<SeckillOrderEntity>()
                        .eq(bySn ? "order_sn" : "request_id", bySn ? orderSn : requestId)
                        .last("LIMIT 1"));
        if (order == null || !Objects.equals(order.getMemberId(), memberId)) {
            throw new RRException(BizCodeEnum.SECKILL_ORDER_NOT_FOUND);
        }
        return toResult(order);
    }

    @Override
    public void onBuildSuccess(String orderSn, Long orderId, BigDecimal payAmount) {
        // 1. 只有待建单才允许推进，重复消费直接忽略
        int updated = seckillOrderDao.update(null, new UpdateWrapper<SeckillOrderEntity>()
                .set("status", SeckillOrderStatus.BUILT)
                .set("order_id", orderId)
                .set("pay_amount", payAmount)
                .eq("order_sn", orderSn)
                .eq("status", SeckillOrderStatus.WAIT_BUILD));
        if (updated == 0) {
            log.info("[seckill-build] 秒杀单已处理过或不是待建单 orderSn={}", orderSn);
        }
    }

    @Override
    public void failAndRelease(String orderSn, String reason) {
        // 1. 抢占：只有待建单的才能标记失败，防止和建单成功并发时误回补
        int updated = seckillOrderDao.update(null, new UpdateWrapper<SeckillOrderEntity>()
                .set("status", SeckillOrderStatus.BUILD_FAILED)
                .set("fail_reason", reason)
                .eq("order_sn", orderSn)
                .eq("status", SeckillOrderStatus.WAIT_BUILD));
        if (updated == 0) {
            return;
        }

        // 2. 回补 Redis；活动已结束导致库存 key 不存在时忽略
        SeckillOrderEntity order = getByOrderSn(orderSn);
        if (order == null) {
            return;
        }
        int released = seckillStockService.releaseReservation(
                order.getSessionId(), order.getSkuId(), order.getMemberId(),
                order.getQuantity(), orderSn);
        log.info("[seckill-build] 建单失败已回补 orderSn={} reason={} redisResult={}",
                orderSn, reason, released);
    }

    /**
     * 落预扣记录 + 写建单消息，必须在同一本地事务里
     */
    private void createReservedOrder(SeckillOrderSubmitVo vo, MemberResponseVo member,
                                     SeckillSkuVo sku, String orderSn, int quantity) {
        // 1. 把这次抢购的单号、会员、商品、金额等信息组装成预扣记录实体
        SeckillOrderEntity entity = new SeckillOrderEntity();
        entity.setOrderSn(orderSn);
        entity.setRequestId(vo.getRequestId());
        entity.setMemberId(member.getId());
        entity.setMemberUsername(member.getUsername());
        entity.setSessionId(vo.getSessionId());
        entity.setSkuId(vo.getSkuId());
        entity.setSkuName(sku.getSkuName());
        entity.setSkuPic(sku.getSkuImg());
        entity.setWareId(sku.getWareId());
        entity.setQuantity(quantity);
        entity.setSeckillPrice(sku.getSeckillPrice());
        entity.setPayAmount(sku.getSeckillPrice().multiply(BigDecimal.valueOf(quantity)));
        entity.setAddrId(vo.getAddrId());
        entity.setPayType(vo.getPayType() == null ? 1 : vo.getPayType());
        entity.setStatus(SeckillOrderStatus.WAIT_BUILD);
        entity.setBuildRetryCount(0);

        // 2. 复制一份预扣记录作为建单消息，发消息时不用再回查数据库
        SeckillOrderCreateMessage message = new SeckillOrderCreateMessage();
        BeanUtils.copyProperties(entity, message);

        // 3. 在同一个本地事务里插记录并投递可靠消息，避免只落库没发消息或反过来
        transactionTemplate.executeWithoutResult(status -> {
            seckillOrderDao.insert(entity);
            mqMessageService.sendReliable(MqConstants.SECKILL_ORDER_EXCHANGE,
                    MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY, message);
        });
    }

    @Override
    public void markPaid(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            return;
        }
        // 1. 只有已建单才能推进到已支付，重复回调幂等
        seckillOrderDao.update(null, new UpdateWrapper<SeckillOrderEntity>()
                .set("status", SeckillOrderStatus.PAID)
                .eq("order_sn", orderSn)
                .eq("status", SeckillOrderStatus.BUILT));
    }

    @Override
    public SeckillOrderResultVo releaseByOrderSn(String orderSn, String reason) {
        // 1. 没有预扣记录说明是普通订单，直接返回
        SeckillOrderEntity order = getByOrderSn(orderSn);
        if (order == null) {
            return null;
        }

        // 2. 只有已建单的才需要归还库存
        if (!Integer.valueOf(SeckillOrderStatus.BUILT).equals(order.getStatus())) {
            return toResult(order);
        }

        // 3. 和结算/取消共用场次锁，避免"判断进行中"和"结算删 key"交叉
        RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(order.getSessionId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException(BizCodeEnum.SECKILL_RELEASE_CONFLICT);
            }

            // 4. 锁内重查：两个取消请求只有一个真正释放
            order = getByOrderSn(orderSn);
            if (order == null
                    || !Integer.valueOf(SeckillOrderStatus.BUILT).equals(order.getStatus())) {
                return order == null ? null : toResult(order);
            }

            // 5. 场次仍进行中且库存 key 存在，才回秒杀池
            int mode = decideReleaseMode(order);

            // 6. 先动数据库：任务 1→2、stock_locked 减、回池时 seckill_stock_locked 加
            R result = wareFeignClient.releaseSeckillOrderStock(orderSn,
                    mode == SeckillOrderStatus.RELEASE_TO_SECKILL ? "SECKILL_POOL" : "NORMAL_POOL");
            if (result == null || result.getCode() == null || result.getCode() != 0) {
                String msg = result == null ? "返回为空" : String.valueOf(result.get("msg"));
                throw new RRException("释放秒杀订单库存失败：" + msg);
            }

            // 7. 回秒杀池时再回补 Redis；key 已不存在只记日志，结算会按 DB 剩余释放
            if (mode == SeckillOrderStatus.RELEASE_TO_SECKILL) {
                int released = seckillStockService.releaseReservation(
                        order.getSessionId(), order.getSkuId(), order.getMemberId(),
                        order.getQuantity(), orderSn);
                if (released < 0) {
                    log.warn("[seckill-release] Redis 库存 key 已不存在，库存留在 seckill_stock_locked 等结算 orderSn={}",
                            orderSn);
                }
            }

            // 8. 最后推进状态；前面两步都幂等，失败重试安全
            seckillOrderDao.update(null, new UpdateWrapper<SeckillOrderEntity>()
                    .set("status", SeckillOrderStatus.RELEASED)
                    .set("release_mode", mode)
                    .set("release_reason", reason)
                    .eq("order_sn", orderSn)
                    .eq("status", SeckillOrderStatus.BUILT));

            SeckillOrderEntity latest = getByOrderSn(orderSn);
            return toResult(latest == null ? order : latest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException(BizCodeEnum.SECKILL_RELEASE_CONFLICT);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 回秒杀池的条件：场次仍进行中，且 Redis 库存 key 还在。
     */
    private int decideReleaseMode(SeckillOrderEntity order) {
        // 1. 场次必须存在
        SeckillSessionEntity session = seckillSessionDao.selectById(order.getSessionId());
        if (session == null) {
            return SeckillOrderStatus.RELEASE_TO_NORMAL;
        }
        // 2. 必须还在进行中；停止中/已结束/已取消都回普通池
        int state = SeckillSessionState.effectiveState(session.getSeckillState(),
                session.getStartTime(), session.getEndTime(), new Date());
        if (state != SeckillSessionState.RUNNING) {
            return SeckillOrderStatus.RELEASE_TO_NORMAL;
        }
        // 3. 库存 key 不存在时不能凭空回补
        Boolean exists = stringRedisTemplate.hasKey(
                SeckillConstant.stockKey(order.getSessionId(), order.getSkuId()));
        return Boolean.TRUE.equals(exists)
                ? SeckillOrderStatus.RELEASE_TO_SECKILL : SeckillOrderStatus.RELEASE_TO_NORMAL;
    }

    private SeckillOrderEntity getByOrderSn(String orderSn) {
        // 1. 按订单号查一条预扣记录，查不到就返回 null，让调用方决定怎么处理
        return seckillOrderDao.selectOne(new QueryWrapper<SeckillOrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"));
    }

    private SeckillOrderResultVo toResult(SeckillOrderEntity order) {
        // 1. 把数据库实体的字段搬到返回前端的 VO，状态额外翻译成中文文案
        SeckillOrderResultVo result = new SeckillOrderResultVo();
        result.setOrderSn(order.getOrderSn());
        result.setStatus(order.getStatus());
        result.setStatusText(SeckillOrderStatus.text(order.getStatus()));
        result.setOrderId(order.getOrderId());
        result.setPayAmount(order.getPayAmount());
        result.setReleaseMode(order.getReleaseMode());
        result.setFailReason(order.getFailReason());
        return result;
    }

    private BizCodeEnum mapReserveCode(int code) {
        // 1. 把 Lua 返回的负数错误码翻译成业务错误码，认不出的码统一按场次无效处理
        return switch (code) {
            case -2 -> BizCodeEnum.SECKILL_STOCK_INSUFFICIENT;
            case -3 -> BizCodeEnum.SECKILL_LIMIT_EXCEEDED;
            case -4 -> BizCodeEnum.SECKILL_NOT_STARTED;
            case -5 -> BizCodeEnum.SECKILL_ENDED;
            default -> BizCodeEnum.SECKILL_SESSION_INVALID;
        };
    }

    private String generateOrderSn(Long memberId) {
        // 1. 取当前时间到秒，让单号带上时间趋势，方便排查
        String time = LocalDateTime.now().format(ORDER_SN_TIME);
        // 2. 会员号取模后补足 4 位，人工也能看出是谁的单
        String member = String.format("%04d", Math.floorMod(memberId, 10000));
        // 3. 再拼 6 位随机数，降低同一秒内不同会员撞号的概率
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return time + member + random;
    }
}
