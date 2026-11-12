package com.atguigu.gulimall.agent.chat.controller;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.dto.GenerateImageRequest;
import com.atguigu.gulimall.agent.image.ImageMetadataService;
import com.atguigu.gulimall.agent.image.ImageStorageService;
import com.atguigu.gulimall.agent.image.ImageTaskService;
import com.atguigu.gulimall.agent.image.ImageValidationService;
import com.atguigu.gulimall.common.utils.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 图片上传 / 生成。
 */
@RestController
@RequestMapping("/agent/images")
@RequiredArgsConstructor
public class ImageController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final ImageValidationService validationService;
    private final ImageStorageService storageService;
    private final ImageMetadataService metadataService;
    private final ImageTaskService imageTaskService;

    /**
     * 上传图片，返回 imageId 和可访问 URL。
     *
     * @param principal 当前登录身份，决定图片存放的目录与归属
     * @param file      上传的图片文件
     * @return 统一响应，data 里是 imageId、访问 URL、宽高
     */
    @PostMapping
    public R upload(Principal principal, @RequestParam("file") MultipartFile file) {
        // 1. 校验大小/类型/尺寸
        ImageValidationService.ValidatedImage image = validationService.validate(file);

        // 2. 生成对象路径：ai/upload/{USER|ADMIN}/{id}/{日期}/{uuid}.{ext}
        String objectKey = "ai/upload/" + principal.type().name() + "/" + principal.id()
                + "/" + LocalDate.now().format(DAY) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + image.extension();
        String url = storageService.upload(image.bytes(), image.mimeType(), objectKey);

        // 3. 元数据存 Redis，3 天
        String imageId = UUID.randomUUID().toString().replace("-", "");
        metadataService.save(principal, imageId, objectKey, url, image.mimeType(), image.bytes().length);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imageId", imageId);
        data.put("url", url);
        data.put("width", image.width());
        data.put("height", image.height());
        return R.ok().put("data", data);
    }

    /**
     * 提交图片生成任务，立即返回 taskId。
     *
     * <p>图片生成通常要 1～3 分钟，不能同步等待；
     * 前端拿 taskId 轮询 /tasks/{taskId}，完成后图片消息会自动写进会话。
     *
     * @param principal 当前登录身份
     * @param request   图片生成请求，包含提示词、会话 id、尺寸与质量
     * @return 统一响应，data 里是 taskId、初始状态、提示词与会话 id
     */
    @PostMapping("/generate")
    public R generate(Principal principal, @RequestBody GenerateImageRequest request) {
        // 1. 提交后台任务
        String taskId = imageTaskService.submit(
                principal, request.getConversationId(),
                request.getPrompt(), request.getSize(), request.getQuality());

        // 2. 立即返回，不阻塞请求
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", "PENDING");
        data.put("prompt", request.getPrompt());
        data.put("conversationId", request.getConversationId());
        return R.ok().put("data", data);
    }

    /**
     * 查询某个会话下的图片任务，前端轮询用。
     *
     * @param principal      当前登录身份
     * @param conversationId 会话 id
     * @return 统一响应，list 里是该会话下的图片任务
     */
    @GetMapping("/tasks")
    public R tasks(Principal principal,
                   @RequestParam("conversationId") String conversationId) {
        return R.ok().put("list", imageTaskService.list(principal, conversationId));
    }

    /**
     * 查询单个图片任务状态。
     *
     * @param principal 当前登录身份
     * @param taskId    图片任务 id
     * @return 统一响应，data 里是该任务的状态信息
     */
    @GetMapping("/tasks/{taskId}")
    public R task(Principal principal, @PathVariable("taskId") String taskId) {
        return R.ok().put("data", imageTaskService.get(principal, taskId));
    }

    /**
     * 查询图片元数据（只能查自己的）。
     *
     * @param principal 当前登录身份
     * @param imageId   图片 id
     * @return 统一响应，data 里是 imageId、访问 URL、MIME 类型与文件大小
     */
    @GetMapping("/{imageId}")
    public R info(Principal principal, @PathVariable("imageId") String imageId) {
        ImageMetadataService.ImageMeta meta = metadataService.get(principal, imageId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imageId", meta.imageId());
        data.put("url", meta.url());
        data.put("mime", meta.mime());
        data.put("size", meta.size());
        return R.ok().put("data", data);
    }
}
