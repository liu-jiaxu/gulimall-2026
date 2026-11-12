package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.SeckillAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 秒杀工具：场次、场次商品、首页秒杀区。
 */
public class SeckillTools {

    private final SeckillAiClient client;
    private final ObjectMapper mapper;

    /**
     * 构造秒杀查询工具实例。
     *
     * @param client 秒杀模块的 Feign 客户端
     * @param mapper JSON 序列化与反序列化组件
     */
    public SeckillTools(SeckillAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 查询秒杀首页数据，包括当前场次、即将开始的场次和秒杀商品。
     *
     * @return 秒杀首页数据的 JSON 字符串
     */
    @Tool("查询秒杀首页数据：当前场次、即将开始的场次和秒杀商品")
    public String getSeckillHome() {
        return ToolResult.from(client.home(), mapper);
    }

    /**
     * 查询今天的所有秒杀场次。
     *
     * @return 今日秒杀场次列表的 JSON 字符串
     */
    @Tool("查询今天的所有秒杀场次")
    public String listSeckillSessions() {
        return ToolResult.from(client.sessions(), mapper);
    }

    /**
     * 查询指定秒杀场次下的商品。
     *
     * @param sessionId 秒杀场次 id
     * @return 场次商品列表的 JSON 字符串；sessionId 为空时返回失败提示
     */
    @Tool("查询某个秒杀场次下的商品")
    public String listSeckillSkus(@P("秒杀场次 id") Long sessionId) {
        if (sessionId == null) {
            return "查询失败：sessionId 不能为空";
        }
        return ToolResult.from(client.skus(sessionId), mapper);
    }
}
