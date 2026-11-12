package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.7：图片异步生成。
 */
@Tag("integration")
class Flow37ImageTaskTest extends AgentIntegrationTestSupport {

    /**
     * 测试非商城相关的图片生成提示词被拒绝并返回 15008。
     */
    @Test
    void irrelevantPromptIsRejected() {
        // 1. 非商城相关提示词
        Map<String, Object> response = post("/agent/images/generate",
                Map.of("prompt", "画一只猫"), userHeaders());

        // 2. ImagePromptGuard 拒绝，返回 15008
        assertEquals(15008, code(response));
    }

    /**
     * 测试图片生成任务从 PENDING/RUNNING 到 DONE，任务列表可查，并把图片消息写回会话。
     */
    @Test
    void generationTaskRunsToDoneAndWritesConversationMessage() throws Exception {
        // 1. 先建会话，生成完成后会写回这个会话
        String conversationId = conversationService.create(TEST_USER, "deepseek-v4.1-flash");
        trackConversation(conversationId);

        // 2. 提交真实图片生成任务
        Map<String, Object> submitResponse = post("/agent/images/generate",
                Map.of("prompt", "谷粒商城促销海报，红色背景，商品主图，简洁电商风格",
                        "conversationId", conversationId,
                        "size", "1024x1024",
                        "quality", "low"),
                userHeaders());
        assertEquals(0, code(submitResponse));
        String taskId = String.valueOf(data(submitResponse).get("taskId"));
        trackTask(taskId);

        // 3. 轮询任务状态，最多等 6 分钟
        Map<String, Object> task = null;
        String status = null;
        long deadline = System.nanoTime() + Duration.ofMinutes(6).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> taskResponse = get("/agent/images/tasks/" + taskId, userHeaders());
            assertEquals(0, code(taskResponse));
            task = data(taskResponse);
            status = String.valueOf(task.get("status"));
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(3000);
        }

        // 4. 任务必须完成并返回 MinIO 地址
        assertEquals("DONE", status, "图片任务没有完成或失败：" + task);
        String imageUrl = String.valueOf(task.get("url"));
        assertTrue(imageUrl.startsWith("/oss/gulimall/ai/generated/USER/" + TEST_USER_ID + "/"),
                imageUrl);

        // 5. 会话任务列表包含该任务
        List<Map<String, Object>> tasks = list(get(
                "/agent/images/tasks?conversationId=" + conversationId, userHeaders()));
        assertTrue(tasks.stream().anyMatch(item -> taskId.equals(item.get("id"))));

        // 6. 生成结果作为 AI 消息写回会话
        List<Map<String, Object>> messages = list(get(
                "/agent/conversations/" + conversationId + "/messages?limit=20", userHeaders()));
        assertTrue(messages.stream().anyMatch(item ->
                String.valueOf(item.get("content")).contains("图片已生成")));
    }

    /**
     * 测试其他身份不能查看用户图片任务，任务列表也会过滤非本人任务。
     */
    @Test
    void otherPrincipalCannotReadTask() {
        // 1. 在真实 Redis 里直接写一个属于测试用户的任务记录，避免再触发一次图片生成
        String conversationId = conversationService.create(TEST_USER, "deepseek-v4.1-flash");
        trackConversation(conversationId);
        String taskId = java.util.UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        redis.opsForHash().putAll("gulimall:ai:imgtask:" + taskId, Map.of(
                "id", taskId,
                "ownerType", "USER",
                "ownerId", String.valueOf(TEST_USER_ID),
                "conversationId", conversationId,
                "prompt", "谷粒商城优惠券宣传图",
                "status", "PENDING",
                "createdAt", String.valueOf(now),
                "updatedAt", String.valueOf(now)
        ));
        redis.opsForList().rightPush("gulimall:ai:imgtask:conv:" + conversationId, taskId);
        trackTask(taskId);

        // 2. 管理端查用户任务，归属校验失败
        Map<String, Object> response = get("/agent/images/tasks/" + taskId, adminHeaders());
        assertEquals(15001, code(response));

        // 3. 管理端查用户会话任务列表，列表会过滤掉非本人任务
        List<Map<String, Object>> tasks = list(get(
                "/agent/images/tasks?conversationId=" + conversationId, adminHeaders()));
        assertFalse(tasks.stream().anyMatch(item -> taskId.equals(item.get("id"))));
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
