package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 20：秒杀缓存 key、回源与降级。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-product（Hash 回源方法需要）。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow20CacheFallbackTest extends SeckillIntegrationTestSupport {

    @AfterEach
    void purgeQueue() {
        // 1. 每个测试结束后清理建单队列，避免下单用例留下的消息干扰后续流程
        purgeSeckillOrderCreateQueue();
    }

    /**
     * 测试内容：删除场次商品 hash 后请求商品列表，应回源数据库返回商品并把 hash 重新补回 Redis。
     */
    @Test
    void missingHashFallsBackToDatabaseAndRebuildsHash() {
        // 1. 确认商品服务可用，并创建测试场次和商品数据
        requireService("gulimall-product");
        Fixture fixture = createFixture();
        // 2. 删掉场次商品 hash，模拟 Redis 缓存丢失，让列表接口必须回源数据库
        redis.delete(SeckillConstant.sessionSkusKey(fixture.sessionId()));

        // 3. 请求秒杀商品列表，触发缓存缺失后的数据库回源
        Map<String, Object> response = getJson(
                "/seckill/skus?sessionId=" + fixture.sessionId(), null);
        // 4. 断言接口成功，并确认返回的商品列表里仍包含测试商品
        assertOk(response);
        List<Map<String, Object>> skus = listValue(response, "skus");
        assertTrue(skus.stream().anyMatch(item ->
                ((Number) item.get("skuId")).longValue() == TEST_SKU_ID));
        // 5. 确认回源后商品 hash 被重新写回 Redis，后续请求可以直接命中缓存
        assertNotNull(redis.opsForHash().get(SeckillConstant.sessionSkusKey(fixture.sessionId()),
                String.valueOf(fixture.skuId())), "回源后应补回 hash");
    }

    /**
     * 测试内容：场次商品 hash 中存入非法 JSON 时，商品列表应回源数据库并正常返回商品。
     */
    @Test
    void malformedHashFallsBackToDatabase() {
        // 1. 确认商品服务可用，并创建测试场次和商品数据
        requireService("gulimall-product");
        Fixture fixture = createFixture();
        // 2. 往场次商品 hash 里写入一段非法 JSON，模拟缓存数据损坏
        redis.opsForHash().put(SeckillConstant.sessionSkusKey(fixture.sessionId()),
                String.valueOf(fixture.skuId()), "not-json");

        // 3. 请求商品列表，验证反序列化失败时会自动回源数据库而不是直接报错
        Map<String, Object> response = getJson(
                "/seckill/skus?sessionId=" + fixture.sessionId(), null);
        // 4. 断言接口仍然成功，并且返回的商品列表里包含测试商品
        assertOk(response);
        List<Map<String, Object>> skus = listValue(response, "skus");
        assertTrue(skus.stream().anyMatch(item ->
                ((Number) item.get("skuId")).longValue() == TEST_SKU_ID));
    }

    /**
     * 测试内容：商品 hash 回源数据库时不应凭空创建库存 key，Redis 中仍不存在该库存 key。
     */
    @Test
    void databaseFallbackDoesNotCreateStockKey() {
        // 1. 确认商品服务可用，并创建测试场次和商品数据
        requireService("gulimall-product");
        Fixture fixture = createFixture();
        // 2. 同时删掉商品 hash 和库存 key，确保场景是“只有商品信息缺失、库存也未初始化”
        redis.delete(SeckillConstant.sessionSkusKey(fixture.sessionId()));
        redis.delete(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId()));

        // 3. 请求商品列表触发 hash 回源，观察回源过程会不会顺带创建库存 key
        Map<String, Object> response = getJson(
                "/seckill/skus?sessionId=" + fixture.sessionId(), null);
        assertOk(response);
        // 4. 断言库存 key 仍不存在，说明商品信息回源不会凭空创建库存，库存只能由正式上架初始化
        assertEquals(Boolean.FALSE, redis.hasKey(
                        SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId())),
                "商品 hash 回源不能凭空创建库存 key");
    }

    /**
     * 测试内容：库存 key 不存在时，商品详情接口应把库存上报为 0。
     */
    @Test
    void missingStockKeyIsReportedAsZero() {
        // 1. 创建测试场次和商品数据，作为商品详情请求的查询条件
        Fixture fixture = createFixture();
        // 2. 删掉秒杀库存 key，模拟库存还没有初始化或已经过期
        redis.delete(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId()));

        // 3. 请求商品详情，观察库存 key 缺失时接口如何返回库存
        Map<String, Object> response = getJson("/seckill/sku/info?sessionId="
                + fixture.sessionId() + "&skuId=" + fixture.skuId(), null);
        assertOk(response);
        // 4. 断言详情里的 stock 是 0，避免把“没有库存 key”误读成有库存
        Map<String, Object> info = mapValue(response, "seckillSkuInfo");
        assertEquals(0, ((Number) info.get("stock")).intValue());
    }

    /**
     * 测试内容：秒杀下单成功后，请求去重 key 与预占 key 均应存在过期时间，且去重 key 的 TTL 不超过 10 秒。
     */
    @Test
    void requestDedupeAndReserveKeysExpire() {
        // 1. 创建测试场次和商品数据，并登录测试会员拿到下单 token
        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        // 2. 组装一次完整的秒杀下单请求体，带上唯一 requestId、场次、商品、地址和随机码
        Map<String, Object> body = Map.of(
                "requestId", "IT-" + java.util.UUID.randomUUID(),
                "sessionId", fixture.sessionId(),
                "skuId", fixture.skuId(),
                "quantity", 1,
                "addrId", fixture.addressId(),
                "payType", 1,
                "randomCode", fixture.randomCode());
        // 3. 提交秒杀下单请求，预期成功并返回订单号
        Map<String, Object> response = postJson("/seckill/order/submit", body, session);
        assertOk(response);
        // 4. 取出订单号并登记，测试结束后据此清理订单数据
        String orderSn = String.valueOf(orderResult(response).get("orderSn"));
        trackOrderSn(orderSn);

        // 5. 验证请求去重 key 和预占 key 都设置了过期时间，并且去重 key 的 TTL 不超过 10 秒
        assertNotNull(redis.getExpire(SeckillConstant.requestKey(
                fixture.sessionId(), fixture.skuId(), TEST_MEMBER_ID)));
        assertNotNull(redis.getExpire(SeckillConstant.reserveKey(orderSn)));
        assertTrue(redis.getExpire(SeckillConstant.requestKey(
                fixture.sessionId(), fixture.skuId(), TEST_MEMBER_ID)) <= 10);
    }
}
