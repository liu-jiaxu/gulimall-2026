package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.feign.*;
import com.atguigu.gulimall.agent.image.ImageTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按角色组装工具集。
 *
 * <p>用户端和管理端能用的工具必须不同：
 * 用户端拿不到“查全部订单/查任意会员/查仓库”这类管理工具，
 * 即使模型被提示词诱导也调用不到，这是权限的最后一道闸。
 */
@Service
public class ToolBundleFactory {

    private final ProductAiClient productAiClient;
    private final SearchAiClient searchAiClient;
    private final OrderAiClient orderAiClient;
    private final MemberAiClient memberAiClient;
    private final WareAiClient wareAiClient;
    private final SeckillAiClient seckillAiClient;
    private final ThirdPartyAiClient thirdPartyAiClient;
    private final ImageTaskService imageTaskService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String weatherBaseUrl;
    private final String weatherAppCode;
    private final String weatherFallbackBaseUrl;

    /**
     * 创建工具集工厂，注入各业务内部接口客户端和天气配置。
     *
     * @param productAiClient        商品内部接口客户端
     * @param searchAiClient         搜索内部接口客户端
     * @param orderAiClient          订单内部接口客户端
     * @param memberAiClient         会员内部接口客户端
     * @param wareAiClient           仓库内部接口客户端
     * @param seckillAiClient        秒杀内部接口客户端
     * @param thirdPartyAiClient     第三方内部接口客户端，提供地址和天气能力
     * @param imageTaskService       后台图片任务服务
     * @param redisTemplate          Redis 字符串模板，用于读取购物车数据
     * @param objectMapper           JSON 序列化器
     * @param weatherBaseUrl         阿里云天气接口基础地址
     * @param weatherAppCode         阿里云天气接口的 AppCode
     * @param weatherFallbackBaseUrl 天气兜底接口基础地址
     */
    public ToolBundleFactory(ProductAiClient productAiClient,
                             SearchAiClient searchAiClient,
                             OrderAiClient orderAiClient,
                             MemberAiClient memberAiClient,
                             WareAiClient wareAiClient,
                             SeckillAiClient seckillAiClient,
                             ThirdPartyAiClient thirdPartyAiClient,
                             ImageTaskService imageTaskService,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             @Value("${WEATHER_BASE_URL:https://jmweather.market.alicloudapi.com}") String weatherBaseUrl,
                             @Value("${WEATHER_APP_CODE:}") String weatherAppCode,
                             @Value("${WEATHER_FALLBACK_BASE_URL:https://wttr.in}") String weatherFallbackBaseUrl) {
        this.productAiClient = productAiClient;
        this.searchAiClient = searchAiClient;
        this.orderAiClient = orderAiClient;
        this.memberAiClient = memberAiClient;
        this.wareAiClient = wareAiClient;
        this.seckillAiClient = seckillAiClient;
        this.thirdPartyAiClient = thirdPartyAiClient;
        this.imageTaskService = imageTaskService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.weatherBaseUrl = weatherBaseUrl;
        this.weatherAppCode = weatherAppCode;
        this.weatherFallbackBaseUrl = weatherFallbackBaseUrl;
    }

    /**
     * 根据当前用户角色创建本次请求可用的工具列表。
     *
     * @param principal      当前用户身份，用于区分管理端和用户端
     * @param conversationId 当前会话 id，供图片等异步任务回写结果
     * @return 本次请求可用的工具实例列表
     */
    public List<Object> create(Principal principal, String conversationId) {
        // 1. 每次请求新建工具实例，把当前身份固定在构造参数里，
        //    流式回调线程切换时也不会拿错用户
        ToolContext context = new ToolContext(principal, conversationId);
        List<Object> tools = new ArrayList<>();

        // 2. 两端都能用的只读工具
        tools.add(new ProductTools(context, productAiClient, objectMapper));
        tools.add(new SearchTools(searchAiClient, objectMapper));
        tools.add(new SeckillTools(seckillAiClient, objectMapper));
        tools.add(new StockTools(wareAiClient, objectMapper));
        tools.add(new LogisticsTools(thirdPartyAiClient, objectMapper));
        tools.add(new WeatherTools(weatherBaseUrl, weatherAppCode, weatherFallbackBaseUrl, objectMapper));
        tools.add(new ImageTools(context, imageTaskService));

        // 3. 用户端专有：本人购物车/订单/会员信息
        if (!principal.isAdmin()) {
            tools.add(new CartTools(context, redisTemplate, objectMapper));
            tools.add(new UserOrderTools(context, orderAiClient, objectMapper));
            tools.add(new MemberTools(context, memberAiClient, objectMapper));
        }

        // 4. 管理端专有：全部订单、任意会员概要、仓库
        if (principal.isAdmin()) {
            tools.add(new AdminOrderTools(orderAiClient, objectMapper));
            tools.add(new AdminMemberTools(memberAiClient, objectMapper));
            tools.add(new AdminWareTools(wareAiClient, objectMapper));
        }

        return tools;
    }
}
