package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.image.VisionAnalysisService;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.6：图片上传与看图。
 */
@Tag("integration")
class Flow36ImageUploadVisionTest extends AgentIntegrationTestSupport {

    @Autowired
    private VisionAnalysisService visionAnalysisService;

    /**
     * 测试真实 PNG 上传、元数据查询、视觉识别，以及带 imageId 的聊天链路。
     */
    @Test
    void uploadInfoAndVisionChat() throws Exception {
        byte[] png = solidPng(64, 64, Color.RED);

        // 1. 上传真实 PNG
        Map<String, Object> uploadResponse = upload(png, "red.png", userHeaders());
        assertEquals(0, code(uploadResponse));
        Map<String, Object> uploaded = data(uploadResponse);
        String imageId = String.valueOf(uploaded.get("imageId"));
        trackImage(imageId);
        assertTrue(String.valueOf(uploaded.get("url")).startsWith("/oss/gulimall/ai/upload/USER/"));
        assertEquals(64, ((Number) uploaded.get("width")).intValue());

        // 2. 查询元数据
        Map<String, Object> infoResponse = get("/agent/images/" + imageId, userHeaders());
        assertEquals(0, code(infoResponse));
        assertEquals(imageId, data(infoResponse).get("imageId"));
        assertEquals("image/png", data(infoResponse).get("mime"));

        // 3. 直接调用视觉服务，确认识别成功
        String analysis = visionAnalysisService.analyze(
                TEST_USER, png, "image/png", "这张图是什么颜色？");
        assertFalse(analysis.startsWith("图片识别失败"), analysis);
        assertTrue(analysis.contains("红") || analysis.toLowerCase().contains("red"), analysis);

        // 4. 带 imageId 发起真实聊天，验证 prepare 中的下载、识别和拼接
        Map<String, Object> body = new HashMap<>();
        body.put("message", "这张图是什么颜色？只回答颜色");
        body.put("modelId", "deepseek-v4.1-flash");
        body.put("imageIds", List.of(imageId));
        Map<String, Object> chatResponse = post("/agent/chat", body, userHeaders());
        assertEquals(0, code(chatResponse));
        Map<String, Object> chatData = data(chatResponse);
        trackConversation(String.valueOf(chatData.get("conversationId")));
        assertFalse(String.valueOf(chatData.get("content")).isBlank());
    }

    /**
     * 测试非图片文件被图片校验拒绝并返回 15008。
     */
    @Test
    void invalidFileIsRejected() {
        // 1. 上传文本文件，魔数不是图片
        Map<String, Object> response = upload(
                "not an image".getBytes(), "a.txt", userHeaders());

        // 2. 图片校验返回 15008
        assertEquals(15008, code(response));
    }

    /**
     * 测试超大图片上传时按最大边 2048 缩放。
     */
    @Test
    void oversizedImageIsResized() throws Exception {
        // 1. 上传 3000x3000 的 PNG
        byte[] png = solidPng(3000, 3000, Color.BLUE);
        Map<String, Object> response = upload(png, "large.png", userHeaders());
        assertEquals(0, code(response));
        Map<String, Object> data = data(response);
        trackImage(String.valueOf(data.get("imageId")));

        // 2. 最大边被压到 2048
        assertTrue(((Number) data.get("width")).intValue() <= 2048);
        assertTrue(((Number) data.get("height")).intValue() <= 2048);
    }

    /**
     * 测试其他身份不能读取或使用当前用户的图片。
     */
    @Test
    void otherPrincipalCannotUseImage() throws Exception {
        // 1. 用户上传图片
        Map<String, Object> uploadResponse = upload(
                solidPng(32, 32, Color.GREEN), "green.png", userHeaders());
        String imageId = String.valueOf(data(uploadResponse).get("imageId"));
        trackImage(imageId);

        // 2. 管理端读取该图片元数据
        Map<String, Object> infoResponse = get("/agent/images/" + imageId, adminHeaders());
        assertEquals(15008, code(infoResponse));

        // 3. 管理端在聊天里使用该 imageId
        Map<String, Object> body = new HashMap<>();
        body.put("message", "这张图是什么颜色？");
        body.put("modelId", "deepseek-v4.1-flash");
        body.put("imageIds", List.of(imageId));
        assertEquals(15008, code(post("/agent/chat", body, adminHeaders())));
    }

    private byte[] solidPng(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
