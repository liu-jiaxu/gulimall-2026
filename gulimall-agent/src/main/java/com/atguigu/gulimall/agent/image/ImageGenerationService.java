package com.atguigu.gulimall.agent.image;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.ModelFactory;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * 图片生成：gpt-image-1-mini -> base64 -> MinIO -> 可访问 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final ModelFactory modelFactory;
    private final ImageStorageService storageService;
    private final AiProperties properties;

    /**
     * 调用图片模型生成图片，并将结果存入 MinIO 后返回访问地址。
     *
     * @param principal 当前登录用户
     * @param prompt    图片生成描述
     * @param size      期望的图片尺寸
     * @param quality   期望的图片质量
     * @return 生成图片的访问地址
     */
    public String generate(Principal principal, String prompt, String size, String quality) {
        // 1. 提示词校验
        if (!StringUtils.hasText(prompt)) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "请描述要生成的图片内容");
        }
        String cleanPrompt = prompt.trim();
        if (cleanPrompt.length() > 1000) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "图片描述不能超过 1000 字");
        }

        try {
            // 2. 尺寸/质量只接受接口支持的值，非法值回退默认，避免直接 400
            String finalSize = normalizeSize(size);
            String finalQuality = normalizeQuality(quality);
            ImageModel model = modelFactory.image(finalSize, finalQuality);
            Response<Image> response = model.generate(cleanPrompt);
            Image image = response.content();
            if (image == null) {
                throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED, "图片模型没有返回内容");
            }

            // 3. 有些代理直接返回 URL，能下载 URL 的情况这里先原样返回
            if (image.base64Data() == null || image.base64Data().isBlank()) {
                if (image.url() != null) {
                    return image.url().toString();
                }
                throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED, "图片模型返回为空");
            }

            // 4. base64 解码后存 MinIO
            byte[] bytes = Base64.getDecoder().decode(image.base64Data());
            String objectKey = "ai/generated/" + principal.type().name() + "/" + principal.id()
                    + "/" + LocalDate.now().format(DAY) + "/"
                    + UUID.randomUUID().toString().replace("-", "") + ".png";
            return storageService.upload(bytes, "image/png", objectKey);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("图片生成失败", ex);
            throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED,
                    "图片生成失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 读取配置中的默认图片尺寸。
     *
     * @return 默认尺寸
     */
    private String defaultSize() {
        return properties.getImage().getGeneration().getSize();
    }

    /**
     * 读取配置中的默认图片质量。
     *
     * @return 默认质量
     */
    private String defaultQuality() {
        return properties.getImage().getGeneration().getQuality();
    }

    /**
     * 校验尺寸是否为模型支持的值，不合法时回退默认尺寸。
     *
     * @param size 请求传入的尺寸
     * @return 合法的尺寸或默认尺寸
     */
    private String normalizeSize(String size) {
        // 1. gpt-image-1-mini 支持这三种尺寸
        if ("1024x1536".equals(size) || "1536x1024".equals(size) || "1024x1024".equals(size)) {
            return size;
        }
        return defaultSize();
    }

    /**
     * 校验质量是否为模型支持的值，不合法时回退默认质量。
     *
     * @param quality 请求传入的质量
     * @return 合法的质量或默认质量
     */
    private String normalizeQuality(String quality) {
        // 1. 只接受 low / medium / high
        if ("low".equals(quality) || "medium".equals(quality) || "high".equals(quality)) {
            return quality;
        }
        return defaultQuality();
    }
}
