package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 11：后台秒杀关联商品管理。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-coupon、gulimall-ware、gulimall-product。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow11AdminSkuRelationTest extends SeckillIntegrationTestSupport {

    @BeforeEach
    void setUpBaseData() {
        // 1. 每个用例开始前确保会员、地址、SKU、仓储库存等基础测试数据存在
        ensureBaseData();
    }

    /**
     * 测试内容：依次新增、修改、删除秒杀商品关联，验证关联落库、ware 的 seckill_stock_locked 随数量同步以及 Redis 库存 key 的写入与删除保持一致。
     */
    @Test
    void addUpdateAndDeleteRelationKeepsWareAndRedisConsistent() {
        // 1. 准备测试数据：确认 coupon、ware 服务可用，创建一个未来开始的场次
        String coupon = requireService("gulimall-coupon");
        requireService("gulimall-ware");
        long sessionId = createFutureSession(coupon, 1);

        // 2. 发起调用：新增一条数量为 5 的秒杀商品关联并确认成功
        assertOk(postServiceJson(coupon, "/coupon/seckillskurelation/save",
                relationBody(sessionId, TEST_SKU_ID, 5)));
        Map<String, Object> relation = relationRow(sessionId);
        // 3. 断言结果：关联已落库，ware 秒杀锁定库存为 5，Redis 库存 key 的值也是 5
        assertNotNull(relation, "关联没有落库");
        assertWareSeckillLocked(5);
        assertEquals("5", redis.opsForValue().get(
                SeckillConstant.stockKey(sessionId, TEST_SKU_ID)));

        // 4. 发起调用：把关联数量改成 8 并确认成功后，断言 ware 秒杀锁定库存同步为 8
        long relationId = ((Number) relation.get("id")).longValue();
        Map<String, Object> update = relationBody(sessionId, TEST_SKU_ID, 8);
        update.put("id", relationId);
        assertOk(postServiceJson(coupon, "/coupon/seckillskurelation/update", update));
        assertWareSeckillLocked(8);

        // 5. 发起调用：删除这条关联并确认成功
        assertOk(postServiceJson(coupon, "/coupon/seckillskurelation/delete",
                List.of(relationId)));
        // 6. 断言结果：关联记录已删除，ware 锁定库存归 0，Redis 库存 key 也被删除
        assertEquals(0, smsJdbc.queryForObject(
                "SELECT COUNT(*) FROM sms_seckill_sku_relation WHERE id = ?",
                Integer.class, relationId));
        assertWareSeckillLocked(0);
        assertEquals(Boolean.FALSE, redis.hasKey(SeckillConstant.stockKey(sessionId, TEST_SKU_ID)));
    }

    /**
     * 测试内容：同一 SKU 在时间重叠的场次以及已开始的场次上新增关联，验证两种情况都返回非 0 业务码被拒绝。
     */
    @Test
    void overlappingSessionAndStartedSessionRejectNewRelation() {
        // 1. 准备测试数据：确认 coupon、ware 服务可用，创建一个未来场次并给测试 SKU 加上关联
        String coupon = requireService("gulimall-coupon");
        requireService("gulimall-ware");

        long first = createFutureSession(coupon, 1);
        assertOk(postServiceJson(coupon, "/coupon/seckillskurelation/save",
                relationBody(first, TEST_SKU_ID, 5)));

        // 2. 发起调用：在时间重叠的另一个场次上，再给同一个 SKU 新增关联
        long overlapping = createFutureSession(coupon, 1);
        Map<String, Object> overlapResponse = postServiceJson(coupon,
                "/coupon/seckillskurelation/save", relationBody(overlapping, TEST_SKU_ID, 5));
        // 3. 断言结果：返回非 0 业务码，说明重叠场次被拒绝
        assertNotNull(overlapResponse);
        assertTrue(((Number) overlapResponse.get("code")).intValue() != 0,
                "同 SKU 时间重叠场次应拒绝");

        // 4. 准备测试数据：再建一个场次，并把它改成已经开始的进行中状态
        long started = createFutureSession(coupon, 1);
        smsJdbc.update("UPDATE sms_seckill_session SET start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR), "
                + "end_time = DATE_ADD(NOW(), INTERVAL 1 HOUR) WHERE id = ?", started);
        // 5. 发起调用：在已开始场次上给同一 SKU 新增关联
        Map<String, Object> startedResponse = postServiceJson(coupon,
                "/coupon/seckillskurelation/save", relationBody(started, TEST_SKU_ID, 5));
        // 6. 断言结果：返回非 0 业务码，说明已开始场次不允许新增关联
        assertNotNull(startedResponse);
        assertTrue(((Number) startedResponse.get("code")).intValue() != 0,
                "已开始场次不允许新增关联");
    }

    /**
     * 测试内容：删除场次商品 hash 后调用秒杀刷新接口，验证接口成功并重新构建出包含该 SKU 的商品 hash。
     */
    @Test
    void refreshEndpointRebuildsSkuHash() {
        // 1. 准备测试数据：确认 coupon、ware、product 服务可用，创建未来场次并给测试 SKU 加关联
        String coupon = requireService("gulimall-coupon");
        requireService("gulimall-ware");
        requireService("gulimall-product");
        long sessionId = createFutureSession(coupon, 1);
        assertOk(postServiceJson(coupon, "/coupon/seckillskurelation/save",
                relationBody(sessionId, TEST_SKU_ID, 5)));

        // 2. 删除数据：删掉 Redis 里的场次商品 hash，模拟缓存丢失
        redis.delete(SeckillConstant.sessionSkusKey(sessionId));
        // 3. 发起调用：调用秒杀刷新接口，确认接口成功
        Map<String, Object> response = postForm("/seckill/refresh",
                Map.of("sessionId", String.valueOf(sessionId),
                        "skuId", String.valueOf(TEST_SKU_ID)), null);
        assertOk(response);
        // 4. 断言结果：商品 hash 已重建，能重新查到该 SKU
        assertNotNull(redis.opsForHash().get(SeckillConstant.sessionSkusKey(sessionId),
                String.valueOf(TEST_SKU_ID)), "刷新后应重建商品 hash");
    }

    private long createFutureSession(String coupon, int count) {
        // 1. 生成唯一场次名和未来开始的起止时间，组装成场次保存请求体
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        Date end = new Date(start.getTime() + 60 * 60 * 1000L);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("startTime", format.format(start));
        body.put("endTime", format.format(end));
        body.put("promotionId", null);
        // 2. 调用 coupon 服务保存场次，并确认请求成功
        assertOk(postServiceJson(coupon, "/coupon/seckillsession/save", body));
        // 3. 按名称查出新场次 id，登记到清理列表后返回给用例使用
        Long id = smsJdbc.queryForObject(
                "SELECT id FROM sms_seckill_session WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
        assertNotNull(id);
        trackSessionId(id);
        return id;
    }

    private Map<String, Object> relationBody(long sessionId, long skuId, int count) {
        // 1. 把场次、SKU、数量、限购、价格和排序组装成关联保存请求体
        Map<String, Object> body = new HashMap<>();
        body.put("promotionSessionId", sessionId);
        body.put("skuId", skuId);
        body.put("seckillCount", count);
        body.put("seckillLimit", 2);
        body.put("seckillPrice", new BigDecimal("9.90"));
        body.put("seckillSort", 1);
        return body;
    }

    private Map<String, Object> relationRow(long sessionId) {
        // 1. 按场次 id 查出最近一条秒杀商品关联，查不到时返回 null
        List<Map<String, Object>> rows = smsJdbc.queryForList(
                "SELECT * FROM sms_seckill_sku_relation WHERE promotion_session_id = ? LIMIT 1",
                sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertWareSeckillLocked(int expected) {
        // 1. 查出 ware 库存行，断言秒杀锁定数量等于期望值
        Map<String, Object> ware = wareSkuRow();
        assertNotNull(ware, "仓储测试库存行不存在");
        assertEquals(expected, ((Number) ware.get("seckill_stock_locked")).intValue());
    }
}
