package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.WareAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;

/**
 * 管理端仓库工具。
 */
public class AdminWareTools {

    private final WareAiClient client;
    private final ObjectMapper mapper;

    /**
     * 创建管理端仓库工具实例。
     *
     * @param client 仓库内部接口客户端，用于查询仓库列表
     * @param mapper JSON 序列化器，用于把接口响应转换成模型可读的文本
     */
    public AdminWareTools(WareAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 查询所有仓库列表，返回仓库 id、名称、地址和区域编码。
     *
     * @return 仓库列表的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("查询所有仓库列表：仓库 id、名称、地址、区域编码")
    public String listWarehouses() {
        return ToolResult.from(client.warehouses(), mapper);
    }
}
