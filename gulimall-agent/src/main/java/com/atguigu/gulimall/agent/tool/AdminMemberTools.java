package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.MemberAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * 管理端会员工具：按会员 id 查概要，手机号已由内部接口脱敏。
 */
public class AdminMemberTools {

    private final MemberAiClient client;
    private final ObjectMapper mapper;

    /**
     * 创建管理端会员工具实例。
     *
     * @param client 会员内部接口客户端，用于按会员 id 查询会员概要
     * @param mapper JSON 序列化器，用于把接口响应转换成模型可读的文本
     */
    public AdminMemberTools(MemberAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 按会员 id 查询会员概要，返回昵称、脱敏手机号、等级、积分和成长值。
     *
     * @param memberId 会员 id
     * @return 会员概要的 JSON 文本；memberId 为空或查询失败时返回中文提示
     */
    @Tool("按会员 id 查询会员概要：昵称、手机号（脱敏）、等级、积分、成长值")
    public String getMemberProfile(@P("会员 id") Long memberId) {
        if (memberId == null) {
            return "查询失败：memberId 不能为空";
        }
        Map<String, Object> response = client.profile(memberId);
        return ToolResult.from(response, mapper);
    }
}
