package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.WareAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 库存工具：用户和管理端都可以查。
 */
public class StockTools {

    private final WareAiClient client;
    private final ObjectMapper mapper;

    /**
     * 构造库存查询工具实例。
     *
     * @param client 库存模块的 Feign 只读客户端
     * @param mapper JSON 序列化与反序列化组件
     */
    public StockTools(WareAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 查询指定 SKU 在所有仓库的可用库存总数。
     *
     * @param skuId SKU id
     * @return SKU 库存信息的 JSON 字符串；skuId 为空时返回失败提示
     */
    @Tool("查询某个 SKU 在所有仓库的可用库存总数")
    public String getStock(@P("SKU id") Long skuId) {
        if (skuId == null) {
            return "查询失败：skuId 不能为空";
        }
        return ToolResult.from(client.stock(skuId), mapper);
    }

    /**
     * 批量查询多个 SKU 的库存。
     *
     * @param skuIds SKU id 列表，多个 id 用逗号分隔，例如 1,2,3
     * @return 批量库存信息的 JSON 字符串；skuIds 为空时返回失败提示
     */
    @Tool("批量查询多个 SKU 的库存，skuIds 用逗号分隔")
    public String getStockBatch(@P("SKU id 列表，逗号分隔，例如 1,2,3") String skuIds) {
        if (skuIds == null || skuIds.isBlank()) {
            return "查询失败：skuIds 不能为空";
        }
        return ToolResult.from(client.stockBatch(skuIds), mapper);
    }
}
