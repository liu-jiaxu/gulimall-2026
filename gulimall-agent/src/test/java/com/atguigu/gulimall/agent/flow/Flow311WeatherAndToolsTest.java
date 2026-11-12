package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.auth.PrincipalType;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import com.atguigu.gulimall.agent.tool.AdminMemberTools;
import com.atguigu.gulimall.agent.tool.AdminOrderTools;
import com.atguigu.gulimall.agent.tool.AdminWareTools;
import com.atguigu.gulimall.agent.tool.CartTools;
import com.atguigu.gulimall.agent.tool.LogisticsTools;
import com.atguigu.gulimall.agent.tool.MemberTools;
import com.atguigu.gulimall.agent.tool.NullSafeToolExecutor;
import com.atguigu.gulimall.agent.tool.ProductTools;
import com.atguigu.gulimall.agent.tool.SearchTools;
import com.atguigu.gulimall.agent.tool.SeckillTools;
import com.atguigu.gulimall.agent.tool.StockTools;
import com.atguigu.gulimall.agent.tool.ToolBundleFactory;
import com.atguigu.gulimall.agent.tool.UserOrderTools;
import com.atguigu.gulimall.agent.tool.WeatherTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.11：Function Calling 与天气查询。
 */
@Tag("integration")
class Flow311WeatherAndToolsTest extends AgentIntegrationTestSupport {

    @Autowired
    private ToolBundleFactory toolBundleFactory;

    /**
     * 测试天气工具在阿里云接口失败时回退 wttr.in，并处理空城市和未配置 AppCode。
     */
    @Test
    void weatherToolFallsBackToWttrAndHandlesBadInput() {
        List<Object> tools = toolBundleFactory.create(TEST_USER, null);
        WeatherTools weather = tool(tools, WeatherTools.class);

        // 1. 阿里云主接口正常时直接返回真实天气
        String result = weather.queryWeather("北京");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true")
                || result.contains("wttr.in") || result.contains("tempC"), result);

        // 2. 主接口不可用时回退 wttr.in
        WeatherTools fallback = new WeatherTools(
                "http://127.0.0.1:1", "test-code", "https://wttr.in", objectMapper);
        String fallbackResult = fallback.queryWeather("北京");
        assertTrue(fallbackResult.contains("wttr.in") || fallbackResult.contains("tempC"), fallbackResult);

        // 3. 空城市直接返回参数错误
        assertEquals("查询失败：城市不能为空", weather.queryWeather("  "));

        // 4. 未配置 AppCode 时不编造天气
        WeatherTools unconfigured = new WeatherTools(
                "https://jmweather.market.alicloudapi.com", "", "https://wttr.in", objectMapper);
        assertTrue(unconfigured.queryWeather("北京").contains("天气服务未配置"));
    }

    /**
     * 测试天气工具通过 NullSafeToolExecutor 和 DefaultToolExecutor 的真实反射调用。
     */
    @Test
    void weatherToolRunsThroughNullSafeToolExecutor() throws Exception {
        List<Object> tools = toolBundleFactory.create(TEST_USER, null);
        WeatherTools weather = tool(tools, WeatherTools.class);

        // 1. 用真实的工具执行器反射调用 queryWeather
        Method method = WeatherTools.class.getMethod("queryWeather", String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(weather, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("weather-1")
                .name("queryWeather")
                .arguments("{\"city\":\"北京\"}")
                .build();

        // 2. 返回真实天气 JSON
        String result = executor.execute(request, "it-memory");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":true") || result.contains("hourWeathers")
                || result.contains("tempC") || result.contains("wttr.in"), result);
    }

    /**
     * 测试模型通过 Function Calling 触发天气工具，并返回真实天气回答。
     */
    @Test
    void modelInvokesWeatherToolThroughChat() {
        // 1. 让模型回答天气，模型应触发 queryWeather 工具
        Map<String, Object> response = post("/agent/chat",
                Map.of("message", "北京今天天气怎么样？请只告诉我温度和天气状况",
                        "modelId", "deepseek-v4.1-flash"),
                userHeaders());

        // 2. 返回真实回答
        assertEquals(0, code(response));
        Map<String, Object> data = data(response);
        trackConversation(String.valueOf(data.get("conversationId")));
        String content = String.valueOf(data.get("content"));
        assertFalse(content.isBlank());
        assertTrue(content.contains("北京"), content);
        assertTrue(content.contains("℃") || content.contains("°C")
                || content.contains("度") || content.contains("天气"), content);
    }

    /**
     * 测试管理端仓库、商品搜索、SKU/分类/品牌/SPU 和库存工具调用真实微服务。
     */
    @Test
    void adminWarehouseProductAndStockToolsUseRealServices() {
        List<Object> tools = toolBundleFactory.create(TEST_ADMIN, null);

        // 1. 仓库列表
        AdminWareTools warehouseTools = tool(tools, AdminWareTools.class);
        assertToolSuccess(warehouseTools.listWarehouses(), "仓库列表");

        // 2. 商品搜索、SKU 详情、分类、品牌、SPU/SKU
        ProductTools productTools = tool(tools, ProductTools.class);
        String search = productTools.searchProducts("手机", null, null, null, null, 1, 5);
        assertToolSuccess(search, "商品搜索");
        assertToolSuccess(productTools.getSkuDetail(5L), "SKU 详情");
        assertToolSuccess(productTools.listCategories(), "分类树");
        assertToolSuccess(productTools.listBrands(null), "品牌列表");
        assertToolSuccess(productTools.listSpuSkus(6L), "SPU SKU 列表");
        assertToolSuccess(productTools.getSpuInfo(6L), "SPU 详情");

        // 3. 单个和批量库存
        StockTools stockTools = tool(tools, StockTools.class);
        assertToolSuccess(stockTools.getStock(5L), "单 SKU 库存");
        assertToolSuccess(stockTools.getStockBatch("5,6"), "批量 SKU 库存");
    }

    /**
     * 测试管理端订单检索、交易统计和会员概要工具调用真实微服务。
     */
    @Test
    void adminOrderAndMemberToolsUseRealServices() {
        List<Object> tools = toolBundleFactory.create(TEST_ADMIN, null);

        // 1. 管理端订单检索和统计
        AdminOrderTools orderTools = tool(tools, AdminOrderTools.class);
        assertToolSuccess(orderTools.searchOrders(null, null, -1, null, null, 1), "管理端订单检索");
        assertToolSuccess(orderTools.orderStatistics(null, null, null), "管理端订单统计");

        // 2. 任意会员概要
        AdminMemberTools memberTools = tool(tools, AdminMemberTools.class);
        assertToolSuccess(memberTools.getMemberProfile(1L), "会员概要");
    }

    /**
     * 测试 ES 搜索、秒杀首页/场次/商品和百度地图正逆地理编码工具。
     */
    @Test
    void publicSearchSeckillAndLogisticsToolsUseRealServices() {
        List<Object> tools = toolBundleFactory.create(TEST_USER, null);

        // 1. ES 搜索
        SearchTools searchTools = tool(tools, SearchTools.class);
        assertToolSuccess(searchTools.searchByKeyword("手机", null, null, null, null, 1), "ES 搜索");

        // 2. 秒杀首页、场次、场次商品
        SeckillTools seckillTools = tool(tools, SeckillTools.class);
        assertToolSuccess(seckillTools.getSeckillHome(), "秒杀首页");
        assertToolSuccess(seckillTools.listSeckillSessions(), "秒杀场次");
        assertToolSuccess(seckillTools.listSeckillSkus(14L), "秒杀场次商品");

        // 3. 百度地图正地理编码和逆地理编码
        LogisticsTools logisticsTools = tool(tools, LogisticsTools.class);
        assertToolSuccess(logisticsTools.geocode("北京市昌平区回龙观"), "地理编码");
        assertToolSuccess(logisticsTools.reverseGeocode(39.9042, 116.4074), "逆地理编码");
    }

    /**
     * 测试用户端会员资料/地址/优惠券/积分和本人订单工具。
     */
    @Test
    void userMemberAndOrderToolsUseRealServices() {
        // 1. 会员资料、地址、优惠券、积分
        Principal member = new Principal(PrincipalType.USER, 1L, "testuser", "testuser");
        List<Object> memberToolList = toolBundleFactory.create(member, null);
        MemberTools memberTools = tool(memberToolList, MemberTools.class);
        assertToolSuccess(memberTools.getMyProfile(), "会员资料");
        assertToolSuccess(memberTools.listMyAddresses(), "收货地址");
        assertToolSuccess(memberTools.listMyCoupons(), "优惠券");
        assertToolSuccess(memberTools.getMyIntegration(), "积分");

        // 2. 真实订单会员 11 的订单列表和详情
        Principal orderMember = new Principal(PrincipalType.USER, 11L, "member11", "member11");
        List<Object> orderToolList = toolBundleFactory.create(orderMember, null);
        UserOrderTools orderTools = tool(orderToolList, UserOrderTools.class);
        assertToolSuccess(orderTools.listMyOrders(-1, null, 1), "本人订单列表");
        assertToolSuccess(orderTools.getMyOrderDetail("20261021013413116706"), "本人订单详情");
    }

    /**
     * 测试购物车工具读取真实 Redis 购物车数据。
     */
    @Test
    void cartToolsReadRealRedisCart() {
        String cartKey = "gulimall:cart:" + TEST_USER_ID;
        try {
            // 1. 在真实 Redis 里造一条购物车数据
            redis.opsForHash().put(cartKey, "5",
                    "{\"skuId\":5,\"check\":true,\"title\":\"测试商品\",\"price\":99.00,"
                            + "\"count\":2,\"totalPrice\":198.00}");

            List<Object> tools = toolBundleFactory.create(TEST_USER, null);
            CartTools cartTools = tool(tools, CartTools.class);

            // 2. 查询购物车、总件数、单个 SKU
            String cart = cartTools.getMyCart();
            assertTrue(cart.contains("测试商品"), cart);
            assertTrue(cart.contains("198"), cart);
            assertEquals("2", cartTools.getCartCount());
            assertTrue(cartTools.getCartItem(5L).contains("测试商品"));
        } finally {
            redis.delete(cartKey);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T tool(List<Object> tools, Class<T> type) {
        return (T) tools.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具集中没有 " + type.getSimpleName()));
    }

    private void assertToolSuccess(String result, String name) {
        assertNotNull(result, name + " 返回 null");
        assertFalse(result.startsWith("查询失败"), name + " 失败：" + result);
        assertFalse(result.contains("服务返回为空"), name + " 失败：" + result);
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
