package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.api.seckill.vo.SeckillSkuInfoVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.seckill.controller.SeckillPageController;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 03：秒杀确认页。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-member。
 * <p>需要关闭：本机 gulimall-seckill。
 * 登录态流程直接调用真实 Controller，重点验证默认地址、数量上限和模型数据。
 */
@Tag("integration")
class Flow03SeckillConfirmPageTest extends SeckillIntegrationTestSupport {

    @Autowired
    private SeckillPageController seckillPageController;

    /**
     * 测试内容：未登录用户访问确认页应返回 302，并重定向到带 returnUrl 参数的登录页。
     */
    @Test
    void guestIsRedirectedToLogin() throws Exception {
        // 1. 准备测试数据：创建一个进行中的测试场次和测试 SKU
        Fixture fixture = createFixture();
        // 2. 发起调用：不带登录态访问确认页，拿到原始响应
        RawResponse response = rawGet("/confirm.html?sessionId=" + fixture.sessionId()
                + "&skuId=" + fixture.skuId() + "&num=1", null);

        // 3. 断言结果：应返回 302，并跳转到带 returnUrl 参数的登录页
        assertEquals(302, response.status());
        String location = firstHeader(response, "location");
        assertNotNull(location);
        assertTrue(location.contains("/login.html?returnUrl="), "未登录应跳登录页：" + location);
    }

    /**
     * 测试内容：已登录会员打开确认页应渲染 seckill-confirm 视图，模型中有默认地址，且购买数量按限购 2 件截断。
     */
    @Test
    void loggedInMemberGetsDefaultAddressAndQuantityLimit() {
        // 1. 准备测试数据：创建场次，确认会员服务可用，并准备空模型给 Controller 填充
        Fixture fixture = createFixture();
        requireService("gulimall-member");

        ExtendedModelMap model = new ExtendedModelMap();
        // 2. 发起调用：直接调用真实确认页 Controller，购买数量故意传 5 以验证限购截断
        String view = seckillPageController.confirmPage(fixture.sessionId(), fixture.skuId(),
                5, loginSession(), model);

        // 3. 断言结果：返回确认页视图，模型里有默认地址，购买数量被限购截断为 2
        assertEquals("seckill-confirm", view);
        assertNotNull(model.getAttribute("defaultAddress"), "确认页没有默认地址");
        assertEquals(2, model.getAttribute("maxQuantity"));
        assertEquals(2, model.getAttribute("quantity"), "数量应按限购截断为 2");
    }

    /**
     * 测试内容：会员没有默认收货地址时，确认页应重定向到个人中心并带上 needAddress=1 参数。
     */
    @Test
    void memberWithoutDefaultAddressIsRedirectedToMemberCenter() {
        // 1. 准备测试数据：创建场次，确认会员服务可用，并把测试会员的默认地址改成非默认
        Fixture fixture = createFixture();
        requireService("gulimall-member");
        jdbc("gulimall_ums").update("UPDATE ums_member_receive_address "
                + "SET default_status = 0 WHERE member_id = ?", TEST_MEMBER_ID);

        // 2. 发起调用：直接调用确认页 Controller，模拟会员没有默认收货地址
        String view = seckillPageController.confirmPage(fixture.sessionId(), fixture.skuId(),
                1, loginSession(), new ExtendedModelMap());

        // 3. 断言结果：应重定向到个人中心，并带上 needAddress=1 提示用户补地址
        assertTrue(view.startsWith("redirect:"), "无默认地址应重定向");
        assertTrue(view.contains("needAddress=1"), "无默认地址应去个人中心：" + view);
        // 4. 清理副作用：把测试会员的默认地址恢复为默认，避免影响其他测试
        jdbc("gulimall_ums").update("UPDATE ums_member_receive_address "
                + "SET default_status = 1 WHERE member_id = ?", TEST_MEMBER_ID);
    }

    /**
     * 测试内容：确认页应把秒杀商品信息放入模型，库存为 100、限购为 2。
     */
    @Test
    void skuInfoIsPutIntoModel() {
        // 1. 准备测试数据：创建场次，确认会员服务可用，并准备空模型给 Controller 填充
        Fixture fixture = createFixture();
        requireService("gulimall-member");

        ExtendedModelMap model = new ExtendedModelMap();
        // 2. 发起调用：直接调用确认页 Controller，让它在模型里放入秒杀商品信息
        seckillPageController.confirmPage(fixture.sessionId(), fixture.skuId(),
                1, loginSession(), model);

        // 3. 断言结果：模型中的商品信息库存为 100、限购为 2
        SeckillSkuInfoVo info = (SeckillSkuInfoVo) model.getAttribute("info");
        assertNotNull(info);
        assertEquals(100, info.getStock());
        assertEquals(2, info.getLimit());
    }

    private MockHttpSession loginSession() {
        // 1. 创建一个模拟会话，并把测试会员信息放进去，供需要登录态的 Controller 调用使用
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthServerConstant.LOGIN_USER, testMember());
        return session;
    }
}
