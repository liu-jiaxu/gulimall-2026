package com.atguigu.gulimall.agent.image;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.model.ModelCatalog;
import com.atguigu.gulimall.agent.model.ModelFactory;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * 图片识别：固定使用视觉模型 gpt-6.1-sol。
 *
 * <p>gpt-image-1-mini 只能画图、不能看图，所以“识别”和“生成”必须分开：
 * 用户上传图片 -> 这里识别成文字 -> 再交给用户选择的对话模型回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisionAnalysisService {

    private final ModelCatalog modelCatalog;
    private final ModelFactory modelFactory;
    private final AiProperties properties;

    /**
     * 调用视觉模型识别图片内容，并结合用户问题返回文字结果。
     *
     * @param principal  当前登录用户
     * @param imageBytes 图片的字节内容
     * @param mimeType   图片的 MIME 类型
     * @param question   用户针对图片提出的问题
     * @return 视觉模型的识别结果文本，失败时返回错误说明
     */
    public String analyze(Principal principal, byte[] imageBytes, String mimeType, String question) {
        try {
            // 1. 取视觉模型（配置里 vision-model，默认 gpt-6.1-sol），
            //    看图单独用 90 秒超时，不能复用普通对话的 60 秒
            AiModelDefinition definition = modelCatalog.visionModel(principal)
                    .withTimeout(properties.getImage().getTimeout());
            ChatModel model = modelFactory.chat(definition);

            // 2. 图片转 base64，与用户问题一起组成多模态消息
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = "请客观识别这张图片的内容，并提取与下面问题相关的信息。"
                    + "不要编造图片里没有的东西。用户问题：" + question;

            UserMessage userMessage = UserMessage.from(List.of(
                    TextContent.from(prompt),
                    ImageContent.from(base64, mimeType)
            ));

            // 3. 调视觉模型
            ChatResponse response = model.chat(userMessage);
            if (response == null || response.aiMessage() == null
                    || response.aiMessage().text() == null) {
                return "图片识别没有返回内容";
            }
            return response.aiMessage().text();
        } catch (Exception ex) {
            log.warn("图片识别失败：{}", ex.getMessage());
            return "图片识别失败：" + ex.getMessage();
        }
    }
}
