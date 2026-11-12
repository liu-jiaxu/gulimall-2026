package com.atguigu.gulimall.agent.image;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.dto.ChatMessageDto;
import com.atguigu.gulimall.agent.chat.memory.ConversationService;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * 图片生成任务：提交后立即返回 taskId，真正的生成放到独立线程池。
 *
 * <p>为什么要异步：
 * gpt-image-1-mini 一次生成经常 1～3 分钟，同步等待会把 HTTP 连接、SSE 和前端都拖住；
 * 改成“先返回正在生成，完成后把图片消息写回会话”，用户可以先继续聊天。
 *
 * <p>状态存 Redis 1 小时，会话里的任务列表也存 1 小时；
 * 图片消息本身写进正常会话，3 天内都能看到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageTaskService {

    private static final String TASK_KEY = "gulimall:ai:imgtask:";
    private static final String CONV_TASK_KEY = "gulimall:ai:imgtask:conv:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ImageGenerationService generationService;
    private final ConversationService conversationService;

    /**
     * 专用线程池：图片生成很慢，不能占用 Tomcat 请求线程。
     */
    @Resource(name = "imageTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    /**
     * 提交任务，立即返回 taskId。
     *
     * @param principal      当前登录用户
     * @param conversationId 关联的会话 id，可为空
     * @param prompt         图片生成描述
     * @param size           图片尺寸参数
     * @param quality        图片质量参数
     * @return 新创建的图片任务 id
     */
    public String submit(Principal principal, String conversationId,
                         String prompt, String size, String quality) {
        // 1. 参数校验；带会话时要先确认会话属于当前用户
        if (!StringUtils.hasText(prompt)) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "请描述要生成的图片内容");
        }
        // 1.1 只允许商城相关图片，避免 AI 被当成通用画图工具
        if (!ImagePromptGuard.isProjectRelated(prompt)) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID,
                    "只能生成与谷粒商城相关的图片（商品图、促销海报、活动横幅、优惠券/秒杀宣传图等），这个需求不在范围内");
        }
        if (StringUtils.hasText(conversationId)) {
            conversationService.getOrCreate(principal, conversationId, null);
        }

        // 2. 先落任务记录，前端马上能查到 PENDING
        String taskId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", taskId);
        data.put("ownerType", principal.type().name());
        data.put("ownerId", String.valueOf(principal.id()));
        data.put("conversationId", conversationId == null ? "" : conversationId);
        data.put("prompt", prompt.trim());
        data.put("size", size == null ? "" : size);
        data.put("quality", quality == null ? "" : quality);
        data.put("status", "PENDING");
        data.put("createdAt", String.valueOf(now));
        data.put("updatedAt", String.valueOf(now));
        saveTask(taskId, data);

        // 3. 关联到会话，前端按 conversationId 轮询
        if (StringUtils.hasText(conversationId)) {
            String key = CONV_TASK_KEY + conversationId;
            redisTemplate.opsForList().rightPush(key, taskId);
            redisTemplate.expire(key, TTL);
        }

        // 4. 提交到图片线程池；队列满时立即告诉用户，不让他干等
        try {
            executor.execute(() -> runTask(taskId, principal, conversationId, prompt, size, quality));
        } catch (TaskRejectedException ex) {
            updateStatus(taskId, "FAILED", null, "图片生成任务繁忙，请稍后再试");
            throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED, "图片生成任务繁忙，请稍后再试");
        }
        return taskId;
    }

    /**
     * 查询单个任务（只能查自己的）。
     *
     * @param principal 当前登录用户
     * @param taskId    图片任务 id
     * @return 任务的展示字段
     */
    public Map<String, Object> get(Principal principal, String taskId) {
        Map<Object, Object> data = redisTemplate.opsForHash().entries(TASK_KEY + taskId);
        if (data.isEmpty()) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "图片任务不存在或已过期");
        }
        checkOwner(principal, data);
        return toView(data);
    }

    /**
     * 查某个会话下的任务，前端轮询用。
     *
     * @param principal      当前登录用户
     * @param conversationId 会话 id
     * @return 当前用户在该会话下最近的任务列表
     */
    public List<Map<String, Object>> list(Principal principal, String conversationId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!StringUtils.hasText(conversationId)) {
            return result;
        }
        // 1. 只取最近 20 个，避免列表无限增长
        List<String> ids = redisTemplate.opsForList().range(CONV_TASK_KEY + conversationId, -20, -1);
        if (ids == null) {
            return result;
        }
        for (String taskId : ids) {
            Map<Object, Object> data = redisTemplate.opsForHash().entries(TASK_KEY + taskId);
            if (data.isEmpty()) {
                continue;
            }
            // 2. 只返回当前用户自己的任务
            if (!owned(principal, data)) {
                continue;
            }
            result.add(toView(data));
        }
        return result;
    }

    /**
     * 在线程池中执行图片生成，并同步更新任务状态与会话消息。
     *
     * @param taskId         图片任务 id
     * @param principal      当前登录用户
     * @param conversationId 关联的会话 id
     * @param prompt         图片生成描述
     * @param size           图片尺寸参数
     * @param quality        图片质量参数
     */
    private void runTask(String taskId, Principal principal, String conversationId,
                         String prompt, String size, String quality) {
        // 1. 标记开始
        updateStatus(taskId, "RUNNING", null, null);
        try {
            // 2. 同步调用图片模型，这里已经在独立线程里，不阻塞聊天
            String url = generationService.generate(principal, prompt, size, quality);
            updateStatus(taskId, "DONE", url, null);

            // 3. 生成完成：写一条图片消息到会话，刷新/轮询都能看到
            appendImageMessage(principal, conversationId, prompt, url);
        } catch (Exception ex) {
            // 4. 失败也要留下记录，前端能提示用户
            log.error("图片生成任务失败 taskId={}", taskId, ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            updateStatus(taskId, "FAILED", null, message);
            appendFailureMessage(principal, conversationId, message);
        }
    }

    /**
     * 将生成成功的图片以 Markdown 图片消息追加到会话。
     *
     * @param principal      当前登录用户
     * @param conversationId 关联的会话 id
     * @param prompt         图片生成描述，用作图片替代文本
     * @param url            生成图片的访问地址
     */
    private void appendImageMessage(Principal principal, String conversationId, String prompt, String url) {
        String alt = prompt == null ? "生成图片" : prompt.replaceAll("[\\[\\]\\r\\n]", " ");
        if (alt.length() > 60) {
            alt = alt.substring(0, 60);
        }
        appendMessage(principal, conversationId, "图片已生成：\n\n![" + alt + "](" + url + ")");
    }

    /**
     * 将生成失败原因作为普通消息追加到会话。
     *
     * @param principal      当前登录用户
     * @param conversationId 关联的会话 id
     * @param error          失败原因
     */
    private void appendFailureMessage(Principal principal, String conversationId, String error) {
        appendMessage(principal, conversationId, "图片生成失败：" + error);
    }

    /**
     * 以助手身份向会话追加一条普通文本消息。
     *
     * @param principal      当前登录用户
     * @param conversationId 会话 id
     * @param content        消息内容
     */
    private void appendMessage(Principal principal, String conversationId, String content) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        try {
            // 1. 会话可能已被用户删除，删了就只更新任务状态，不再写消息
            conversationService.getOrCreate(principal, conversationId, null);

            // 2. 作为一条普通 AI 消息写入，前端/历史记录都能看到
            ChatMessageDto message = new ChatMessageDto();
            message.setId(UUID.randomUUID().toString().replace("-", ""));
            message.setRole("assistant");
            message.setContent(content);
            message.setCreatedAt(System.currentTimeMillis());
            message.setStatus("ok");
            conversationService.append(principal, conversationId, message);
        } catch (Exception ex) {
            log.warn("图片任务消息写入会话失败 conversationId={}：{}", conversationId, ex.getMessage());
        }
    }

    /**
     * 将任务数据写入 Redis 哈希并设置过期时间。
     *
     * @param taskId 图片任务 id
     * @param data   任务的字段集合
     */
    private void saveTask(String taskId, Map<String, String> data) {
        String key = TASK_KEY + taskId;
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, TTL);
    }

    /**
     * 更新任务状态，按需写入结果地址或错误信息。
     *
     * @param taskId 图片任务 id
     * @param status 新状态，如 RUNNING、DONE、FAILED
     * @param url    生成成功时的图片地址，可为空
     * @param error  生成失败时的错误信息，可为空
     */
    private void updateStatus(String taskId, String status, String url, String error) {
        String key = TASK_KEY + taskId;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        if (url != null) {
            data.put("url", url);
        }
        if (error != null) {
            data.put("error", error);
        }
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, TTL);
    }

    /**
     * 判断任务是否归属于当前用户。
     *
     * @param principal 当前登录用户
     * @param data      任务的字段集合
     * @return 归属当前用户时为 true，否则为 false
     */
    private boolean owned(Principal principal, Map<Object, Object> data) {
        return principal.type().name().equals(String.valueOf(data.get("ownerType")))
                && String.valueOf(principal.id()).equals(String.valueOf(data.get("ownerId")));
    }

    /**
     * 校验任务归属，非本人任务抛出未登录异常。
     *
     * @param principal 当前登录用户
     * @param data      任务的字段集合
     */
    private void checkOwner(Principal principal, Map<Object, Object> data) {
        if (!owned(principal, data)) {
            throw new AgentException(AgentErrorCode.NOT_LOGIN, "无权查看该图片任务");
        }
    }

    /**
     * 将 Redis 中的任务字段转换为前端展示用的结构。
     *
     * @param data 任务的字段集合
     * @return 只包含展示字段的任务视图
     */
    private Map<String, Object> toView(Map<Object, Object> data) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", data.get("id"));
        view.put("status", data.get("status"));
        view.put("url", data.get("url"));
        view.put("error", data.get("error"));
        view.put("prompt", data.get("prompt"));
        view.put("createdAt", data.get("createdAt"));
        view.put("updatedAt", data.get("updatedAt"));
        return view;
    }
}
