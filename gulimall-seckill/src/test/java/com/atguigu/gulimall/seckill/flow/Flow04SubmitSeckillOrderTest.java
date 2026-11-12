package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 04：提交抢购订单。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；无额外业务服务（不需要 gulimall-order / gulimall-ware）。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow04SubmitSeckillOrderTest extends SeckillIntegrationTestSupport {

    @AfterEach
    void purgeCreateQueue() {
        // 1. 清理副作用：每个用例结束后清空秒杀建单队列，避免残留消息干扰下一个测试
        purgeSeckillOrderCreateQueue();
    }

    /**
     * 测试内容：提交抢购订单应返回排队建单中状态，落预扣秒杀单，Redis 库存扣到 99、限购计数加 1，并写入建单可靠消息。
     */
    @Test
    void submitReservesRedisAndWritesReliableMessage() {
        // 1. 准备测试数据：创建场次，登录测试会员，生成当前请求唯一的 requestId
        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        String requestId = "IT-" + UUID.randomUUID();

        // 2. 发起调用：提交抢购订单，确认接口成功，取出订单号并登记以便后续清理
        Map<String, Object> response = postJson("/seckill/order/submit",
                submitBody(fixture, requestId, 1, fixture.randomCode()), session);
        assertOk(response);
        Map<String, Object> order = orderResult(response);
        String orderSn = String.valueOf(order.get("orderSn"));
        trackOrderSn(orderSn);

        // 3. 断言结果：订单处于排队建单中状态，数据库已落预扣秒杀单
        assertEquals(SeckillOrderStatus.WAIT_BUILD, ((Number) order.get("status")).intValue());
        assertEquals("排队建单中", order.get("statusText"));
        assertNotNull(seckillOrderRow(orderSn), "没有落预扣单");
        // 4. 断言结果：Redis 库存扣到 99、会员限购计数加 1，请求去重 key 和预扣 key 都已写入
        assertEquals(99, redisStock(fixture.sessionId()));
        assertEquals(1, redisBought(fixture.sessionId()));
        assertTrue(redis.hasKey(SeckillConstant.requestKey(
                fixture.sessionId(), fixture.skuId(), TEST_MEMBER_ID)));
        assertTrue(redis.hasKey(SeckillConstant.reserveKey(orderSn)));

        // 5. 验证副作用：可靠消息表中存在一条指向秒杀建单交换机的消息，保证异步建单能触发
        List<Map<String, Object>> messages = smsJdbc.queryForList(
                "SELECT * FROM mq_message WHERE content LIKE ?", "%" + orderSn + "%");
        assertTrue(messages.stream().anyMatch(message ->
                        MqConstants.SECKILL_ORDER_EXCHANGE.equals(message.get("to_exchange"))),
                "没有写建单可靠消息");
    }

    /**
     * 测试内容：同一 requestId 重复提交应返回相同订单号，且 Redis 库存只扣减一次。
     */
    @Test
    void sameRequestIdReturnsSameOrderWithoutSecondDeduction() {
        // 1. 准备测试数据：创建场次，登录测试会员，生成当前请求唯一的 requestId
        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        String requestId = "IT-" + UUID.randomUUID();

        // 2. 发起调用：第一次提交抢购订单，确认成功并记录订单号
        Map<String, Object> first = postJson("/seckill/order/submit",
                submitBody(fixture, requestId, 1, fixture.randomCode()), session);
        assertOk(first);
        String firstOrderSn = String.valueOf(orderResult(first).get("orderSn"));
        trackOrderSn(firstOrderSn);

        // 3. 发起调用：用同一个 requestId 再提交一次，模拟用户重复点击
        Map<String, Object> second = postJson("/seckill/order/submit",
                submitBody(fixture, requestId, 1, fixture.randomCode()), session);
        assertOk(second);
        // 4. 断言结果：两次返回同一订单号，Redis 库存只扣一次仍然是 99
        assertEquals(firstOrderSn, String.valueOf(orderResult(second).get("orderSn")));
        assertEquals(99, redisStock(fixture.sessionId()), "重复 requestId 不应二次扣减");
    }

    /**
     * 测试内容：去重窗口内使用不同 requestId 的第二次提交应复用第一次的订单号，库存不再次扣减。
     */
    @Test
    void differentRequestIdWithinDedupeWindowReturnsOriginalOrder() {
        // 1. 准备测试数据：创建场次并登录测试会员
        Fixture fixture = createFixture();
        String session = loginAsTestMember();

        // 2. 发起调用：用第一个 requestId 提交订单，确认成功并记录订单号
        Map<String, Object> first = postJson("/seckill/order/submit",
                submitBody(fixture, "IT-" + UUID.randomUUID(), 1, fixture.randomCode()), session);
        assertOk(first);
        String firstOrderSn = String.valueOf(orderResult(first).get("orderSn"));
        trackOrderSn(firstOrderSn);

        // 3. 发起调用：在去重窗口内换一个 requestId 再提交一次
        Map<String, Object> second = postJson("/seckill/order/submit",
                submitBody(fixture, "IT-" + UUID.randomUUID(), 1, fixture.randomCode()), session);
        assertOk(second);
        // 4. 断言结果：第二次仍复用第一次的订单号，库存保持 99 不被再次扣减
        assertEquals(firstOrderSn, String.valueOf(orderResult(second).get("orderSn")));
        assertEquals(99, redisStock(fixture.sessionId()));
    }

    /**
     * 测试内容：错误随机码、缺失收货地址和未登录提交应分别被拒绝并返回 14007、14009、14001 错误码。
     */
    @Test
    void invalidRandomCodeAndParamsAreRejected() {
        // 1. 准备测试数据：创建场次并登录测试会员
        Fixture fixture = createFixture();
        String session = loginAsTestMember();

        // 2. 发起调用：用错误的随机码提交，断言返回 14007 随机码错误
        Map<String, Object> wrongCode = postJson("/seckill/order/submit",
                submitBody(fixture, "IT-" + UUID.randomUUID(), 1, "wrong-code"), session);
        assertCode(wrongCode, 14007);

        // 3. 发起调用：把请求体里的收货地址删掉后提交，断言返回 14009 参数缺失
        Map<String, Object> missingAddress = submitBody(
                fixture, "IT-" + UUID.randomUUID(), 1, fixture.randomCode());
        missingAddress.remove("addrId");
        assertCode(postJson("/seckill/order/submit", missingAddress, session), 14009);

        // 4. 发起调用：不带登录态提交，断言返回 14001 未登录
        assertCode(postJson("/seckill/order/submit",
                submitBody(fixture, "IT-" + UUID.randomUUID(), 1, fixture.randomCode()), null), 14001);
    }

    /**
     * 测试内容：未开始、已结束、售罄和超出限购四种场景提交订单应分别返回 14002、14003、14004、14005 错误码。
     */
    @Test
    void timeWindowAndStockAndLimitAreEnforced() {
        // 1. 准备测试数据：创建一个还没开始的场次并登录会员，提交后断言返回 14002 未开始
        Fixture notStarted = createFixture(
                new Date(System.currentTimeMillis() + 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000L),
                0, 10, 2);
        String session = loginAsTestMember();
        assertCode(postJson("/seckill/order/submit",
                submitBody(notStarted, "IT-" + UUID.randomUUID(), 1, notStarted.randomCode()),
                session), 14002);

        // 2. 准备测试数据：创建一个已经结束的场次，提交后断言返回 14003 已结束
        Fixture ended = createFixture(
                new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                3, 10, 2);
        assertCode(postJson("/seckill/order/submit",
                submitBody(ended, "IT-" + UUID.randomUUID(), 1, ended.randomCode()),
                session), 14003);

        // 3. 准备测试数据：创建一个库存为 0 的售罄场次，提交后断言返回 14004 库存不足
        Fixture soldOut = createFixture(
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() + 60 * 60 * 1000L),
                1, 0, 2);
        assertCode(postJson("/seckill/order/submit",
                submitBody(soldOut, "IT-" + UUID.randomUUID(), 1, soldOut.randomCode()),
                session), 14004);

        // 4. 准备测试数据：创建一个限购 1 件的场次但买 2 件，提交后断言返回 14005 超过限购
        Fixture limited = createFixture(
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() + 60 * 60 * 1000L),
                1, 10, 1);
        assertCode(postJson("/seckill/order/submit",
                submitBody(limited, "IT-" + UUID.randomUUID(), 2, limited.randomCode()),
                session), 14005);
    }

    private Map<String, Object> submitBody(Fixture fixture, String requestId,
                                           int quantity, String randomCode) {
        // 1. 把提交订单需要的场次、SKU、数量、地址、支付方式和随机码组装成一个请求体
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("sessionId", fixture.sessionId());
        body.put("skuId", fixture.skuId());
        body.put("quantity", quantity);
        body.put("addrId", fixture.addressId());
        body.put("payType", 1);
        body.put("randomCode", randomCode);
        return body;
    }
}
