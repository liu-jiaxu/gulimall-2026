package com.atguigu.gulimall.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.atguigu.gulimall.api.cart.CartItemVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物车工具。
 *
 * <p>购物车本来就在 Redis 里（gulimall:cart:{memberId}），
 * 直接读同一份数据，不再多绕一次 cart 服务。
 */
@Slf4j
public class CartTools {

    private static final String CART_PREFIX = "gulimall:cart:";

    private final ToolContext context;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper;

    /**
     * 创建购物车工具实例。
     *
     * @param context       当前请求上下文，提供登录会员身份
     * @param redisTemplate Redis 字符串模板，用于直接读取购物车哈希数据
     * @param mapper        JSON 序列化器，用于把购物车数据转换成模型可读的文本
     */
    public CartTools(ToolContext context, StringRedisTemplate redisTemplate, ObjectMapper mapper) {
        this.context = context;
        this.redisTemplate = redisTemplate;
        this.mapper = mapper;
    }

    /**
     * 查询当前登录用户的购物车，返回商品明细、总件数和勾选商品合计金额。
     *
     * @return 购物车内容的 JSON 文本；购物车为空时返回“购物车为空”
     */
    @Tool("查询当前登录用户的购物车：商品、单价、数量、小计、合计")
    public String getMyCart() {
        // 1. 登录用户的购物车 key 就是 gulimall:cart:{memberId}
        Map<Object, Object> entries = redisTemplate.opsForHash()
                .entries(CART_PREFIX + context.principal().id());
        if (entries == null || entries.isEmpty()) {
            return "购物车为空";
        }

        // 2. 逐条解析成精简结构，不把实体的全部 getter 都返回
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int countNum = 0;
        for (Object value : entries.values()) {
            try {
                CartItemVo item = JSON.parseObject(String.valueOf(value), CartItemVo.class);
                if (item == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("skuId", item.getSkuId());
                row.put("title", item.getTitle());
                row.put("price", item.getPrice());
                row.put("count", item.getCount());
                row.put("checked", item.getCheck());
                row.put("totalPrice", item.getTotalPrice());
                items.add(row);
                if (item.getCount() != null) {
                    countNum += item.getCount();
                }
                if (Boolean.TRUE.equals(item.getCheck()) && item.getTotalPrice() != null) {
                    total = total.add(item.getTotalPrice());
                }
            } catch (Exception ex) {
                log.warn("购物车条目解析失败：{}", ex.getMessage());
            }
        }

        // 3. 汇总返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("countNum", countNum);
        result.put("checkedTotalAmount", total);
        return ToolResult.json(result, mapper);
    }

    /**
     * 统计当前登录用户购物车中所有商品的件数总和。
     *
     * @return 商品总件数的字符串；购物车为空时返回“0”
     */
    @Tool("查询当前登录用户购物车里的商品总件数")
    public String getCartCount() {
        Map<Object, Object> entries = redisTemplate.opsForHash()
                .entries(CART_PREFIX + context.principal().id());
        if (entries == null || entries.isEmpty()) {
            return "0";
        }
        int count = 0;
        for (Object value : entries.values()) {
            try {
                CartItemVo item = JSON.parseObject(String.valueOf(value), CartItemVo.class);
                if (item != null && item.getCount() != null) {
                    count += item.getCount();
                }
            } catch (Exception ignored) {
                // 单条坏数据跳过
            }
        }
        return String.valueOf(count);
    }

    /**
     * 查询当前登录用户购物车中指定 SKU 的数量和单价。
     *
     * @param skuId SKU id
     * @return 该 SKU 在购物车中的数据文本；skuId 为空或购物车中没有该商品时返回中文提示
     */
    @Tool("查询当前登录用户购物车中某个 SKU 的数量和单价")
    public String getCartItem(@P("SKU id") Long skuId) {
        if (skuId == null) {
            return "查询失败：skuId 不能为空";
        }
        Object value = redisTemplate.opsForHash()
                .get(CART_PREFIX + context.principal().id(), String.valueOf(skuId));
        if (value == null) {
            return "购物车里没有这个商品";
        }
        return ToolResult.raw(String.valueOf(value));
    }
}
