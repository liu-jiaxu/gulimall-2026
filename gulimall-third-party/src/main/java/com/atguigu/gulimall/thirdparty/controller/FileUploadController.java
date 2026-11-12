package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.thirdparty.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @ClassName: FileUploadController
 * @Package: com.atguigu.gulimall.thirdparty.controller
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/2 - 21:52
 * @Version: v1.0
 */
@RestController
@RequestMapping("/admin/system/minio")
public class FileUploadController {

    @Autowired
    private FileUploadService fileUploadService;

    /**
     * 获取 MinIO 上传地址与文件访问地址（前端直传 MinIO，后端不碰文件流）
     * <p>
     * <a href="http://localhost:30000/admin/system/minio/uploadUrl?fileName=test.png&bucketPackageName=test">http://localhost:30000/admin/system/minio/uploadUrl?fileName=test.png&bucketPackageName=test</a>
     * @param fileName 原始文件名
     * @param bucketPackageName bucket 包名（用于区分不同业务模块的文件存储目录），可不传，缺省使用 default
     * @return uploadUrl —— PUT 直传地址，带签名、<b>短时有效（10 分钟）</b>，仅用于本次上传
     *         fileUrl   —— 文件访问地址，<b>永久有效（无签名）</b>，前端存库后可直接用于图片展示
     */
    @PostMapping("/uploadUrl")
    public R getUploadUrl(@RequestParam("fileName") String fileName,
                          @RequestParam(value = "bucketPackageName", required = false) String bucketPackageName) {
        try {
            Map<String, String> urls = fileUploadService.getUploadUrl(fileName, bucketPackageName);
            return R.ok()
                    .put("uploadUrl", urls.get("putUrl"))
                    .put("fileUrl", urls.get("getUrl"));
        } catch (Exception e) {
            return R.error("生成上传地址失败：" + e.getMessage());
        }
    }

}
