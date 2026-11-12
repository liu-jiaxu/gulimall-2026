package com.atguigu.gulimall.agent.chat.controller;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.memory.ConversationService;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.common.utils.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 多会话历史：列表、消息、会话内搜索、3 天内全局搜索、删除。
 */
@RestController
@RequestMapping("/agent/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final AiProperties properties;

    /**
     * 查询当前身份最近 3 天的会话列表，每项只包含摘要，不包含全部消息。
     *
     * @param principal 当前登录身份
     * @return 统一响应，list 里是会话摘要集合
     */
    @GetMapping
    public R list(Principal principal) {
        // 1. 按当前身份返回最近 3 天会话；每个会话只返回摘要，不返回全部消息
        return R.ok().put("list", conversationService.list(principal));
    }

    /**
     * 新建一个会话，可指定默认模型，未指定时使用服务端默认模型。
     *
     * @param principal 当前登录身份
     * @param body      请求体，modelId 字段可选，用于指定新会话的默认模型
     * @return 统一响应，conversationId 为新会话 id
     */
    @PostMapping
    public R create(Principal principal, @RequestBody(required = false) Map<String, Object> body) {
        // 1. 新会话可以指定默认模型；不传就由服务端默认模型兜底
        String modelId = body == null ? null : String.valueOf(body.getOrDefault("modelId", ""));
        String conversationId = conversationService.create(principal, modelId);
        return R.ok().put("conversationId", conversationId);
    }

    /**
     * 分页查询指定会话的消息，before 用于加载更早的历史消息。
     *
     * @param principal 当前登录身份
     * @param id        会话 id
     * @param limit     每页条数，为空或小于 1 时使用配置的每页条数
     * @param before    只返回该消息 id 之前的消息，用于向前翻页
     * @return 统一响应，list 里是消息集合
     */
    @GetMapping("/{id}/messages")
    public R messages(Principal principal,
                      @PathVariable("id") String id,
                      @RequestParam(value = "limit", required = false) Integer limit,
                      @RequestParam(value = "before", required = false) String before) {
        // 1. 请求没传 limit 时使用会话配置的每页条数
        int pageSize = limit == null || limit < 1
                ? properties.getConversation().getUiPageSize()
                : limit;

        // 2. before 用于“加载更早”：只取这条消息 id 之前的内容
        return R.ok().put("list", conversationService.messages(principal, id, pageSize, before));
    }

    /**
     * 在当前会话内按关键词搜索消息。
     *
     * @param principal 当前登录身份
     * @param id        会话 id
     * @param keyword   搜索关键词
     * @param limit     最多返回条数
     * @return 统一响应，list 里是命中的消息
     */
    @GetMapping("/{id}/messages/search")
    public R searchCurrent(Principal principal,
                           @PathVariable("id") String id,
                           @RequestParam("keyword") String keyword,
                           @RequestParam(value = "limit", defaultValue = "50") int limit) {
        // 1. 只在当前会话里搜，适合聊天窗的“搜索当前对话”
        return R.ok().put("list", conversationService.search(principal, id, keyword, limit));
    }

    /**
     * 在当前身份 3 天内的所有会话中按关键词搜索，返回命中的消息及所属会话。
     *
     * @param principal 当前登录身份
     * @param keyword   搜索关键词
     * @param limit     最多返回条数
     * @return 统一响应，list 里是搜索结果
     */
    @GetMapping("/search")
    public R searchAll(Principal principal,
                       @RequestParam("keyword") String keyword,
                       @RequestParam(value = "limit", defaultValue = "50") int limit) {
        // 1. 遍历当前身份 3 天内的所有会话，返回命中的消息和所属会话
        return R.ok().put("list", conversationService.searchAll(principal, keyword, limit));
    }

    /**
     * 删除指定会话，删除前会校验会话是否属于当前身份。
     *
     * @param principal 当前登录身份
     * @param id        会话 id
     * @return 统一响应，删除成功不返回业务数据
     */
    @DeleteMapping("/{id}")
    public R delete(Principal principal, @PathVariable("id") String id) {
        // 1. 先校验归属再删，防止通过 id 删别人的会话
        conversationService.delete(principal, id);
        return R.ok();
    }
}
