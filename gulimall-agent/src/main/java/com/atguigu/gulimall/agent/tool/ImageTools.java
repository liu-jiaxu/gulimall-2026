package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.image.ImageTaskService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 图片生成工具：模型判断用户想画图时，提交后台任务后立即返回。
 *
 * <p>gpt-image-1-mini 生成很慢，不能阻塞聊天；真正的图片由
 * {@link ImageTaskService} 在独立线程里生成，完成后写回会话。
 */
public class ImageTools {

    private final ToolContext context;
    private final ImageTaskService imageTaskService;

    /**
     * 创建图片生成工具实例。
     *
     * @param context          当前请求上下文，提供登录身份和会话 id
     * @param imageTaskService 后台图片任务服务，负责异步生成并回写图片
     */
    public ImageTools(ToolContext context, ImageTaskService imageTaskService) {
        this.context = context;
        this.imageTaskService = imageTaskService;
    }

    /**
     * 提交一张与商城相关的图片生成任务，立即返回任务 id，不阻塞聊天。
     *
     * @param prompt 图片内容描述，尽量具体
     * @param size   图片尺寸，可选 1024x1024、1024x1536、1536x1024，可为空
     * @return 任务提交结果的提示文本；提交失败时返回中文错误提示
     */
    @Tool("只生成与谷粒商城相关的图片：商品主图、促销海报、活动横幅、优惠券/秒杀宣传图、店铺/品牌视觉。"
            + "与商城无关的绘画需求不要调用本工具，直接拒绝。")
    public String generateImage(@P("图片内容描述，尽量具体") String prompt,
                                @P("尺寸：1024x1024、1024x1536、1536x1024，可为空") String size) {
        try {
            // 1. 只提交任务，立即返回，模型和前端都不用等图片生成完
            String taskId = imageTaskService.submit(
                    context.principal(), context.conversationId(), prompt, size, null);

            // 2. 明确告诉模型和用户：已经在后台画了，稍后自动出现
            return "图片正在后台生成（任务 " + taskId + "），完成后会自动出现在对话里。"
                    + "请先继续回答用户的其他问题，不需要一直等待。";
        } catch (Exception ex) {
            // 3. 队列满/参数错误时给一句可读提示
            return "图片生成失败：" + (ex.getMessage() == null ? "请稍后再试" : ex.getMessage());
        }
    }
}
