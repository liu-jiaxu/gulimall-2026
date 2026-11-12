package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 流程 02：商品详情页秒杀模式。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-product（用于商品缓存缺失时的 Hash 回源）。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow02SeckillItemDetailTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：未登录用户查询秒杀 SKU 信息时，应看到已购 0 件、限购 2 件、库存 100 件以及秒杀价 9.90。
     */
    @Test
    void guestSeesPriceStockAndLimit() {
        // 1. 准备测试数据：创建一个进行中的测试场次和测试 SKU
        Fixture fixture = createFixture();

        // 2. 发起调用：以游客身份请求秒杀 SKU 信息接口，确认调用成功并取出信息对象
        Map<String, Object> response = getJson("/seckill/sku/info?sessionId="
                + fixture.sessionId() + "&skuId=" + fixture.skuId(), null);
        assertOk(response);
        Map<String, Object> info = mapValue(response, "seckillSkuInfo");

        // 3. 断言结果：游客已购 0 件、限购 2 件、库存 100 件，秒杀价为 9.90
        assertEquals(0, ((Number) info.get("bought")).intValue());
        assertEquals(2, ((Number) info.get("limit")).intValue());
        assertEquals(100, ((Number) info.get("stock")).intValue());
        Map<String, Object> sku = mapValue(info, "sku");
        assertEquals(0, new BigDecimal("9.90").compareTo(new BigDecimal(
                String.valueOf(sku.get("seckillPrice")))));
    }

    /**
     * 测试内容：已登录会员的已购数量应从 Redis 用户计数 hash 中读取，接口返回 bought 为 1。
     */
    @Test
    void loggedInMemberSeesBoughtCount() {
        // 1. 准备测试数据：创建场次，在 Redis 用户计数 hash 中把测试会员已购数量写成 1
        Fixture fixture = createFixture();
        redis.opsForHash().put(SeckillConstant.userCountKey(fixture.sessionId(), fixture.skuId()),
                String.valueOf(TEST_MEMBER_ID), "1");
        // 2. 登录测试会员拿到会话，再带登录态请求秒杀 SKU 信息接口并确认成功
        String sessionId = loginAsTestMember();

        Map<String, Object> response = getJson("/seckill/sku/info?sessionId="
                + fixture.sessionId() + "&skuId=" + fixture.skuId(), sessionId);
        assertOk(response);
        Map<String, Object> info = mapValue(response, "seckillSkuInfo");
        // 3. 断言结果：已购数量应从 Redis 用户计数中读到 1
        assertEquals(1, ((Number) info.get("bought")).intValue());
    }

    /**
     * 测试内容：Redis 中库存 key 缺失时，秒杀 SKU 信息接口应把库存降级返回为 0。
     */
    @Test
    void missingStockKeyReturnsZero() {
        // 1. 准备测试数据：创建场次后删除 Redis 里的库存 key，模拟库存数据丢失
        Fixture fixture = createFixture();
        redis.delete(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId()));

        // 2. 发起调用：请求秒杀 SKU 信息接口，确认调用成功并取出信息对象
        Map<String, Object> response = getJson("/seckill/sku/info?sessionId="
                + fixture.sessionId() + "&skuId=" + fixture.skuId(), null);
        assertOk(response);
        Map<String, Object> info = mapValue(response, "seckillSkuInfo");
        // 3. 断言结果：库存应降级返回 0，而不是接口报错
        assertEquals(0, ((Number) info.get("stock")).intValue());
    }

    /**
     * 测试内容：场次商品 hash 缺失时，信息接口应回源数据库和 gulimall-product，返回正确 SKU 并把商品信息重新写回 hash。
     */
    @Test
    void missingHashFallsBackToDatabaseAndProduct() {
        // 1. 准备测试数据：创建场次后删除 Redis 的场次商品 hash，并确认 product 服务可用
        Fixture fixture = createFixture();
        redis.delete(SeckillConstant.sessionSkusKey(fixture.sessionId()));
        requireService("gulimall-product");

        // 2. 发起调用：请求秒杀 SKU 信息接口，确认调用成功并取出信息和商品对象
        Map<String, Object> response = getJson("/seckill/sku/info?sessionId="
                + fixture.sessionId() + "&skuId=" + fixture.skuId(), null);
        assertOk(response);
        Map<String, Object> info = mapValue(response, "seckillSkuInfo");
        Map<String, Object> sku = mapValue(info, "sku");
        // 3. 断言结果：返回的 SKU 正确，并且商品信息已被回填到 Redis 场次 hash 中
        assertEquals(TEST_SKU_ID, ((Number) sku.get("skuId")).longValue());
        assertNotNull(redis.opsForHash().get(SeckillConstant.sessionSkusKey(fixture.sessionId()),
                String.valueOf(fixture.skuId())), "回源后应把商品信息补回 hash");
    }

    /**
     * 测试内容：查询不存在的 skuId 时接口仍返回成功，但 seckillSkuInfo 为 null。
     */
    @Test
    void unknownSkuReturnsNull() {
        // 1. 准备测试数据：创建一个进行中的测试场次
        Fixture fixture = createFixture();

        // 2. 发起调用：用一个不存在的 skuId 请求秒杀 SKU 信息接口，确认调用成功
        Map<String, Object> response = getJson("/seckill/sku/info?sessionId="
                + fixture.sessionId() + "&skuId=99999999", null);
        assertOk(response);
        // 3. 断言结果：接口返回成功，但 seckillSkuInfo 为 null，表示没有这个商品
        assertNull(response.get("seckillSkuInfo"));
    }
}
