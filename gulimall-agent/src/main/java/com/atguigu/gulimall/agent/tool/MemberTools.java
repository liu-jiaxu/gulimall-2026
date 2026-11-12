package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.MemberAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;

/**
 * 用户端会员工具：只能查当前登录会员。
 */
public class MemberTools {

    private final ToolContext context;
    private final MemberAiClient client;
    private final ObjectMapper mapper;

    /**
     * 创建用户端会员工具实例。
     *
     * @param context 当前请求上下文，提供登录会员身份
     * @param client  会员内部接口客户端，用于查询会员资料、地址、优惠券和积分
     * @param mapper  JSON 序列化器，用于把接口响应转换成模型可读的文本
     */
    public MemberTools(ToolContext context, MemberAiClient client, ObjectMapper mapper) {
        this.context = context;
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 查询当前登录用户的会员资料，包含昵称、脱敏手机号、等级、积分和成长值。
     *
     * @return 会员资料的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("查询当前登录用户的会员资料：昵称、手机号（脱敏）、等级、积分、成长值")
    public String getMyProfile() {
        return ToolResult.from(client.profile(context.principal().id()), mapper);
    }

    /**
     * 查询当前登录用户的收货地址列表。
     *
     * @return 收货地址列表的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("查询当前登录用户的收货地址列表")
    public String listMyAddresses() {
        return ToolResult.from(client.addresses(context.principal().id()), mapper);
    }

    /**
     * 查询当前登录用户可用的优惠券。
     *
     * @return 优惠券列表的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("查询当前登录用户可用的优惠券")
    public String listMyCoupons() {
        return ToolResult.from(client.coupons(context.principal().id()), mapper);
    }

    /**
     * 查询当前登录用户的积分和成长值。
     *
     * @return 积分和成长值数据的 JSON 文本；查询失败时返回中文提示
     */
    @Tool("查询当前登录用户的积分和成长值")
    public String getMyIntegration() {
        return ToolResult.from(client.integration(context.principal().id()), mapper);
    }
}
