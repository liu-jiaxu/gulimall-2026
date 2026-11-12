package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.auth.Principal;

/**
 * 工具执行上下文：每个请求一份，工具从里面拿当前用户，避免查别人的数据。
 *
 * @param principal      当前登录身份
 * @param conversationId 当前会话 id
 */
public record ToolContext(Principal principal, String conversationId) {
}
