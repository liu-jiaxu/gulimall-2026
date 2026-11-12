package com.atguigu.gulimall.agent.image;

import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 上传图片校验：大小、类型、魔数、尺寸。
 *
 * <p>不信任浏览器传来的 Content-Type，按文件头判断真实类型；
 * JPEG/PNG 会解码一次确认不是伪装文件，超过 2048px 自动缩小后再送模型。
 */
@Service
@RequiredArgsConstructor
public class ImageValidationService {

    private static final int MAX_SIDE = 2048;

    private final AiProperties properties;

    /**
     * 校验上传图片的大小、真实类型与尺寸，必要时缩放到最大边 2048 像素。
     *
     * @param file 浏览器上传的图片文件
     * @return 校验并处理后的图片数据及元信息
     */
    public ValidatedImage validate(MultipartFile file) {
        // 1. 基本大小校验
        if (file == null || file.isEmpty()) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "请选择要上传的图片");
        }
        long maxBytes = properties.getImage().getMaxUploadBytes();
        if (file.getSize() > maxBytes) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID,
                    "图片不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }

        try {
            byte[] bytes = file.getBytes();

            // 2. 按魔数判断真实类型
            String mime = detectMime(bytes);
            if (mime == null || !properties.getImage().getAllowedTypes().contains(mime)) {
                throw new AgentException(AgentErrorCode.IMAGE_INVALID, "只支持 JPEG / PNG / WebP 图片");
            }

            // 3. WebP JDK 默认不能解码，原样放行；JPEG/PNG 解码并缩放
            if ("image/webp".equals(mime)) {
                return new ValidatedImage(bytes, mime, "webp", 0, 0);
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new AgentException(AgentErrorCode.IMAGE_INVALID, "图片文件已损坏或格式不支持");
            }

            BufferedImage resized = resizeIfNeeded(image);
            String extension = "image/png".equals(mime) ? "png" : "jpg";
            String format = "image/png".equals(mime) ? "png" : "jpg";
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(resized, format, output);
            return new ValidatedImage(output.toByteArray(), mime, extension,
                    resized.getWidth(), resized.getHeight());
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.IMAGE_INVALID, "图片读取失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 当图片最大边超过 2048 像素时按比例缩放，否则原样返回。
     *
     * @param source 原始图片
     * @return 缩放后的图片或原图
     */
    private BufferedImage resizeIfNeeded(BufferedImage source) {
        // 1. 最大边不超过 2048 就原样返回
        int width = source.getWidth();
        int height = source.getHeight();
        int maxSide = Math.max(width, height);
        if (maxSide <= MAX_SIDE) {
            return source;
        }

        // 2. 等比缩放到 2048，减少视觉模型的 token 和上传体积
        double ratio = (double) MAX_SIDE / maxSide;
        int targetW = Math.max(1, (int) Math.round(width * ratio));
        int targetH = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, targetW, targetH, null);
        graphics.dispose();
        return target;
    }

    /**
     * 根据文件头魔数识别图片的真实 MIME 类型。
     *
     * @param bytes 图片字节内容
     * @return 识别出的 MIME 类型，无法识别时返回 null
     */
    private String detectMime(byte[] bytes) {
        // 1. JPEG：FF D8 FF
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }

        // 2. PNG：89 50 4E 47
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }

        // 3. WebP：RIFF....WEBP
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * 校验并处理后的图片数据。
     *
     * @param bytes     图片字节
     * @param mimeType  图片 MIME 类型
     * @param extension 文件扩展名
     * @param width     图片宽度，WebP 未解码时为 0
     * @param height    图片高度，WebP 未解码时为 0
     */
    public record ValidatedImage(byte[] bytes, String mimeType, String extension, int width, int height) {
    }
}
