package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.api.order.vo.SeckillOrderCreateVo;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 流程 07：用户取消订单回补。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-order、gulimall-ware、gulimall-member。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow07UserCancelReleaseTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：进行中场次取消订单后，秒杀单释放模式应为回补秒杀池，Redis 库存恢复到 100、限购计数清零，ware 秒杀锁定库存回退。
     */
    @Test
    void cancelRunningSessionReturnsStockToSeckillPool() throws Exception {
        // 1. 准备测试数据：确认 order、ware、member 服务可用，创建进行中场次并造一条已建单的订单
        String orderBase = requireService("gulimall-order");
        requireService("gulimall-ware");
        requireService("gulimall-member");

        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        BuiltOrder built = createBuiltOrder(orderBase, fixture, session);

        // 2. 发起调用：调用 order 服务的取消订单接口，确认取消请求成功
        Map<String, Object> response = postServiceForm(orderBase, "/pay/cancel",
                Map.of("orderSn", built.orderSn()), session);
        assertOk(response);

        // 3. 等待结果：最多等 20 秒，直到秒杀单状态被异步改成已释放
        awaitUntil("取消后秒杀单释放", Duration.ofSeconds(20),
                () -> Objects.equals(SeckillOrderStatus.RELEASED,
                        seckillOrderStatus(built.orderSn())));

        // 4. 断言结果：释放模式为回补秒杀池，Redis 秒杀库存回到 100、限购计数清零
        Map<String, Object> order = seckillOrderRow(built.orderSn());
        assertEquals(SeckillOrderStatus.RELEASE_TO_SECKILL,
                ((Number) order.get("release_mode")).intValue());
        assertEquals(100, redisStock(fixture.sessionId()));
        assertEquals(0, redisBought(fixture.sessionId()));

        // 5. 断言结果：ware 普通锁定库存归 0，秒杀锁定库存保留为 1，说明库存已回补到秒杀池
        Map<String, Object> ware = wareSkuRow();
        assertEquals(0, ((Number) ware.get("stock_locked")).intValue());
        assertEquals(1, ((Number) ware.get("seckill_stock_locked")).intValue());
    }

    /**
     * 测试内容：已结束场次取消订单后，释放模式应为回补普通库存，Redis 秒杀库存保持 99，ware 秒杀锁定库存清零。
     */
    @Test
    void cancelEndedSessionReturnsStockToNormalPool() throws Exception {
        // 1. 准备测试数据：确认服务可用，创建一个已结束的场次并造一条已建单的订单
        String orderBase = requireService("gulimall-order");
        requireService("gulimall-ware");
        requireService("gulimall-member");

        Fixture fixture = createFixture(
                new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                1, 100, 2);
        String session = loginAsTestMember();
        BuiltOrder built = createBuiltOrder(orderBase, fixture, session);

        // 2. 发起调用：调用 order 服务的取消订单接口，确认取消请求成功
        Map<String, Object> response = postServiceForm(orderBase, "/pay/cancel",
                Map.of("orderSn", built.orderSn()), session);
        assertOk(response);

        // 3. 等待结果：最多等 20 秒，直到秒杀单状态被异步改成已释放
        awaitUntil("取消后秒杀单释放", Duration.ofSeconds(20),
                () -> Objects.equals(SeckillOrderStatus.RELEASED,
                        seckillOrderStatus(built.orderSn())));

        // 4. 断言结果：释放模式为回补普通库存，已结束场次的 Redis 秒杀库存保持 99 不动
        Map<String, Object> order = seckillOrderRow(built.orderSn());
        assertEquals(SeckillOrderStatus.RELEASE_TO_NORMAL,
                ((Number) order.get("release_mode")).intValue());
        assertEquals(99, redisStock(fixture.sessionId()),
                "场次已结束不应把库存写回秒杀 key");

        // 5. 断言结果：ware 普通锁定和秒杀锁定库存都已清零，说明库存回到了普通池
        Map<String, Object> ware = wareSkuRow();
        assertEquals(0, ((Number) ware.get("stock_locked")).intValue());
        assertEquals(0, ((Number) ware.get("seckill_stock_locked")).intValue());
    }

    private BuiltOrder createBuiltOrder(String orderBase, Fixture fixture, String session) {
        // 1. 插入一条已建单状态的秒杀单，作为后续取消操作的测试对象
        String orderSn = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.BUILT, 1);
        // 2. 准备 ware 库存以及 Redis 库存和限购计数，模拟抢购已经扣减后的状态
        setWareStock(100, 0, 1);
        redis.opsForValue().set(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId()),
                "99");
        redis.opsForHash().put(SeckillConstant.userCountKey(fixture.sessionId(), fixture.skuId()),
                String.valueOf(TEST_MEMBER_ID), "1");

        // 3. 组装建单需要的商品、场次、会员和地址信息
        SeckillOrderCreateVo vo = new SeckillOrderCreateVo();
        vo.setOrderSn(orderSn);
        vo.setRequestId("IT-" + UUID.randomUUID());
        vo.setMemberId(TEST_MEMBER_ID);
        vo.setMemberUsername(TEST_USERNAME);
        vo.setSessionId(fixture.sessionId());
        vo.setSkuId(fixture.skuId());
        vo.setSkuName("IT 秒杀商品");
        vo.setSkuPic("https://example.test/sku.png");
        vo.setWareId(fixture.wareId());
        vo.setQuantity(1);
        vo.setSeckillPrice(new BigDecimal("9.90"));
        vo.setAddrId(fixture.addressId());
        vo.setPayType(1);

        // 4. 发起调用：调用 order 服务内部建单接口，确认成功并拿到 orderId
        Map<String, Object> response = postServiceJson(orderBase,
                "/internal/order/seckill/create", vo, session);
        assertOk(response);
        assertNotNull(response.get("orderId"), "order 没有返回 orderId");
        long orderId = ((Number) response.get("orderId")).longValue();
        // 5. 把 orderId 回填到秒杀单上，返回订单号和 orderId 给用例使用
        updateSeckillOrder(orderSn, SeckillOrderStatus.BUILT, orderId);
        return new BuiltOrder(orderSn, orderId);
    }

    private record BuiltOrder(String orderSn, long orderId) {
    }
}
