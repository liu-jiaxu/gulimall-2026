package com.atguigu.gulimall.thirdparty.service;

import java.util.Map;

/**
 * @ClassName: FileUploadService
 * @Package: com.atguigu.gulimall.thirdparty.service.impl
 * @Description: MinIO 前端直传：后端只签发 presigned URL，文件由前端直接 PUT 上传
 * @Author: 刘家旭
 * @Create: 2026/9/2 - 21:53
 * @Version: v1.0
 */

public interface FileUploadService {

    /**
     * 生成 MinIO presigned PUT 直传 URL + GET 可访问 URL
     * @param originalFilename 原始文件名（用于生成对象 key，会加日期目录 + uuid 前缀）
     * @param socketPackageName bucket 包名（用于区分不同业务模块的文件存储目录）
     * @return map，含：
     *         putUrl —— PUT 直传签名 URL（短时，默认 10 分钟，供前端直传文件）
     *         getUrl —— GET 可访问签名 URL（长时，默认 7 天，供前端展示图片）
     */
    Map<String, String> getUploadUrl(String originalFilename, String socketPackageName) throws Exception;

}
