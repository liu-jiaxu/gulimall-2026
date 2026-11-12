package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.OrderAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * 管理端订单工具：可以查全部订单和交易统计，仍然只读。
 */
public class AdminOrderTools {

    private final OrderAiClient client;
    private final ObjectMapper mapper;

    /**
     * 创建管理端订单工具实例。
     *
     * @param client 订单内部接口客户端，用于检索订单和统计交易数据
     * @param mapper JSON 序列化器，用于把接口响应转换成模型可读的文本
     */
    public AdminOrderTools(OrderAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 按订单号、会员 id、状态和时间范围检索订单，按页返回。
     *
     * @param orderSn   订单号，支持模糊匹配，可为空
     * @param memberId  会员 id，可为空
     * @param status    订单状态：0 待付款、1 待发货、2 待收货、3 已完成、4 已关闭；小于 0 表示不限
     * @param startTime 开始时间，格式 yyyy-MM-dd HH:mm:ss，可为空
     * @param endTime   结束时间，格式 yyyy-MM-dd HH:mm:ss，可为空
     * @param page      页码，从 1 开始，为空或小于 1 时按第一页处理
     * @return 订单分页结果的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("管理端检索订单，可按订单号、会员 id、状态、时间范围筛选")
    public String searchOrders(@P("订单号，支持模糊匹配，可为空") String orderSn,
                               @P("会员 id，可为空") Long memberId,
                               @P("订单状态：0待付款 1待发货 2待收货 3已完成 4已关闭；-1 全部") Integer status,
                               @P("开始时间 yyyy-MM-dd HH:mm:ss，可为空") String startTime,
                               @P("结束时间 yyyy-MM-dd HH:mm:ss，可为空") String endTime,
                               @P("页码，从 1 开始") Integer page) {
        // 1. 状态 -1 视为不限
        Integer queryStatus = status == null || status < 0 ? null : status;

        // 2. 调内部订单检索
        Map<String, Object> response = client.adminSearch(
                orderSn, memberId, queryStatus, startTime, endTime,
                page == null || page < 1 ? 1 : page, 10);
        return ToolResult.from(response, mapper);
    }

    /**
     * 统计指定时间范围内的交易数据，包含交易额、订单数、商品件数、每日趋势、热销商品和状态分布。
     *
     * @param startTime 开始时间，格式 yyyy-MM-dd HH:mm:ss，为空时默认近 30 天
     * @param endTime   结束时间，格式 yyyy-MM-dd HH:mm:ss，可为空
     * @param keyword   商品名称关键字，可为空
     * @return 交易统计结果的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("管理端交易统计：交易额、订单数、商品件数、每日趋势、热销商品、状态分布")
    public String orderStatistics(@P("开始时间 yyyy-MM-dd HH:mm:ss，可为空（默认近30天）") String startTime,
                                  @P("结束时间 yyyy-MM-dd HH:mm:ss，可为空") String endTime,
                                  @P("商品名称关键字，可为空") String keyword) {
        Map<String, Object> response = client.adminStat(startTime, endTime, keyword);
        return ToolResult.from(response, mapper);
    }
}
