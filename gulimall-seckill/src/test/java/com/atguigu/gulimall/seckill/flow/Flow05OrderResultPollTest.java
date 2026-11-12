package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流程 05：抢购结果轮询。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow05OrderResultPollTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：结果轮询接口应支持按 orderSn 和 requestId 两种方式查询，并返回同一订单号及“排队建单中”状态文案。
     */
    @Test
    void queryByOrderSnAndRequestId() {
        // 1. 准备测试数据：创建场次，登录测试会员，插入一条排队建单中的秒杀单并记下 requestId
        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        String requestId = "IT-" + UUID.randomUUID();
        String orderSn = insertSeckillOrder(fixture, requestId, SeckillOrderStatus.WAIT_BUILD, 1);

        // 2. 发起调用并断言：按 orderSn 查询结果，应返回同一订单号和“排队建单中”文案
        Map<String, Object> bySn = getJson("/seckill/order/result?orderSn=" + orderSn, session);
        assertOk(bySn);
        Map<String, Object> result = orderResult(bySn);
        assertEquals(orderSn, result.get("orderSn"));
        assertEquals("排队建单中", result.get("statusText"));

        // 3. 发起调用并断言：改按 requestId 查询，仍应返回同一个订单号
        Map<String, Object> byRequest = getJson(
                "/seckill/order/result?requestId=" + requestId, session);
        assertOk(byRequest);
        assertEquals(orderSn, orderResult(byRequest).get("orderSn"));
    }

    /**
     * 测试内容：订单状态变更为待支付、已取消、建单失败、已支付时，轮询返回的状态文案应分别对应正确。
     */
    @Test
    void statusTextFollowsOrderStatus() {
        // 1. 准备测试数据：创建场次，登录测试会员，插入一条排队建单中的秒杀单
        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        String orderSn = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.WAIT_BUILD, 1);

        // 2. 断言结果：把订单改成已建单后轮询，状态文案应为“待支付”
        assertStatusText(session, orderSn, SeckillOrderStatus.BUILT, "待支付");
        // 3. 断言结果：把订单改成已释放后轮询，状态文案应为“已取消”
        assertStatusText(session, orderSn, SeckillOrderStatus.RELEASED, "已取消");
        // 4. 断言结果：把订单改成建单失败后轮询，状态文案应为“建单失败”
        assertStatusText(session, orderSn, SeckillOrderStatus.BUILD_FAILED, "建单失败");
        // 5. 断言结果：把订单改成已支付后轮询，状态文案应为“已支付”
        assertStatusText(session, orderSn, SeckillOrderStatus.PAID, "已支付");
    }

    /**
     * 测试内容：非订单所属会员查询抢购结果时应被拒绝并返回 14008 错误码。
     */
    @Test
    void otherMemberCannotReadOrder() {
        // 1. 准备测试数据：创建场次，插入测试会员的秒杀单，再登录另一个会员拿到会话
        Fixture fixture = createFixture();
        String orderSn = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.WAIT_BUILD, 1);
        String otherSession = loginAsMember(990002L, "it_other");

        // 2. 发起调用：用别的会员会话去查询这张订单的结果
        Map<String, Object> response = getJson("/seckill/order/result?orderSn=" + orderSn,
                otherSession);
        // 3. 断言结果：应被拒绝并返回 14008 无权查看错误码
        assertCode(response, 14008);
    }

    /**
     * 测试内容：既未传 orderSn 也未传 requestId 的轮询请求应返回 14009 参数缺失错误码。
     */
    @Test
    void missingOrderSnAndRequestIdIsRejected() {
        // 1. 准备测试数据：创建场次并登录测试会员，保证登录校验不会先拦住请求
        createFixture();
        String session = loginAsTestMember();

        // 2. 发起调用：什么单号参数都不带地请求结果接口
        Map<String, Object> response = getJson("/seckill/order/result", session);
        // 3. 断言结果：应返回 14009 参数缺失错误码
        assertCode(response, 14009);
    }

    private void assertStatusText(String session, String orderSn, int status, String expected) {
        // 1. 先把订单状态改成用例指定的值
        smsJdbc.update("UPDATE sms_seckill_order SET status = ? WHERE order_sn = ?", status, orderSn);
        // 2. 发起调用：按订单号请求结果接口，确认调用成功
        Map<String, Object> response = getJson("/seckill/order/result?orderSn=" + orderSn, session);
        assertOk(response);
        // 3. 断言结果：接口返回的状态文案应和预期一致
        assertEquals(expected, orderResult(response).get("statusText"));
    }
}
