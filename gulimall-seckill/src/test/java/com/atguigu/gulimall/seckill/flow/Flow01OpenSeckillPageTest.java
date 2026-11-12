package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 01：打开秒杀页。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow01OpenSeckillPageTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：验证秒杀首页、场次列表和商品列表都能展示当前进行中的测试场次与测试 SKU，并确认 /seckill/home 返回正确场次及进行中状态。
     */
    @Test
    void pageShowsActiveSessionAndSku() {
        // 1. 准备测试数据：创建一个进行中的测试场次、测试 SKU 关联和测试会员收货地址
        Fixture fixture = createFixture();

        // 2. 发起调用并断言：请求秒杀页 HTML，确认页面渲染出了测试商品名和测试场次前缀
        String html = getHtml("/seckill.html", null);
        assertNotNull(html);
        assertTrue(html.contains("IT 秒杀商品"), "页面没有渲染测试商品");
        assertTrue(html.contains("IT-SECKILL-"), "页面没有渲染测试场次");

        // 3. 查询并断言：请求场次列表接口，确认当前创建的进行中场次出现在列表中
        Map<String, Object> sessionsResponse = getJson("/seckill/sessions", null);
        assertOk(sessionsResponse);
        List<Map<String, Object>> sessions = listValue(sessionsResponse, "sessions");
        assertTrue(sessions.stream().anyMatch(item ->
                        ((Number) item.get("id")).longValue() == fixture.sessionId()),
                "场次列表没有测试场次");

        // 4. 查询并断言：请求该场次的商品列表，确认只返回当前测试用的那一个 SKU
        Map<String, Object> skusResponse = getJson(
                "/seckill/skus?sessionId=" + fixture.sessionId(), null);
        assertOk(skusResponse);
        List<Map<String, Object>> skus = listValue(skusResponse, "skus");
        assertEquals(1, skus.size());
        assertEquals(TEST_SKU_ID, ((Number) skus.get(0).get("skuId")).longValue());

        // 5. 查询并断言：请求首页接口，确认返回的是进行中场次且状态为 1（进行中）
        Map<String, Object> homeResponse = getJson("/seckill/home", null);
        assertOk(homeResponse);
        Map<String, Object> home = mapValue(homeResponse, "seckillHome");
        assertEquals(fixture.sessionId(), ((Number) home.get("sessionId")).longValue());
        assertEquals(1, ((Number) home.get("state")).intValue());
    }

    /**
     * 测试内容：当同时存在进行中和未来场次时，首页接口应优先返回进行中的场次而非未来场次。
     */
    @Test
    void homePrefersRunningSession() {
        // 1. 准备测试数据：先创建一个进行中的场次，再额外造一个 30 分钟后开始的未来场次
        Fixture running = createFixture();
        Fixture future = createAdditionalSession(
                new Date(System.currentTimeMillis() + 30 * 60 * 1000L),
                new Date(System.currentTimeMillis() + 90 * 60 * 1000L),
                0, 100, 2);

        // 2. 发起调用：请求首页接口，取回当前推荐场次
        Map<String, Object> response = getJson("/seckill/home", null);
        assertOk(response);
        Map<String, Object> home = mapValue(response, "seckillHome");
        // 3. 断言结果：首页应优先返回进行中的场次，而不是未来场次
        assertEquals(running.sessionId(), ((Number) home.get("sessionId")).longValue(),
                "有进行中场次时应优先展示进行中");
        assertTrue(home.get("sessionId") != null);
    }

    /**
     * 测试内容：已结束的秒杀场次不应出现在 /seckill/sessions 返回的可展示场次列表中。
     */
    @Test
    void expiredSessionIsNotListed() {
        // 1. 准备测试数据：创建一个两小时前开始、一小时前结束的已过期场次
        Fixture expired = createFixture(
                new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                1, 10, 2);

        // 2. 发起调用：请求场次列表接口，确认调用成功并取出列表
        Map<String, Object> response = getJson("/seckill/sessions", null);
        assertOk(response);
        List<Map<String, Object>> sessions = listValue(response, "sessions");
        // 3. 断言结果：已结束场次不能出现在可展示列表里
        assertTrue(sessions.stream().noneMatch(item ->
                        ((Number) item.get("id")).longValue() == expired.sessionId()),
                "已结束场次不应出现在可展示列表");
    }
}
