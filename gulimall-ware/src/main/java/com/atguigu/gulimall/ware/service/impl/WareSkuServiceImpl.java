package com.atguigu.gulimall.ware.service.impl;

import com.atguigu.gulimall.api.ware.vo.SeckillOrderStockLockVo;
import com.atguigu.gulimall.common.constant.WareConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import com.atguigu.gulimall.ware.dao.SeckillStockOperationDao;
import com.atguigu.gulimall.ware.dao.WareSkuDao;
import com.atguigu.gulimall.ware.entity.SeckillStockOperationEntity;
import com.atguigu.gulimall.ware.entity.WareOrderTaskDetailEntity;
import com.atguigu.gulimall.ware.entity.WareOrderTaskEntity;
import com.atguigu.gulimall.ware.entity.WareSkuEntity;
import com.atguigu.gulimall.ware.mq.message.StockReleaseMessage;
import com.atguigu.gulimall.ware.service.WareOrderTaskDetailService;
import com.atguigu.gulimall.ware.service.WareOrderTaskService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mysql.cj.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.atguigu.gulimall.ware.constant.WareConstant.BIZ_TYPE_SECKILL;
import static com.atguigu.gulimall.ware.constant.WareConstant.TASK_STATUS_DEDUCTED;
import static com.atguigu.gulimall.ware.constant.WareConstant.TASK_STATUS_LOCKED;
import static com.atguigu.gulimall.ware.constant.WareConstant.TASK_STATUS_RELEASED;
import static com.atguigu.gulimall.ware.constant.WareConstant.TASK_STATUS_RETURNED;


@Slf4j
@Service("wareSkuService")
public class WareSkuServiceImpl extends ServiceImpl<WareSkuDao, WareSkuEntity> implements WareSkuService {

    private static final String OP_LOCK = "LOCK";
    private static final String OP_RELEASE = "RELEASE";

    @Autowired
    private SeckillStockOperationDao seckillStockOperationDao;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MqMessageService mqMessageService;

    @Autowired
    private WareOrderTaskService wareOrderTaskService;

    @Autowired
    private WareOrderTaskDetailService wareOrderTaskDetailService;

    /** 下单后多久解锁库存（毫秒），配置在 common.yaml */
    @Value("${gulimall.ware.stock-release.delay-ms}")
    private long stockReleaseDelayMs;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        QueryWrapper<WareSkuEntity> wrapper = new QueryWrapper<>();

        String wareId = (String) params.get("wareId");
        if (!StringUtils.isNullOrEmpty(wareId)) {
            wrapper.eq("ware_id", wareId);
        }

        String skuId = (String) params.get("skuId");
        if (!StringUtils.isNullOrEmpty(skuId)) {
            wrapper.eq("sku_id", skuId);
        }

        IPage<WareSkuEntity> page = this.page(
                new Query<WareSkuEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    public void addStock(Long skuId, Long wareId, String skuName, Integer skuNum) {
        // wms_ware_sku 上 (sku_id, ware_id) 只有普通索引、没有唯一约束，
        // 用 selectOne 一旦出现重复行会抛 TooManyResultsException，所以取第一条即可
        List<WareSkuEntity> exist = this.baseMapper.selectList(new QueryWrapper<WareSkuEntity>()
                .eq("sku_id", skuId)
                .eq("ware_id", wareId));
        WareSkuEntity wareSkuEntity = exist.isEmpty() ? null : exist.get(0);

        if (wareSkuEntity == null) {
            // 新增
            wareSkuEntity = new WareSkuEntity();
            wareSkuEntity.setStock(skuNum);
            wareSkuEntity.setStockLocked(0);
        } else {
            // 累加；老数据 stock 可能为 null
            Integer stock = wareSkuEntity.getStock();
            wareSkuEntity.setStock(stock == null ? skuNum : stock + skuNum);
        }

        // skuName 远程获取失败时是空串，别把库里已有的名字覆盖掉
        if (!StringUtils.isNullOrEmpty(skuName)) {
            wareSkuEntity.setSkuName(skuName);
        }
        wareSkuEntity.setWareId(wareId);
        wareSkuEntity.setSkuId(skuId);

        this.saveOrUpdate(wareSkuEntity);
    }

    /**
     * 查询sku是否有库存
     *
     * @param skuIds
     * @return
     */
    @Override
    public Map<Long, Boolean> getSkuHasStock(List<Long> skuIds) {
        // 一个 sku 可能在多个仓库都有记录，要按「所有仓库的可用库存之和」判断有没有货
        return skuIds.stream().collect(Collectors.toMap(
                sku -> sku,
                skuId -> sumAvailableStock(skuId) > 0
        ));
    }

    @Override
    public Map<Long, Boolean> getSkuHasStockByWare(Map<Long, Long> skuWareMap,
                                                   Map<Long, Integer> skuQuantities) {
        Map<Long, Boolean> result = new HashMap<>();
        skuQuantities.forEach((skuId, quantity) -> {
            Long wareId = skuWareMap == null ? null : skuWareMap.get(skuId);
            if (wareId == null || quantity == null || quantity <= 0) {
                result.put(skuId, false);
                return;
            }
            WareSkuEntity row = getOne(new QueryWrapper<WareSkuEntity>()
                    .eq("sku_id", skuId)
                    .eq("ware_id", wareId)
                    .last("LIMIT 1"), false);
            result.put(skuId, row != null && availableOf(row) >= quantity);
        });
        return result;
    }

    /**
     * 查询sku库存数量
     *
     * @param skuIds 商品 id 列表
     * @return skuId → 可用库存 = 该 sku 在所有仓库的 Σ(stock - stock_locked - seckill_stock_locked)
     */
    @Override
    public Map<Long, Long> getStockNums(List<Long> skuIds) {
        return skuIds.stream().collect(Collectors.toMap(
                skuId -> skuId,
                this::sumAvailableStock
        ));
    }

    /**
     * 某个 sku 在<b>所有仓库</b>的可用库存总和
     *
     * <p>⚠️ 之前用的是 {@code SELECT stock - stock_locked ... eq(sku_id, ?)}：
     * 同一个 sku 在多个仓库有记录时，{@code getMap} 会拿到多行并抛
     * {@code TooManyResultsException}。所以必须用 {@code SUM} 聚合，保证只返回一行。
     */
    private long sumAvailableStock(Long skuId) {
        Map<String, Object> row = getMap(new QueryWrapper<WareSkuEntity>()
                .select("SUM(COALESCE(stock, 0) - COALESCE(stock_locked, 0) " +
                        "- COALESCE(seckill_stock_locked, 0)) AS available_stock")
                .eq("sku_id", skuId));

        // 没有这个 sku 的任何记录时，SUM 会返回一行 {available_stock: null}
        if (row == null || row.get("available_stock") == null) {
            return 0L;
        }
        // MySQL 的 SUM 可能给 BigDecimal / Long，统一按 Number 取
        return ((Number) row.get("available_stock")).longValue();
    }

    /** 一行库存的可用数量：stock - stock_locked - seckill_stock_locked */
    private long availableOf(WareSkuEntity row) {
        int stock = row.getStock() == null ? 0 : row.getStock();
        int locked = row.getStockLocked() == null ? 0 : row.getStockLocked();
        int seckillLocked = row.getSeckillStockLocked() == null ? 0 : row.getSeckillStockLocked();
        return (long) stock - locked - seckillLocked;
    }

    @Override
    public void refreshStockCache(Long skuId) {
        // 和 getStockNums / hasStock 用同一个口径：所有仓库的可用库存之和
        long available = sumAvailableStock(skuId);

        // ⚠️ 刻意【不设过期时间】：库存是长期有效的业务数据，
        //    设了 TTL 到期后会变成"没有缓存"，下单时反而多查一次库
        stringRedisTemplate.opsForValue()
                .set(WareConstant.STOCK_CACHE_PREFIX + skuId, String.valueOf(available));

        log.info("[stock] skuId={} 可用库存已回写 Redis：{}", skuId, available);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long lockSeckillOrderStock(SeckillOrderStockLockVo vo) {
        // 1. 参数校验
        if (vo == null || vo.getOrderSn() == null || vo.getOrderSn().isBlank()
                || vo.getSkuId() == null || vo.getWareId() == null
                || vo.getQuantity() == null || vo.getQuantity() <= 0) {
            throw new RRException(BizCodeEnum.SECKILL_PARAM_INVALID);
        }
        int quantity = vo.getQuantity();

        // 2. 幂等：同一个订单号已经有秒杀工作单，直接返回
        WareOrderTaskEntity existing = wareOrderTaskService.getOne(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("order_sn", vo.getOrderSn())
                        .last("LIMIT 1"));
        if (existing != null) {
            if (!Integer.valueOf(BIZ_TYPE_SECKILL).equals(existing.getBizType())) {
                throw new RRException("订单号已被普通订单占用，不能创建秒杀工作单");
            }
            return existing.getId();
        }

        // 3. 先占订单号：工作单唯一键挡住重复消息，避免库存被转换两次
        WareOrderTaskEntity task = new WareOrderTaskEntity();
        task.setOrderId(vo.getOrderId());
        task.setOrderSn(vo.getOrderSn());
        task.setTaskStatus(TASK_STATUS_LOCKED);
        task.setCreateTime(new Date());
        task.setWareId(vo.getWareId());
        task.setOrderBody("秒杀订单");
        task.setBizType(BIZ_TYPE_SECKILL);
        task.setSeckillSessionId(vo.getSessionId());
        task.setMemberId(vo.getMemberId());
        wareOrderTaskService.save(task);

        // 4. 原子转换：seckill_stock_locked -q、stock_locked +q，总占用不变
        int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                .setSql("stock_locked = COALESCE(stock_locked, 0) + " + quantity
                        + ", seckill_stock_locked = COALESCE(seckill_stock_locked, 0) - " + quantity)
                .eq("sku_id", vo.getSkuId())
                .eq("ware_id", vo.getWareId())
                .apply("COALESCE(seckill_stock_locked, 0) >= {0}", quantity)
                .apply("COALESCE(stock, 0) - COALESCE(stock_locked, 0) >= {0}", quantity));
        if (affected == 0) {
            throw new RRException("秒杀库存不足，无法锁定订单");
        }

        // 5. 工作单明细
        WareOrderTaskDetailEntity detail = new WareOrderTaskDetailEntity();
        detail.setSkuId(vo.getSkuId());
        detail.setSkuNum(quantity);
        detail.setTaskId(task.getId());
        detail.setWareId(vo.getWareId());
        detail.setLockStatus(TASK_STATUS_LOCKED);
        wareOrderTaskDetailService.save(detail);

        return task.getId();
    }

    /**
     * 关联秒杀商品时锁定库存：库存够就增加 seckill_stock_locked，<b>不动 stock</b>。
     *
     * <p>从可用库存最多的仓库整笔锁，库存不够直接报错。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long reserveSeckillStock(Long skuId, Integer quantity, String operationId, Long wareId) {
        // 1. 参数校验：商品、数量、operationId 必须有效
        if (skuId == null || quantity == null || quantity <= 0) {
            throw new RRException("秒杀商品和数量不能为空");
        }
        if (operationId == null || operationId.isBlank() || operationId.length() > 64) {
            throw new RRException("秒杀库存操作ID不合法");
        }

        // 2. 幂等检查：同一个 operationId 已经锁过，直接返回当时的仓库 id
        SeckillStockOperationEntity existing = findOperation(operationId);
        if (existing != null) {
            if (!OP_LOCK.equals(existing.getOperationType())) {
                throw new RRException("秒杀库存操作ID类型不一致");
            }
            return existing.getWareId();
        }

        // 3. 选仓：
        //    3.1 传了 wareId（>0）：只查这个原仓库，库存不够直接失败，不换仓
        //    3.2 wareId 为空或 0：新增关联，选可用库存最多的仓库
        WareSkuEntity best;
        if (wareId != null && wareId > 0) {
            best = this.getOne(new QueryWrapper<WareSkuEntity>()
                    .eq("sku_id", skuId)
                    .eq("ware_id", wareId)
                    .last("LIMIT 1"), false);
            if (best == null) {
                throw new RRException("原仓库没有这个商品的库存");
            }
            if (availableOf(best) < quantity) {
                throw new RRException("原仓库可用库存不足，当前可用 " + Math.max(availableOf(best), 0));
            }
        } else {
            List<WareSkuEntity> rows = this.list(new QueryWrapper<WareSkuEntity>()
                    .eq("sku_id", skuId));
            best = rows.stream()
                    .max(Comparator.comparingLong(this::availableOf))
                    .orElse(null);
            if (best == null || availableOf(best) < quantity) {
                long total = rows.stream().mapToLong(this::availableOf).sum();
                throw new RRException("可用库存不足，当前可用 " + Math.max(total, 0));
            }
        }

        // 4. 先写操作记录，唯一索引挡住并发重复；返回 0 说明 operationId 已存在
        SeckillStockOperationEntity operation = buildOperation(
                operationId, OP_LOCK, skuId, best.getWareId(), quantity);
        if (seckillStockOperationDao.insertIgnore(operation) == 0) {
            SeckillStockOperationEntity duplicate = findOperation(operationId);
            if (duplicate != null && OP_LOCK.equals(duplicate.getOperationType())) {
                return duplicate.getWareId();
            }
            throw new RRException("重复的秒杀库存操作");
        }

        // 5. 条件更新：只有当前可用仍够，才把数量加到 seckill_stock_locked
        int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                .setSql("seckill_stock_locked = COALESCE(seckill_stock_locked, 0) + " + quantity)
                .eq("id", best.getId())
                .apply("COALESCE(stock, 0) - COALESCE(stock_locked, 0) " +
                        "- COALESCE(seckill_stock_locked, 0) >= {0}", quantity));
        if (affected == 0) {
            throw new RRException("可用库存不足，请刷新后重试");
        }

        // 6. 返回实际锁定的仓库 id，coupon 要存到关联表
        return best.getWareId();
    }

    /**
     * 修改/删除关联时释放秒杀锁定库存：只扣关联表记录的那个仓库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseSeckillStock(Long skuId, Long wareId, Integer quantity, String operationId) {
        // 1. 参数校验：商品、仓库、数量、operationId 必须有效
        if (skuId == null || wareId == null || quantity == null || quantity <= 0) {
            throw new RRException("秒杀商品、仓库和数量不能为空");
        }
        if (operationId == null || operationId.isBlank() || operationId.length() > 64) {
            throw new RRException("秒杀库存操作ID不合法");
        }

        // 2. 幂等检查：同一个 operationId 已经释放过，直接返回
        SeckillStockOperationEntity existing = findOperation(operationId);
        if (existing != null) {
            if (!OP_RELEASE.equals(existing.getOperationType())) {
                throw new RRException("秒杀库存操作ID类型不一致");
            }
            return;
        }

        // 3. 先写操作记录，唯一索引挡住并发重复；返回 0 说明 operationId 已存在
        SeckillStockOperationEntity operation = buildOperation(
                operationId, OP_RELEASE, skuId, wareId, quantity);
        if (seckillStockOperationDao.insertIgnore(operation) == 0) {
            SeckillStockOperationEntity duplicate = findOperation(operationId);
            if (duplicate != null && OP_RELEASE.equals(duplicate.getOperationType())) {
                return;
            }
            throw new RRException("重复的秒杀库存操作");
        }

        // 4. 只扣 sku + wareId 这一行；不够就报错，事务回滚操作记录
        int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                .setSql("seckill_stock_locked = COALESCE(seckill_stock_locked, 0) - " + quantity)
                .eq("sku_id", skuId)
                .eq("ware_id", wareId)
                .apply("COALESCE(seckill_stock_locked, 0) >= {0}", quantity));
        if (affected == 0) {
            throw new RRException("秒杀锁定库存不足，无法释放");
        }
    }

    /**
     * 按 operationId 查幂等记录
     */
    private SeckillStockOperationEntity findOperation(String operationId) {
        return seckillStockOperationDao.selectOne(
                new QueryWrapper<SeckillStockOperationEntity>()
                        .eq("operation_id", operationId)
                        .last("LIMIT 1"));
    }

    /**
     * 组装一条秒杀库存操作记录
     */
    private SeckillStockOperationEntity buildOperation(String operationId, String operationType,
                                                       Long skuId, Long wareId, Integer quantity) {
        SeckillStockOperationEntity operation = new SeckillStockOperationEntity();
        operation.setOperationId(operationId);
        operation.setOperationType(operationType);
        operation.setSkuId(skuId);
        operation.setWareId(wareId);
        operation.setQuantity(quantity);
        operation.setCreateTime(new Date());
        return operation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long lockStock(Long orderId, String orderSn, Map<Long, Integer> skuQuantities,
                          Map<Long, Long> skuWareMap) {
        if (orderSn == null || orderSn.isEmpty()) {
            throw new RRException(BizCodeEnum.WARE_ORDER_SN_REQUIRED);
        }
        if (skuQuantities == null || skuQuantities.isEmpty()) {
            throw new RRException(BizCodeEnum.WARE_STOCK_ITEMS_REQUIRED);
        }

        // 幂等：同一订单已经有工作单就直接返回。
        // 数据库上还有 uk_order_sn 唯一索引兜底，并发重复请求不可能锁两次库存。
        WareOrderTaskEntity existing = wareOrderTaskService.getOne(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("order_sn", orderSn)
                        .last("LIMIT 1"),
                false);
        if (existing != null) {
            if (Integer.valueOf(TASK_STATUS_LOCKED).equals(existing.getTaskStatus())) {
                log.info("[stock-lock] 订单 {} 已锁定过库存，直接返回工作单 {}", orderSn, existing.getId());
                return existing.getId();
            }
            throw new RRException(BizCodeEnum.WARE_ORDER_TASK_ALREADY_PROCESSED, orderSn);
        }

        // 1. 先写工作单主记录，明细需要它的自增 id
        WareOrderTaskEntity task = new WareOrderTaskEntity();
        task.setOrderId(orderId);
        task.setOrderSn(orderSn);
        task.setTaskStatus(TASK_STATUS_LOCKED);
        task.setCreateTime(new Date());
        wareOrderTaskService.save(task);

        // 2. 逐个 sku 按仓库锁库存，并记录每一笔锁定的明细
        for (Map.Entry<Long, Integer> entry : skuQuantities.entrySet()) {
            Long skuId = entry.getKey();
            Integer quantity = entry.getValue();
            if (quantity == null || quantity <= 0) {
                continue;
            }

            // 必须带选仓信息，缺失直接报错
            Long chosenWareId = skuWareMap == null ? null : skuWareMap.get(skuId);
            if (chosenWareId == null) {
                throw new RRException(BizCodeEnum.WARE_CHOSEN_WAREHOUSE_REQUIRED);
            }
            lockChosenWarehouse(task, skuId, quantity, chosenWareId);
        }

        Long taskId = task.getId();
        sendReleaseMessage(taskId, orderSn);
        return taskId;
    }

    /** 只锁运费页选中的仓库，库存不足直接失败 */
    private void lockChosenWarehouse(WareOrderTaskEntity task, Long skuId, int quantity, Long wareId) {
        WareSkuEntity row = getOne(new QueryWrapper<WareSkuEntity>()
                .eq("sku_id", skuId)
                .eq("ware_id", wareId)
                .last("LIMIT 1"), false);
        if (row == null) {
            throw new RRException(BizCodeEnum.WARE_CHOSEN_WAREHOUSE_EMPTY);
        }

        if (availableOf(row) < quantity) {
            throw new RRException(BizCodeEnum.WARE_STOCK_NOT_ENOUGH);
        }

        int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                .setSql("stock_locked = COALESCE(stock_locked, 0) + " + quantity)
                .eq("id", row.getId())
                .apply("COALESCE(stock, 0) - COALESCE(stock_locked, 0) " +
                        "- COALESCE(seckill_stock_locked, 0) >= " + quantity));
        if (affected == 0) {
            throw new RRException(BizCodeEnum.WARE_STOCK_NOT_ENOUGH);
        }

        WareOrderTaskDetailEntity detail = new WareOrderTaskDetailEntity();
        detail.setTaskId(task.getId());
        detail.setSkuId(skuId);
        detail.setSkuName(row.getSkuName());
        detail.setSkuNum(quantity);
        detail.setWareId(wareId);
        detail.setLockStatus(TASK_STATUS_LOCKED);
        wareOrderTaskDetailService.save(detail);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseStock(Long taskId) {
        if (taskId == null) {
            return;
        }

        // 0. 秒杀工作单不能走普通释放：它回池还是不回池由 seckill 决定
        WareOrderTaskEntity task = wareOrderTaskService.getById(taskId);
        if (task == null) {
            return;
        }
        if (Integer.valueOf(BIZ_TYPE_SECKILL).equals(task.getBizType())) {
            log.warn("[stock-release] 秒杀工作单不能走普通释放，跳过 taskId={} orderSn={}",
                    taskId, task.getOrderSn());
            return;
        }

        // 抢占：只有把工作单从「已锁定」更新成「已解锁」成功的那一次才继续。
        // MQ 重复投递、定时任务与 MQ 同时处理，都会被这一行条件更新挡住。
        int claimed = wareOrderTaskService.getBaseMapper().update(null,
                new UpdateWrapper<WareOrderTaskEntity>()
                        .set("task_status", TASK_STATUS_RELEASED)
                        .eq("id", taskId)
                        .eq("task_status", TASK_STATUS_LOCKED));
        if (claimed == 0) {
            log.debug("[stock-release] 工作单 {} 已被处理过，跳过", taskId);
            return;
        }

        List<WareOrderTaskDetailEntity> details = wareOrderTaskDetailService.list(
                new QueryWrapper<WareOrderTaskDetailEntity>()
                        .eq("task_id", taskId)
                        .eq("lock_status", TASK_STATUS_LOCKED));

        // skuId → 本次解锁的总数量（一个 sku 可能锁在多个仓库）
        Map<Long, Integer> restoredBySku = new HashMap<>();

        for (WareOrderTaskDetailEntity detail : details) {
            int num = detail.getSkuNum() == null ? 0 : detail.getSkuNum();
            if (num <= 0) {
                continue;
            }

            // 解锁：stock_locked 减回去即可，stock 不能动（东西还在仓库里）
            int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                    .setSql("stock_locked = COALESCE(stock_locked, 0) - " + num)
                    .eq("ware_id", detail.getWareId())
                    .eq("sku_id", detail.getSkuId())
                    .apply("COALESCE(stock_locked, 0) >= " + num));
            if (affected == 0) {
                // 数据不一致（例如人工改过库存）：抛异常让事务回滚，交给 MQ 重试 / 定时任务再处理
                throw new RRException(BizCodeEnum.WARE_STOCK_RELEASE_FAILED, detail.getId());
            }

            wareOrderTaskDetailService.update(new UpdateWrapper<WareOrderTaskDetailEntity>()
                    .set("lock_status", TASK_STATUS_RELEASED)
                    .eq("id", detail.getId())
                    .eq("lock_status", TASK_STATUS_LOCKED));

            restoredBySku.merge(detail.getSkuId(), num, Integer::sum);
        }

        // Redis 必须在 DB 事务提交后再回补，否则事务回滚会把 Redis 提前加回去。
        // 用 INCRBY 而不是"查库后 SET"：并发下单时查库会读到还没同步的旧值，
        // 把别人刚预扣的数量覆盖掉，造成超卖。
        afterCommit(() -> restoredBySku.forEach((skuId, num) -> {
            stringRedisTemplate.opsForValue()
                    .increment(WareConstant.STOCK_CACHE_PREFIX + skuId, num);
            log.info("[stock-release] 工作单 {} 已解锁并回补 Redis：skuId={} num={}", taskId, skuId, num);
        }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseSeckillOrderStock(String orderSn, String mode) {
        // 1. 查工作单；不存在或不是秒杀单都直接返回/拒绝
        WareOrderTaskEntity task = wareOrderTaskService.getOne(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("order_sn", orderSn)
                        .last("LIMIT 1"),
                false);
        if (task == null) {
            return;
        }
        if (!Integer.valueOf(BIZ_TYPE_SECKILL).equals(task.getBizType())) {
            throw new RRException("订单不是秒杀工作单，不能走秒杀释放");
        }

        // 2. 抢占：只有把 1 改成 2 的那一次才真正释放
        int claimed = wareOrderTaskService.getBaseMapper().update(null,
                new UpdateWrapper<WareOrderTaskEntity>()
                        .set("task_status", TASK_STATUS_RELEASED)
                        .eq("id", task.getId())
                        .eq("task_status", TASK_STATUS_LOCKED));
        if (claimed == 0) {
            return;
        }

        // 3. 逐条释放明细；回池时同时把数量加回 seckill_stock_locked
        boolean backToSeckill = "SECKILL_POOL".equals(mode);
        Set<Long> skuIds = new HashSet<>();
        List<WareOrderTaskDetailEntity> details = wareOrderTaskDetailService.list(
                new QueryWrapper<WareOrderTaskDetailEntity>()
                        .eq("task_id", task.getId())
                        .eq("lock_status", TASK_STATUS_LOCKED));
        for (WareOrderTaskDetailEntity detail : details) {
            int num = detail.getSkuNum() == null ? 0 : detail.getSkuNum();
            if (num <= 0) {
                continue;
            }

            // 3.1 订单占用的 stock_locked 减回去
            int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                    .setSql("stock_locked = COALESCE(stock_locked, 0) - " + num)
                    .eq("ware_id", detail.getWareId())
                    .eq("sku_id", detail.getSkuId())
                    .apply("COALESCE(stock_locked, 0) >= " + num));
            if (affected == 0) {
                throw new RRException(BizCodeEnum.WARE_STOCK_RELEASE_FAILED, detail.getId());
            }

            // 3.2 回秒杀池：重新计入活动占用
            if (backToSeckill) {
                baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                        .setSql("seckill_stock_locked = COALESCE(seckill_stock_locked, 0) + " + num)
                        .eq("ware_id", detail.getWareId())
                        .eq("sku_id", detail.getSkuId()));
            }

            // 3.3 明细标记已解锁
            wareOrderTaskDetailService.update(new UpdateWrapper<WareOrderTaskDetailEntity>()
                    .set("lock_status", TASK_STATUS_RELEASED)
                    .eq("id", detail.getId())
                    .eq("lock_status", TASK_STATUS_LOCKED));
            skuIds.add(detail.getSkuId());
        }

        // 4. 事务提交后按数据库重算普通可售库存缓存：
        //    回池时可售不变，回普通池时可售 +num，不能用 INCRBY 一刀切
        afterCommit(() -> skuIds.forEach(this::refreshStockCache));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseRemainingSeckillStock(Long sessionId, Long skuId, Long wareId, String operationId) {
        // 1. 参数校验
        if (skuId == null || wareId == null) {
            throw new RRException("秒杀商品和仓库不能为空");
        }
        if (operationId == null || operationId.isBlank() || operationId.length() > 64) {
            throw new RRException("秒杀库存操作ID不合法");
        }

        // 2. 幂等：同一 operationId 已经释放过
        SeckillStockOperationEntity existing = findOperation(operationId);
        if (existing != null) {
            return 0;
        }

        // 3. 行锁读当前剩余；表上没有 (sku_id, ware_id) 唯一约束，沿用取第一行的约定
        WareSkuEntity row = baseMapper.selectOne(new QueryWrapper<WareSkuEntity>()
                .eq("sku_id", skuId)
                .eq("ware_id", wareId)
                .last("LIMIT 1 FOR UPDATE"));
        if (row == null) {
            return 0;
        }
        int remaining = row.getSeckillStockLocked() == null ? 0 : row.getSeckillStockLocked();
        if (remaining <= 0) {
            return 0;
        }

        // 4. 先写操作记录，唯一索引挡住并发重复
        SeckillStockOperationEntity operation = buildOperation(
                operationId, OP_RELEASE, skuId, wareId, remaining);
        if (seckillStockOperationDao.insertIgnore(operation) == 0) {
            return 0;
        }

        // 5. 置 0；条件更新兜底，防止期间被别的取消/转换改过
        int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                .set("seckill_stock_locked", 0)
                .eq("id", row.getId())
                .apply("COALESCE(seckill_stock_locked, 0) > 0"));
        if (affected == 0) {
            return 0;
        }
        log.info("[seckill-release-remaining] sessionId={} skuId={} wareId={} released={}",
                sessionId, skuId, wareId, remaining);
        return remaining;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductLockedStock(String orderSn) {
        WareOrderTaskEntity task = wareOrderTaskService.getOne(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("order_sn", orderSn)
                        .last("LIMIT 1"),
                false);
        if (task == null) {
            throw new RRException(BizCodeEnum.WARE_ORDER_TASK_NOT_FOUND, orderSn);
        }
        if (Integer.valueOf(TASK_STATUS_DEDUCTED).equals(task.getTaskStatus())) {
            // 支付回调可能重复执行，已经扣减过就直接返回
            log.info("[stock-deduct] 订单 {} 已扣减过库存，跳过", orderSn);
            return;
        }

        int claimed = wareOrderTaskService.getBaseMapper().update(null,
                new UpdateWrapper<WareOrderTaskEntity>()
                        .set("task_status", TASK_STATUS_DEDUCTED)
                        .eq("id", task.getId())
                        .eq("task_status", TASK_STATUS_LOCKED));
        if (claimed == 0) {
            throw new RRException(BizCodeEnum.WARE_ORDER_TASK_RELEASED, orderSn);
        }

        List<WareOrderTaskDetailEntity> details = wareOrderTaskDetailService.list(
                new QueryWrapper<WareOrderTaskDetailEntity>()
                        .eq("task_id", task.getId())
                        .eq("lock_status", TASK_STATUS_LOCKED));

        for (WareOrderTaskDetailEntity detail : details) {
            int num = detail.getSkuNum() == null ? 0 : detail.getSkuNum();
            if (num <= 0) {
                continue;
            }

            // 支付成功：锁定转真实扣减。
            // stock 和 stock_locked 同时减，可用库存（stock - stock_locked - seckill_stock_locked）保持不变。
            int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                    .setSql("stock = COALESCE(stock, 0) - " + num
                            + ", stock_locked = COALESCE(stock_locked, 0) - " + num)
                    .eq("ware_id", detail.getWareId())
                    .eq("sku_id", detail.getSkuId())
                    .apply("COALESCE(stock, 0) >= " + num
                            + " AND COALESCE(stock_locked, 0) >= " + num));
            if (affected == 0) {
                throw new RRException(BizCodeEnum.WARE_STOCK_DEDUCT_FAILED, detail.getId());
            }

            wareOrderTaskDetailService.update(new UpdateWrapper<WareOrderTaskDetailEntity>()
                    .set("lock_status", TASK_STATUS_DEDUCTED)
                    .eq("id", detail.getId())
                    .eq("lock_status", TASK_STATUS_LOCKED));
        }

        // Redis 在下单时已经预扣过，这里不能再减
        log.info("[stock-deduct] 订单 {} 的锁定库存已转为真实扣减", orderSn);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void returnStock(String orderSn, Long skuId, Integer num) {
        if (num == null || num <= 0) {
            throw new RRException(BizCodeEnum.WARE_RETURN_STOCK_NUM_INVALID);
        }

        WareOrderTaskEntity task = wareOrderTaskService.getOne(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("order_sn", orderSn)
                        .last("LIMIT 1"),
                false);
        if (task == null) {
            throw new RRException(BizCodeEnum.WARE_ORDER_TASK_NOT_FOUND, orderSn);
        }

        List<WareOrderTaskDetailEntity> details = wareOrderTaskDetailService.list(
                new QueryWrapper<WareOrderTaskDetailEntity>()
                        .eq("task_id", task.getId())
                        .eq("sku_id", skuId));

        if (details.isEmpty()) {
            throw new RRException(BizCodeEnum.WARE_ORDER_TASK_DETAIL_NOT_FOUND, orderSn);
        }
        if (details.stream().anyMatch(d ->
                Integer.valueOf(TASK_STATUS_RETURNED).equals(d.getLockStatus()))) {
            log.info("[stock-return] 订单 {} 的 sku {} 已回补过库存，跳过", orderSn, skuId);
            return;
        }

        WareOrderTaskDetailEntity detail = details.stream()
                .filter(d -> Integer.valueOf(TASK_STATUS_DEDUCTED).equals(d.getLockStatus()))
                .findFirst()
                .orElseThrow(() -> new RRException(
                        BizCodeEnum.WARE_ORDER_TASK_DETAIL_NOT_FOUND, orderSn));

        // 先抢占：3 已扣减 -> 4 已退货，保证并发/重复请求只回补一次
        int claimed = wareOrderTaskDetailService.getBaseMapper().update(null,
                new UpdateWrapper<WareOrderTaskDetailEntity>()
                        .set("lock_status", TASK_STATUS_RETURNED)
                        .eq("id", detail.getId())
                        .eq("lock_status", TASK_STATUS_DEDUCTED));
        if (claimed == 0) {
            log.info("[stock-return] 明细 {} 已被其它请求处理，跳过", detail.getId());
            return;
        }

        // 支付时 stock 和 stock_locked 已同时减过，这里只加 stock
        int affected = baseMapper.update(null, new UpdateWrapper<WareSkuEntity>()
                .setSql("stock = COALESCE(stock, 0) + " + num)
                .eq("ware_id", detail.getWareId())
                .eq("sku_id", detail.getSkuId()));
        if (affected == 0) {
            // 正常不会发生；仓库记录被删过时补一条，保证库存不丢
            addStock(detail.getSkuId(), detail.getWareId(), detail.getSkuName(), num);
        }

        // 事务提交后再回写 Redis，避免事务回滚后缓存多出货
        Long returnedSkuId = detail.getSkuId();
        afterCommit(() -> refreshStockCache(returnedSkuId));

        log.info("[stock-return] 订单 {} 退货回补库存：skuId={} wareId={} num={}",
                orderSn, detail.getSkuId(), detail.getWareId(), num);
    }

    /**
     * 登记库存解锁延时消息。
     *
     * <p>这里只把消息写进本地消息表 mq_message，<b>不立即发送</b>。
     * 当前处在 Seata 全局事务里，如果事务结束前就把消息发出去，confirm 回调会
     * UPDATE mq_message.message_status，全局回滚时 Seata 校验 undo_log 会报
     * dirty undo log。所以交给补偿任务等全局事务结束后再投递；业务回滚时
     * 消息记录会随事务一起回滚。
     *
     * <p>补偿任务发送时会扣掉已经等待的时间，消息仍按 30 分钟到期；
     * 投递失败由共通模块继续重发，再兜不住还有 {@code StockReleaseTask}
     * 扫超时工作单。
     */
    private void sendReleaseMessage(Long taskId, String orderSn) {
        long delayMillis = stockReleaseDelayMs;

        StockReleaseMessage message = new StockReleaseMessage();
        message.setTaskId(taskId);
        message.setOrderSn(orderSn);

        mqMessageService.sendReliableDeferred(MqConstants.RETRY_EXCHANGE,
                MqConstants.STOCK_RELEASE_ROUTING_KEY, message, delayMillis);

        log.info("[stock-lock] 已登记库存解锁延时消息：taskId={} orderSn={} delay={}ms",
                taskId, orderSn, delayMillis);
    }

    /**
     * 事务提交后执行；没有事务时立即执行。
     *
     * <p>目前用于解锁库存后回补 Redis。执行失败只记日志：DB 已经提交，
     * Redis 失败由每日对账任务兜底，不能反过来把业务请求打挂。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
        } else {
            runQuietly(action);
        }
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[stock] 事务提交后的后续操作失败，等待定时任务/对账任务修复", e);
        }
    }

}
