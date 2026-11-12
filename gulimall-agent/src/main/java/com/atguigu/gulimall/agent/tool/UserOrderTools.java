package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.OrderAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * 用户端订单工具：只能查当前登录会员自己的订单。
 */
public class UserOrderTools {

    private final ToolContext context;
    private final OrderAiClient client;
    private final ObjectMapper mapper;

    /**
     * 构造用户订单查询工具实例。
     *
     * @param context 工具上下文，提供当前登录会员的身份信息
     * @param client  订单模块的 Feign 只读客户端
     * @param mapper  JSON 序列化与反序列化组件
     */
    public UserOrderTools(ToolContext context, OrderAiClient client, ObjectMapper mapper) {
        this.context = context;
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 查询当前登录用户的订单列表，可按状态和订单号或商品名关键字筛选。
     *
     * @param status  订单状态：0 待付款、1 待发货、2 待收货、3 已完成、4 已关闭；-1 表示全部
     * @param keyword 订单号或商品名关键字，可为空
     * @param page    页码，从 1 开始
     * @return 当前用户订单列表的 JSON 字符串
     */
    @Tool("查询当前登录用户的订单列表，可按状态和订单号/商品名关键字筛选")
    public String listMyOrders(@P("订单状态：0待付款 1待发货 2待收货 3已完成 4已关闭；-1 表示全部") Integer status,
                               @P("订单号或商品名关键字，可为空") String keyword,
                               @P("页码，从 1 开始") Integer page) {
        // 1. 把 -1 转成“不限状态”
        Integer queryStatus = status == null || status < 0 ? null : status;

        // 2. memberId 固定取当前登录用户，模型无法伪造别人的 id
        Map<String, Object> response = client.memberOrders(
                context.principal().id(),
                queryStatus,
                keyword,
                page == null || page < 1 ? 1 : page,
                10
        );
        return ToolResult.from(response, mapper);
    }

    /**
     * 查询当前登录用户的指定订单详情，包括商品、金额、收货信息和物流。
     *
     * @param orderSn 订单号
     * @return 订单详情的 JSON 字符串；订单号为空时返回失败提示
     */
    @Tool("查询当前登录用户的某个订单详情，包括商品、金额、收货信息、物流")
    public String getMyOrderDetail(@P("订单号 orderSn") String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            return "查询失败：订单号不能为空";
        }

        // 1. 带上 memberId，内部接口会校验订单归属
        Map<String, Object> response = client.detail(orderSn.trim(), context.principal().id());
        return ToolResult.from(response, mapper);
    }
}
