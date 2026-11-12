package com.atguigu.gulimall.order.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.order.vo.*;
import com.atguigu.gulimall.api.ware.vo.SeckillOrderStockLockVo;
import com.atguigu.gulimall.api.ware.vo.StockLockVo;
import com.atguigu.gulimall.api.ware.vo.WareStockCheckVo;
import com.atguigu.gulimall.common.constant.WareConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.constant.OrderConstant;
import com.atguigu.gulimall.order.dao.OrderDao;
import com.atguigu.gulimall.order.dao.OrderItemDao;
import com.atguigu.gulimall.order.dao.OrderReturnApplyDao;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderItemEntity;
import com.atguigu.gulimall.order.entity.OrderReturnApplyEntity;
import com.atguigu.gulimall.order.entity.PaymentInfoEntity;
import com.atguigu.gulimall.api.product.SkuDetailVo;
import com.atguigu.gulimall.order.feign.CartFeignClient;
import com.atguigu.gulimall.order.feign.MemberReceiveAddressFeignClient;
import com.atguigu.gulimall.order.feign.ProductFeignClient;
import com.atguigu.gulimall.order.feign.SeckillFeignClient;
import com.atguigu.gulimall.order.feign.WareFeignClient;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.service.PaymentInfoService;
import com.atguigu.gulimall.order.thread.MemberResponseVoThreadLocal;
import com.atguigu.gulimall.order.vo.OrderListVo;
import com.atguigu.gulimall.order.vo.OrderStatVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    /** oms_order.status：0-待付款 */
    private static final int ORDER_STATUS_UNPAID = 0;

    /** oms_order.status：1-待发货（已支付） */
    private static final int ORDER_STATUS_PAID = 1;

    /** oms_order.status：2-已发货（待收货） */
    private static final int ORDER_STATUS_SHIPPED = 2;

    /** oms_order.status：3-已完成 */
    private static final int ORDER_STATUS_COMPLETED = 3;

    /** oms_order.status：4-已关闭 */
    private static final int ORDER_STATUS_CLOSED = 4;

    /** 订单列表筛选：待退货（退货申请 0 待处理 / 1 退货中） */
    public static final int ORDER_LIST_STATUS_PENDING_RETURN = 5;

    /** 订单列表筛选：已退货（退货申请 2 已完成） */
    public static final int ORDER_LIST_STATUS_RETURNED = 6;

    @Autowired
    private MemberReceiveAddressFeignClient memberReceiveAddressFeignClient;
    @Autowired
    private CartFeignClient cartFeignClient;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolExecutor;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private WareFeignClient wareFeignClient;
    @Autowired
    private SeckillFeignClient seckillFeignClient;
    @Autowired
    private ProductFeignClient productFeignClient;
    @Autowired
    private OrderItemDao orderItemDao;
    @Autowired
    private OrderReturnApplyDao orderReturnApplyDao;
    @Autowired
    private PaymentInfoService paymentInfoService;

    /**
     * Redis 预扣库存的 Lua 脚本
     * 返回：-1 = 缓存里没有这个 sku 的库存；-2 = 库存不足；其它 = 扣减后的剩余库存
     */
    private static final DefaultRedisScript<Long> DEDUCT_STOCK_SCRIPT;

    /** 令牌校验 + 删除必须原子：否则并发请求都能校验通过，导致重复下单。返回 1=消费成功，0=不匹配 */
    private static final DefaultRedisScript<Long> CONSUME_TOKEN_SCRIPT;

    static {
        DEDUCT_STOCK_SCRIPT = new DefaultRedisScript<>();
        DEDUCT_STOCK_SCRIPT.setScriptText(
                "local stock = tonumber(redis.call('get', KEYS[1]));"
                        + "if stock == nil then return -1 end;"
                        + "local need = tonumber(ARGV[1]);"
                        + "if stock < need then return -2 end;"
                        + "return redis.call('decrby', KEYS[1], need);");
        DEDUCT_STOCK_SCRIPT.setResultType(Long.class);

        CONSUME_TOKEN_SCRIPT = new DefaultRedisScript<>();
        CONSUME_TOKEN_SCRIPT.setScriptText(
                "local val = redis.call('get', KEYS[1]);"
                        + "if val == ARGV[1] then redis.call('del', KEYS[1]); return 1 end;"
                        + "return 0;");
        CONSUME_TOKEN_SCRIPT.setResultType(Long.class);
    }

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        QueryWrapper<OrderEntity> wrapper = new QueryWrapper<>();

        Object key = params.get("key");
        if (key instanceof String text && StringUtils.hasText(text)) {
            wrapper.and(w -> w.like("order_sn", text)
                    .or().like("member_username", text));
        }
        Object status = params.get("status");
        if (status != null && StringUtils.hasText(status.toString())) {
            wrapper.eq("status", status.toString());
        }
        wrapper.orderByDesc("create_time");

        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    public OrderConfirmVo confirmOrder() {
        MemberResponseVo memberResponseVo = MemberResponseVoThreadLocal.get();

        // 1.远程查询收货地址列表
        CompletableFuture<List<MemberAddressVo>> getAddress = CompletableFuture.supplyAsync(
                () -> memberReceiveAddressFeignClient.getAddress(memberResponseVo.getId()), threadPoolExecutor);

        // 2.1.远程查询购物车中所有购物项
        // 见FeignCookieConfig，远程调用别的服务无法传递请求头，，异步任务ThreadLocal开启多个线程导致拦截器失效！
        CompletableFuture<List<OrderItemVo>> getCurrentCartItems = CompletableFuture.supplyAsync(
                () -> cartFeignClient.getCurrentCartItems(), threadPoolExecutor);

        // 3.查询用户积分
        Integer integration = memberResponseVo.getIntegration();

        // 4.其它数据vo中自动计算

        // 5.防重令牌
        /*
          保证多次提交的幂等性
            1.令牌生成：
              用户进入结算页面时，后端生成令牌，一个进redis，一个给前端
            2.令牌检验与销毁：
              用户提交订单时，前端带上令牌与redis匹配，若匹配则立即删除订单并进行后续操作，若不匹配说明订单已提交，本次提交订单失败
              检验与销毁过程必须是原子性的，用lua脚本或redis分布式锁实现
          其它保证幂等的方式
            1.数据库唯一索引：给当前订单提交创建唯一索引（主键），缺点是只能保证提交幂等，无法防止更新
            2.状态机 + 乐观锁： status状态（已提交/已支付） + 版本号，缺点是必须有明确的流程状态
            3.redis分布式锁：同一时间只能单次提交，缺点是性能问题，并且需要处理锁超时和释放问题
         */
        CompletableFuture<String> getToken = CompletableFuture.supplyAsync(
                () -> {
                    String token = UUID.randomUUID().toString().replace("-", "");
                    stringRedisTemplate.opsForValue().set(
                            OrderConstant.USER_ORDER_TOKEN_PREFIX + memberResponseVo.getId(),
                            token,
                            30,
                            TimeUnit.MINUTES);
                    return token;
                });

        return CompletableFuture.allOf(getAddress, getCurrentCartItems, getToken).thenApply(
                vo -> {
                    OrderConfirmVo orderConfirmVo = new OrderConfirmVo();
                    orderConfirmVo.setMemberAddressVos(getAddress.join());
                    orderConfirmVo.setItems(getCurrentCartItems.join());
                    orderConfirmVo.setIntegration(integration);
                    orderConfirmVo.setOrderToken(getToken.join());
                    return orderConfirmVo;
                }
        ).join();

    }

    /*
    本地事务
      1.基本特性 ACID
        原子性 全成功/失败
        一致性 两个合法状态
        隔离性 事务相互不影响
        持久性 永久操作
      2.隔离级别
        读未提交 本事务会读取其它未提交事务的修改数据
        读已提交 本事务会读取其它事务已提交的修改数据 Oracle & SQL Server末日
        可重复读 所有事务相互读不影响，事务均不会读取到其它事务修改的数据
        串行化 加读锁
      3.常用传播级别
        多个方法调用仍是一个事务，最开始的事务的全部事务设置会覆盖子事务 @Transactional(propagation = Propagation.REQUIRED)
        开启新事务 其它事务失败回滚不影响 @Transactional(propagation = Propagation.REQUIRES_NEW)
      4.本地事务失效（绕过代理对象使用私有对象调用）
        引入aop + @EnableAspectJAutoProxy(exposeProxy = true) + Class class = (Class) AopContext.currentProxy();
        更推荐重构代码解决

    分布式事务
      CAP原则 最多只能实现两点，一般保证AP，C只要求最终一致（使用seata确保）
        一致性 所有分布式服务器数据最新一致
        可用性 一台服务器宕机集群是否正常工作
        分区容错性 服务器间相互通信的容错
     */

    /**
     * 提交订单：对应 ConfirmController 类注释里的【第 2 步：二次校验】和【第 3 步：库存预扣减】
     *
     * <p>⚠️ 3.3（等待支付、超时取消）属于支付页面的职责，这里不做。
     *
     * <p>库存锁由调用方（Controller）持有，本方法内部的扣库调用不再加锁。
     */
    @GlobalTransactional
    @Transactional(rollbackFor = Exception.class)
    @Override
    public OrderVo submitOrder(OrderSubmitVo orderSubmitVo) {

        // ==================== 第 2 步：二次校验关键数据 ====================

        // 2.1 登录用户（令牌的 key 里要用 memberId）
        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (loginUser == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_LOGIN);
        }

        // 2.2 防重令牌：confirmOrder 里是 key=前缀+memberId、value=token，
        //     校验通过立刻删除，保证一个令牌只能用一次
        String tokenKey = OrderConstant.USER_ORDER_TOKEN_PREFIX + loginUser.getId();

        // 校验通过并删除，两步必须原子（Lua），否则并发下同一个令牌能被用两次
        Long consumed = stringRedisTemplate.execute(CONSUME_TOKEN_SCRIPT, List.of(tokenKey),
                orderSubmitVo.getOrderToken());
        if (consumed != 1L) {
            throw new RRException(BizCodeEnum.ORDER_TOKEN_INVALID);
        }

        // 2.3 收货地址（顺带核对这个地址是不是他自己的）
        MemberAddressVo address = memberReceiveAddressFeignClient.getAddressById(orderSubmitVo.getAddrId());
        if (address == null || !loginUser.getId().equals(address.getMemberId())) {
            throw new RRException(BizCodeEnum.ORDER_ADDRESS_INVALID);
        }

        // 2.4 购物车实时数据：不信任前端提交的商品、件数和价格
        List<OrderItemVo> items = cartFeignClient.getCurrentCartItems();
        if (items == null || items.isEmpty()) {
            throw new RRException(BizCodeEnum.ORDER_CART_EMPTY);
        }

        // 2.5 价格校验：用服务端实时价格重算，和前端传来的对比。
        //     totalPrice 不用自己乘：cart 侧 CartItemVo#getTotalPrice() 已经算好（price × count），
        //     Feign 序列化时 Jackson 调 getter 把它写进了 JSON，反序列化成 OrderItemVo 时自动赋上来的
        BigDecimal goodsTotal = items.stream()
                .map(item -> item.getTotalPrice() == null ? BigDecimal.ZERO : item.getTotalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (orderSubmitVo.getGoodsTotal() != null
                && orderSubmitVo.getGoodsTotal().compareTo(goodsTotal) != 0) {
            throw new RRException(BizCodeEnum.ORDER_PRICE_CHANGED);
        }

        // 每个 sku 的购买数量
        Map<Long, Integer> skuQuantities = items.stream()
                .collect(Collectors.toMap(OrderItemVo::getSkuId, OrderItemVo::getCount, Integer::sum));

        // 运费页选中的仓库
        Map<Long, Long> skuWareMap = null;
        if (orderSubmitVo.getSkuWareMap() != null && !orderSubmitVo.getSkuWareMap().isBlank()) {
            skuWareMap = JSON.parseObject(orderSubmitVo.getSkuWareMap(),
                    new TypeReference<Map<Long, Long>>() {
                    });
        }
        if (skuWareMap == null) {
            throw new RRException(BizCodeEnum.ORDER_WAREHOUSE_MISSING);
        }
        for (Long skuId : skuQuantities.keySet()) {
            if (skuWareMap.get(skuId) == null) {
                throw new RRException(BizCodeEnum.ORDER_WAREHOUSE_MISSING);
            }
        }

        // 2.6 库存校验：按运费页选中的仓库查
        List<Long> skuIds = items.stream().map(OrderItemVo::getSkuId).distinct().toList();
        Map<Long, Boolean> stockResult = wareFeignClient.hasStockByWare(
                new WareStockCheckVo(skuWareMap, skuQuantities));
        Map<Long, Boolean> hasStock = stockResult == null ? Map.of() : stockResult;
        List<Long> noStockSkuIds = skuIds.stream()
                .filter(skuId -> !Boolean.TRUE.equals(hasStock.get(skuId)))
                .toList();
        if (!noStockSkuIds.isEmpty()) {
            // 提示里给商品名，给 skuId 用户看不懂
            List<String> noStockTitles = items.stream()
                    .filter(item -> noStockSkuIds.contains(item.getSkuId()))
                    .map(OrderItemVo::getTitle)
                    .toList();
            throw new RRException(BizCodeEnum.ORDER_STOCK_NOT_ENOUGH, noStockTitles);
        }

        // ==================== 第 3 步：库存预扣减 ====================

        /*
          因为DB和redis是两套系统，因此扣库存操作无法保证原子性。
          出现宕机时，只能靠每日定时任务来确保DB和redis库存一致性！
         */

        // 3.1 Redis 预扣（Lua 原子执行：判断 + 扣减一步完成）
        skuQuantities.forEach(this::deductRedisStock);

        // 3.2 建订单 + 锁定数据库库存
        String orderSn = generateOrderSn(loginUser.getId());

        // 批量查一次 sku 对应的 spu 概要信息（spu 名称、品牌、分类），别每个 sku 查一次
        // Function.identity()恒等函数 相当于 v -> v
        Map<Long, SkuDetailVo> detailMap = productFeignClient.getSkuDetails(skuIds).stream()
                .collect(Collectors.toMap(SkuDetailVo::getSkuId, Function.identity()));

        OrderEntity order = buildOrder(orderSubmitVo, loginUser, address, goodsTotal, orderSn);
        save(order);

        List<OrderItemEntity> orderItems = items.stream()
                .map(item -> buildOrderItem(item, order.getId(), orderSn, detailMap.get(item.getSkuId())))
                .toList();
        orderItems.forEach(orderItemDao::insert);

        try {
            // ware 侧不再加锁（调用方已持有 STOCK_LOCK）；锁库存失败会由 Seata 回滚 ware 分支。
            // ware 本地事务里同时写工作单和延时解锁消息。
            StockLockVo stockLockVo = new StockLockVo();
            stockLockVo.setOrderId(order.getId());
            stockLockVo.setOrderSn(orderSn);
            stockLockVo.setSkuQuantities(skuQuantities);
            stockLockVo.setSkuWareMap(skuWareMap);
            wareFeignClient.lockStock(stockLockVo);
            // 测试全局事务回滚
//            int a = 1 / 0;
        } catch (Exception e) {
            // Seata 会回滚 ware 分支；Redis 不在全局事务里，这里统一回补。
            skuQuantities.forEach((skuId, num) -> stringRedisTemplate.opsForValue()
                    .increment(WareConstant.STOCK_CACHE_PREFIX + skuId, num));
            throw new RRException(BizCodeEnum.ORDER_STOCK_DEDUCT_FAILED);
        }

        OrderVo orderVo = new OrderVo();
        BeanUtils.copyProperties(order, orderVo);
        return orderVo;
    }

    @Override
    public void cancelOrder(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            throw new RRException(BizCodeEnum.ORDER_SN_REQUIRED);
        }

        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (loginUser == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_LOGIN);
        }

        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        if (order == null || !loginUser.getId().equals(order.getMemberId())) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }

        // 已经关闭：可能是上次取消时库存释放失败，再补一次，接口保持幂等
        if (Integer.valueOf(ORDER_STATUS_CLOSED).equals(order.getStatus())) {
            afterOrderClosed(order, "USER_CANCEL");
            return;
        }

        if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(order.getStatus())) {
            throw new RRException(BizCodeEnum.ORDER_CANCEL_NOT_ALLOWED);
        }

        // 条件更新：和支付回调、超时关单并发时，只有一方能成功
        int updated = getBaseMapper().update(null, new UpdateWrapper<OrderEntity>()
                .set("status", ORDER_STATUS_CLOSED)
                .eq("id", order.getId())
                .eq("status", ORDER_STATUS_UNPAID));

        if (updated == 0) {
            // 状态被别的线程改了：如果已经被关闭，按幂等处理；否则拒绝
            OrderEntity latest = getById(order.getId());
            if (latest != null && Integer.valueOf(ORDER_STATUS_CLOSED).equals(latest.getStatus())) {
                afterOrderClosed(latest, "USER_CANCEL");
                return;
            }
            throw new RRException(BizCodeEnum.ORDER_CANCEL_NOT_ALLOWED);
        }

        afterOrderClosed(order, "USER_CANCEL");
    }

    @Override
    public void afterOrderClosed(OrderEntity order, String reason) {
        if (order == null || order.getOrderSn() == null) {
            return;
        }
        // 1. 秒杀订单：由 seckill 判断场次是否还进行中，决定回秒杀池还是普通池
        if (Integer.valueOf(2).equals(order.getSourceType())) {
            try {
                R result = seckillFeignClient.releaseSeckillOrder(order.getOrderSn(), reason);
                if (result == null || result.getCode() == null || result.getCode() != 0) {
                    log.error("[order-cancel] 通知秒杀释放失败，等待对账：orderSn={} msg={}",
                            order.getOrderSn(), result == null ? "空" : result.get("msg"));
                }
            } catch (Exception e) {
                log.error("[order-cancel] 通知秒杀释放异常，等待对账：orderSn={}", order.getOrderSn(), e);
            }
            return;
        }
        // 2. 普通订单：释放 ware 的 stock_locked
        releaseStockQuietly(order.getOrderSn());
    }

    /**
     * 通知 ware 释放库存。
     *
     * <p>订单已经关闭，库存释放失败不能反过来让取消失败：
     * 延时消息 / StockReleaseTask 后面会兜底释放。
     */
    private void releaseStockQuietly(String orderSn) {
        try {
            R result = wareFeignClient.releaseStockByOrderSn(orderSn);
            if (result != null && result.getCode() != null && result.getCode() != 0) {
                log.error("[order-cancel] 库存释放返回失败，等待兜底任务处理：orderSn={}, msg={}",
                        orderSn, result.get("msg"));
            }
        } catch (Exception e) {
            log.error("[order-cancel] 库存释放失败，等待兜底任务处理：orderSn={}", orderSn, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markOrderPaid(String orderSn, String tradeNo,
                                 BigDecimal paidAmount, String callbackContent) {
        // 执行顺序 1：按订单号查订单。orderSn 是商户订单号（oms_order.order_sn）。
        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        if (order == null) {
            log.error("[alipay] 订单不存在：orderSn={}", orderSn);
            return false;
        }

        // 执行顺序 2：金额校验。
        // paidAmount 是支付宝通知里的 total_amount，必须和订单的 pay_amount（应付金额）一致，
        // 防止 1 分钱支付、串单等。
        if (paidAmount == null || order.getPayAmount() == null
                || order.getPayAmount().compareTo(paidAmount) != 0) {
            log.error("[alipay] 支付金额不一致：orderSn={}, orderAmount={}, paidAmount={}",
                    orderSn, order.getPayAmount(), paidAmount);
            return false;
        }

        // 执行顺序 3：幂等。
        // 支付宝可能重复通知，status 已经是 1（待发货/已支付）时直接返回成功，
        // 但顺手把支付信息补一遍，防止上次只更新了订单没写 payment_info。
        if (Integer.valueOf(ORDER_STATUS_PAID).equals(order.getStatus())) {
            saveOrUpdatePaymentInfo(order, tradeNo, paidAmount, callbackContent);
            return true;
        }

        // 执行顺序 4：状态校验。
        // 只有 0（待付款）可以置为 1（已支付）；
        // 4（已关闭）说明支付来晚了，生产上要发起退款，这里先拒绝。
        if (!Integer.valueOf(ORDER_STATUS_UNPAID).equals(order.getStatus())) {
            log.warn("[alipay] 订单状态不允许置为已支付：orderSn={}, status={}",
                    orderSn, order.getStatus());
            return false;
        }

        // 执行顺序 5：CAS（Compare-And-Set，比较并更新）条件更新。
        // 只有当前数据库里 status 还是 0 才会更新成功：
        // status=1、pay_type=1（支付宝）、payment_time=当前时间。
        int updated = getBaseMapper().update(null, new UpdateWrapper<OrderEntity>()
                .set("status", ORDER_STATUS_PAID)
                .set("pay_type", 1)
                .set("payment_time", new Date())
                .eq("id", order.getId())
                .eq("status", ORDER_STATUS_UNPAID));

        if (updated == 0) {
            // 执行顺序 6：并发兜底。
            // 支付通知和超时关单/重复通知同时到达时，可能有一方先改成功；
            // 重新读一次，如果已经是已支付，按幂等处理。
            OrderEntity latest = getById(order.getId());
            if (latest != null && Integer.valueOf(ORDER_STATUS_PAID).equals(latest.getStatus())) {
                saveOrUpdatePaymentInfo(latest, tradeNo, paidAmount, callbackContent);
                return true;
            }
            return false;
        }

        // 执行顺序 7：订单更新成功后，写支付信息表 oms_payment_info。
        saveOrUpdatePaymentInfo(order, tradeNo, paidAmount, callbackContent);
        return true;
    }

    private void saveOrUpdatePaymentInfo(OrderEntity order, String tradeNo,
                                         BigDecimal paidAmount, String callbackContent) {
        // 支付信息表 oms_payment_info 按 order_sn 唯一对应一条记录：
        // 先查，存在就更新，不存在就新建，避免重复通知插入多条。
        PaymentInfoEntity info = paymentInfoService.getOne(
                new QueryWrapper<PaymentInfoEntity>()
                        .eq("order_sn", order.getOrderSn())
                        .last("LIMIT 1"), false);

        if (info == null) {
            info = new PaymentInfoEntity();
            info.setOrderSn(order.getOrderSn());
            info.setOrderId(order.getId());
            info.setCreateTime(new Date());
        }

        // 支付宝交易流水号，退款/对账时要用
        info.setAlipayTradeNo(tradeNo);
        info.setTotalAmount(paidAmount);
        info.setSubject("谷粒商城订单 " + order.getOrderSn());
        // 支付状态：TRADE_SUCCESS 表示支付成功
        info.setPaymentStatus("TRADE_SUCCESS");
        info.setConfirmTime(new Date());
        // 保存支付宝回调原文和时间，方便排查
        info.setCallbackContent(callbackContent);
        info.setCallbackTime(new Date());

        paymentInfoService.saveOrUpdate(info);
    }

    @Override
    public boolean isOrderClosed(String orderSn) {
        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        return order != null
                && Integer.valueOf(ORDER_STATUS_CLOSED).equals(order.getStatus());
    }

    @Override
    public IPage<OrderListVo> queryUserOrders(Long memberId, Integer status,
                                              String keyword, long pageNo, long pageSize) {
        // 关键字命中商品名/商品编号时，先查出对应的 order_id
        List<Long> itemOrderIds = List.of();
        if (StringUtils.hasText(keyword)) {
            QueryWrapper<OrderItemEntity> itemWrapper = new QueryWrapper<>();
            itemWrapper.select("DISTINCT order_id");
            itemWrapper.and(w -> {
                w.like("sku_name", keyword);
                try {
                    long skuId = Long.parseLong(keyword);
                    w.or().eq("sku_id", skuId);
                } catch (NumberFormatException ignored) {
                    // 关键字不是数字，只按商品名查
                }
            });
            itemOrderIds = orderItemDao.selectList(itemWrapper).stream()
                    .map(OrderItemEntity::getOrderId)
                    .filter(Objects::nonNull)
                    .toList();
        }

        QueryWrapper<OrderEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("member_id", memberId);

        // 5/6 不是 oms_order.status 的值，而是「有退货申请的订单」筛选：
        //   5 待退货 = 退货申请 0 待处理 / 1 退货中
        //   6 已退货 = 退货申请 2 已完成
        if (Integer.valueOf(ORDER_LIST_STATUS_PENDING_RETURN).equals(status)
                || Integer.valueOf(ORDER_LIST_STATUS_RETURNED).equals(status)) {
            QueryWrapper<OrderReturnApplyEntity> applyWrapper = new QueryWrapper<>();
            applyWrapper.select("DISTINCT order_id")
                    // memberId 是 Long，不存在 SQL 注入风险
                    .inSql("order_id", "SELECT id FROM oms_order WHERE member_id = " + memberId);
            if (Integer.valueOf(ORDER_LIST_STATUS_PENDING_RETURN).equals(status)) {
                applyWrapper.in("status", 0, 1);
            } else {
                applyWrapper.eq("status", 2);
            }
            List<Long> returnOrderIds = orderReturnApplyDao.selectList(applyWrapper).stream()
                    .map(OrderReturnApplyEntity::getOrderId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (returnOrderIds.isEmpty()) {
                return new Page<>(pageNo, pageSize, 0);
            }
            wrapper.in("id", returnOrderIds);
        } else if (status != null) {
            wrapper.eq("status", status);
        }

        if (StringUtils.hasText(keyword)) {
            List<Long> ids = itemOrderIds;
            wrapper.and(w -> {
                // 订单号模糊匹配，或者订单包含匹配的商品
                w.like("order_sn", keyword);
                if (!ids.isEmpty()) {
                    w.or().in("id", ids);
                }
            });
        }
        wrapper.orderByDesc("create_time");

        IPage<OrderEntity> orderPage = page(new Page<>(pageNo, pageSize), wrapper);

        List<Long> orderIds = orderPage.getRecords().stream()
                .map(OrderEntity::getId)
                .toList();
        Map<Long, List<OrderItemEntity>> itemMap = orderIds.isEmpty()
                ? Map.of()
                : orderItemDao.selectList(new QueryWrapper<OrderItemEntity>()
                        .in("order_id", orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderItemEntity::getOrderId));

        // 退货申请按订单 + sku 归集；同一商品理论上只有一条，按时间倒序取第一条兜底
        Map<String, OrderReturnApplyEntity> returnApplyMap = Map.of();
        if (!orderIds.isEmpty()) {
            returnApplyMap = orderReturnApplyDao.selectList(
                            new QueryWrapper<OrderReturnApplyEntity>()
                                    .in("order_id", orderIds)
                                    .orderByDesc("create_time"))
                    .stream()
                    .collect(Collectors.toMap(
                            a -> a.getOrderId() + "_" + a.getSkuId(),
                            a -> a,
                            (first, ignored) -> first));
        }

        for (List<OrderItemEntity> items : itemMap.values()) {
            for (OrderItemEntity item : items) {
                OrderReturnApplyEntity apply = returnApplyMap.get(item.getOrderId() + "_" + item.getSkuId());
                if (apply != null) {
                    item.setReturnApplyId(apply.getId());
                    item.setReturnStatus(apply.getStatus());
                    item.setReturnStatusText(returnStatusText(apply.getStatus()));
                }
            }
        }

        Page<OrderListVo> voPage = new Page<>(orderPage.getCurrent(),
                orderPage.getSize(), orderPage.getTotal());
        voPage.setRecords(orderPage.getRecords().stream().map(order -> {
            OrderListVo vo = new OrderListVo();
            BeanUtils.copyProperties(order, vo);
            vo.setItems(itemMap.getOrDefault(order.getId(), List.of()));
            vo.setStatusText(orderStatusText(order.getStatus()));
            return vo;
        }).toList());
        return voPage;
    }

    /** 退货申请状态文案 */
    private String returnStatusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 0 -> "退货待处理";
            case 1 -> "退货处理中";
            case 2 -> "退货已完成";
            case 3 -> "退货已拒绝";
            default -> "退货状态未知";
        };
    }

    private String orderStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "等待付款";
            case 1 -> "等待发货";
            case 2 -> "等待收货";
            case 3 -> "已完成";
            case 4 -> "已关闭";
            default -> "无效订单";
        };
    }

    @Override
    public OrderStatVo getOrderStat(String start, String end, String keyword) {
        String key = StringUtils.hasText(keyword) ? keyword.trim() : null;

        OrderStatVo stat = baseMapper.selectSummary(start, end, key);
        if (stat == null) {
            stat = new OrderStatVo();
        }
        stat.setDailyTrend(baseMapper.selectDailyTrend(start, end, key));
        stat.setTopProducts(baseMapper.selectTopProducts(start, end, key, 10));
        stat.setStatusDistribution(baseMapper.selectStatusDistribution(start, end, key));
        stat.setPayTypeDistribution(baseMapper.selectPayTypeDistribution(start, end, key));
        return stat;
    }

    @Override
    public boolean confirmReceive(String orderSn) {
        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);

        if (order == null || loginUser == null
                || !loginUser.getId().equals(order.getMemberId())) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }
        if (!Integer.valueOf(ORDER_STATUS_SHIPPED).equals(order.getStatus())) {
            throw new RRException(BizCodeEnum.ORDER_RECEIVE_NOT_ALLOWED);
        }

        int updated = getBaseMapper().update(null, new UpdateWrapper<OrderEntity>()
                .set("status", ORDER_STATUS_COMPLETED)
                .set("receive_time", new Date())
                .eq("id", order.getId())
                .eq("status", ORDER_STATUS_SHIPPED));
        return updated > 0;
    }

    @Override
    public List<Long> getOrderSkuIds(String orderSn) {
        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        if (order == null) {
            return List.of();
        }
        return orderItemDao.selectList(new QueryWrapper<OrderItemEntity>()
                        .select("sku_id")
                        .eq("order_id", order.getId()))
                .stream()
                .map(OrderItemEntity::getSkuId)
                .toList();
    }

    /**
     * Redis 预扣某个 sku 的库存
     *
     * <p>key 不存在时（还没被管理员操作过 / 缓存丢过）先回源查库并预热，再扣一次。
     */
    private void deductRedisStock(Long skuId, Integer num) {
        String key = WareConstant.STOCK_CACHE_PREFIX + skuId;

        long result = stringRedisTemplate.execute(DEDUCT_STOCK_SCRIPT, List.of(key), String.valueOf(num));

        if (result == -1L) {
            // 缓存没预热：查库拿可用库存写进 Redis（不设过期），再扣一次
            Long dbStock = wareFeignClient.getStockNums(List.of(skuId)).get(skuId);
            long available = dbStock == null ? 0L : dbStock;
            stringRedisTemplate.opsForValue().set(key, String.valueOf(available));

            result = stringRedisTemplate.execute(DEDUCT_STOCK_SCRIPT, List.of(key), String.valueOf(num));
        }

        if (result == -2L) {
            throw new RRException(BizCodeEnum.ORDER_STOCK_INSUFFICIENT);
        }
    }

    /** 订单号：时间戳 + 会员 id + 4 位随机数 */
    private String generateOrderSn(Long memberId) {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + memberId
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /** 组装订单主表（收货人信息在这一步做快照，地址以后改了也不影响已下的单） */
    private OrderEntity buildOrder(OrderSubmitVo vo, MemberResponseVo loginUser,
                                   MemberAddressVo address, BigDecimal goodsTotal, String orderSn) {
        // 运费没传说明页面状态过期了，直接让用户刷新重来，不猜默认值
        if (vo.getFreightAmount() == null) {
            throw new RRException(BizCodeEnum.ORDER_FREIGHT_MISSING);
        }
        // 应付金额同样加判断
        if (vo.getPayPrice() == null) {
            throw new RRException(BizCodeEnum.ORDER_PAYPRICE_MISSING);
        }

        OrderEntity order = new OrderEntity();
        order.setOrderSn(orderSn);
        order.setMemberId(loginUser.getId());
        order.setMemberUsername(loginUser.getUsername());
        order.setCreateTime(new Date());

        order.setTotalAmount(goodsTotal);
        order.setFreightAmount(vo.getFreightAmount());
        order.setPayAmount(vo.getPayPrice());

        order.setPayType(vo.getPayType());
        order.setStatus(0);          // 0 = 待付款
        order.setSourceType(0);      // 0 = PC 订单
        order.setDeleteStatus(0);
        order.setConfirmStatus(0);
        order.setNote(vo.getRemarks());

        // 收货人快照
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getPhone());
        order.setReceiverPostCode(address.getPostCode());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getRegion());
        order.setReceiverDetailAddress(address.getDetailAddress());

        return order;
    }

    @Override
    @GlobalTransactional
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSeckillOrder(SeckillOrderCreateVo vo) {
        // 1. 参数校验
        if (vo == null || !StringUtils.hasText(vo.getOrderSn()) || vo.getMemberId() == null
                || vo.getSessionId() == null || vo.getSkuId() == null || vo.getWareId() == null
                || vo.getQuantity() == null || vo.getQuantity() <= 0
                || vo.getSeckillPrice() == null || vo.getAddrId() == null) {
            throw new RRException(BizCodeEnum.SECKILL_PARAM_INVALID);
        }

        // 2. 订单号幂等：重复消费直接返回已有订单
        OrderEntity exists = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", vo.getOrderSn())
                .last("LIMIT 1"), false);
        if (exists != null) {
            return seckillOrderMap(exists, true);
        }

        // 3. 地址必须属于这个会员
        MemberAddressVo address = memberReceiveAddressFeignClient.getAddressById(vo.getAddrId());
        if (address == null || !Objects.equals(address.getMemberId(), vo.getMemberId())) {
            throw new RRException(BizCodeEnum.ORDER_ADDRESS_INVALID);
        }

        // 4. 金额以 seckill 传来的秒杀价为准，免运费
        BigDecimal payAmount = vo.getSeckillPrice()
                .multiply(BigDecimal.valueOf(vo.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);

        // 5. 订单主表
        OrderEntity order = new OrderEntity();
        order.setOrderSn(vo.getOrderSn());
        order.setMemberId(vo.getMemberId());
        order.setMemberUsername(vo.getMemberUsername());
        order.setCreateTime(new Date());
        order.setTotalAmount(payAmount);
        order.setFreightAmount(BigDecimal.ZERO);
        order.setPayAmount(payAmount);
        order.setPromotionAmount(BigDecimal.ZERO);
        order.setIntegrationAmount(BigDecimal.ZERO);
        order.setCouponAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayType(vo.getPayType() == null ? 1 : vo.getPayType());
        order.setStatus(ORDER_STATUS_UNPAID);
        order.setSourceType(2);
        order.setDeleteStatus(0);
        order.setConfirmStatus(0);
        order.setNote("秒杀订单");
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getPhone());
        order.setReceiverPostCode(address.getPostCode());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getRegion());
        order.setReceiverDetailAddress(address.getDetailAddress());
        save(order);

        // 6. 订单项快照
        OrderItemEntity item = new OrderItemEntity();
        item.setOrderId(order.getId());
        item.setOrderSn(order.getOrderSn());
        item.setSkuId(vo.getSkuId());
        item.setSkuName(vo.getSkuName());
        item.setSkuPic(vo.getSkuPic());
        item.setSkuPrice(vo.getSeckillPrice());
        item.setSkuQuantity(vo.getQuantity());
        item.setRealAmount(payAmount);
        item.setPromotionAmount(BigDecimal.ZERO);
        item.setCouponAmount(BigDecimal.ZERO);
        item.setIntegrationAmount(BigDecimal.ZERO);
        item.setGiftIntegration(0);
        item.setGiftGrowth(0);
        orderItemDao.insert(item);

        // 7. 调 ware 把活动锁定转成订单锁定；失败由 Seata 回滚订单
        SeckillOrderStockLockVo lockVo = new SeckillOrderStockLockVo();
        lockVo.setOrderId(order.getId());
        lockVo.setOrderSn(order.getOrderSn());
        lockVo.setSessionId(vo.getSessionId());
        lockVo.setMemberId(vo.getMemberId());
        lockVo.setSkuId(vo.getSkuId());
        lockVo.setWareId(vo.getWareId());
        lockVo.setQuantity(vo.getQuantity());
        R lockResult = wareFeignClient.lockSeckillOrderStock(lockVo);
        if (lockResult == null || lockResult.getCode() == null || lockResult.getCode() != 0) {
            String msg = lockResult == null ? "返回为空" : String.valueOf(lockResult.get("msg"));
            throw new RRException("秒杀库存锁定失败：" + msg);
        }

        return seckillOrderMap(order, false);
    }

    @Override
    public Map<String, Object> getSeckillOrderStatus(String orderSn) {
        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        if (order == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("orderSn", order.getOrderSn());
        map.put("orderId", order.getId());
        map.put("status", order.getStatus());
        map.put("sourceType", order.getSourceType());
        map.put("payAmount", order.getPayAmount());
        map.put("createTime", order.getCreateTime());
        return map;
    }

    @Override
    public Map<String, Integer> batchGetOrderStatus(List<String> orderSns) {
        if (orderSns == null || orderSns.isEmpty()) {
            return Map.of();
        }
        List<OrderEntity> orders = list(new QueryWrapper<OrderEntity>()
                .select("order_sn", "status")
                .in("order_sn", orderSns));
        return orders.stream().collect(Collectors.toMap(
                OrderEntity::getOrderSn, OrderEntity::getStatus, (a, b) -> a));
    }

    @Override
    public boolean isSeckillOrder(String orderSn) {
        OrderEntity order = getOne(new QueryWrapper<OrderEntity>()
                .select("id", "source_type")
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        return order != null && Integer.valueOf(2).equals(order.getSourceType());
    }

    private Map<String, Object> seckillOrderMap(OrderEntity order, boolean duplicate) {
        Map<String, Object> map = new HashMap<>();
        map.put("orderSn", order.getOrderSn());
        map.put("orderId", order.getId());
        map.put("payAmount", order.getPayAmount());
        map.put("status", order.getStatus());
        map.put("duplicate", duplicate);
        return map;
    }

    /** 组装订单明细（spu 相关信息从 product 批量查好传进来） */
    private OrderItemEntity buildOrderItem(OrderItemVo item, Long orderId, String orderSn,
                                           SkuDetailVo detail) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.setOrderId(orderId);
        entity.setOrderSn(orderSn);

        entity.setSkuId(item.getSkuId());
        entity.setSkuName(item.getTitle());
        entity.setSkuPic(item.getImage());
        entity.setSkuPrice(item.getPrice());
        entity.setSkuQuantity(item.getCount());

        if (detail != null) {
            entity.setSpuId(detail.getSpuId());
            entity.setSpuName(detail.getSpuName());
            entity.setSpuPic(detail.getSpuPic());
            entity.setSpuBrand(detail.getSpuBrand());
            entity.setCategoryId(detail.getCategoryId());
        }
        entity.setSkuAttrsVals(item.getSkuAttrValues() == null ? null : JSON.toJSONString(item.getSkuAttrValues()));

        // 目前没有优惠体系，促销/优惠券/积分抵扣都按 0 记，实付 = 单价 × 数量
        entity.setRealAmount(item.getPrice().multiply(BigDecimal.valueOf(item.getCount())));
        entity.setPromotionAmount(BigDecimal.ZERO);
        entity.setCouponAmount(BigDecimal.ZERO);
        entity.setIntegrationAmount(BigDecimal.ZERO);
        entity.setGiftIntegration(0);
        entity.setGiftGrowth(0);

        return entity;
    }

}