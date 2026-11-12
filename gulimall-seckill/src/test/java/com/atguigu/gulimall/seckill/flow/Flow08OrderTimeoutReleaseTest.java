package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.api.order.vo.SeckillOrderCreateVo;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 流程 08：订单超时关闭回补。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-order、gulimall-ware、gulimall-member。
 * <p>需要关闭：本机 gulimall-seckill。
 * <p>OrderTimeoutTask 每分钟整点跑一次，本测试最多等待约 90 秒。
 */
@Tag("integration")
class Flow08OrderTimeoutReleaseTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：把已创建订单的创建时间改到 31 分钟前，验证 OrderTimeoutTask 关闭超时订单、秒杀单置为 RELEASED，并核对 Redis 秒杀库存回补为 100、ware 锁定库存清零且 seckill_stock_locked 保留为 1。
     */
    @Test
    void timeoutTaskClosesOrderAndReleasesSeckillStock() throws Exception {
        // 1. 准备测试数据：确认 order、ware、member 服务可用，创建进行中场次并造一条已建单的订单
        String orderBase = requireService("gulimall-order");
        requireService("gulimall-ware");
        requireService("gulimall-member");

        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        BuiltOrder built = createBuiltOrder(orderBase, fixture, session);

        // 2. 修改数据：把订单创建时间改到 31 分钟前，让 OrderTimeoutTask 下一次扫描就能命中
        jdbc("gulimall_oms").update("UPDATE oms_order SET create_time = DATE_SUB(NOW(), INTERVAL 31 MINUTE) "
                + "WHERE order_sn = ?", built.orderSn());

        // 3. 等待结果：最多等 2 分钟，直到 order 服务把订单状态改成已关闭（4）
        awaitUntil("订单超时关闭", Duration.ofMinutes(2),
                () -> {
                    Map<String, Object> order = omsOrderRow(built.orderSn());
                    return order != null && Objects.equals(4, ((Number) order.get("status")).intValue());
                });
        // 4. 等待结果：最多等 30 秒，直到秒杀单状态被异步改成已释放
        awaitUntil("秒杀单超时释放", Duration.ofSeconds(30),
                () -> Objects.equals(SeckillOrderStatus.RELEASED,
                        seckillOrderStatus(built.orderSn())));

        // 5. 断言结果：释放模式为回补秒杀池，Redis 秒杀库存回到 100
        assertEquals(SeckillOrderStatus.RELEASE_TO_SECKILL,
                ((Number) seckillOrderRow(built.orderSn()).get("release_mode")).intValue());
        assertEquals(100, redisStock(fixture.sessionId()));
        // 6. 断言结果：ware 普通锁定库存归 0，秒杀锁定库存保留为 1
        Map<String, Object> ware = wareSkuRow();
        assertEquals(0, ((Number) ware.get("stock_locked")).intValue());
        assertEquals(1, ((Number) ware.get("seckill_stock_locked")).intValue());
    }

    private BuiltOrder createBuiltOrder(String orderBase, Fixture fixture, String session) {
        // 1. 插入一条已建单状态的秒杀单，作为超时任务的测试对象
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
        assertNotNull(response.get("orderId"));
        long orderId = ((Number) response.get("orderId")).longValue();
        // 5. 把 orderId 回填到秒杀单上，返回订单号和 orderId 给用例使用
        updateSeckillOrder(orderSn, SeckillOrderStatus.BUILT, orderId);
        return new BuiltOrder(orderSn, orderId);
    }

    private record BuiltOrder(String orderSn, long orderId) {
    }
}
