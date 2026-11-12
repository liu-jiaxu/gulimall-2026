package com.atguigu.gulimall.agent.image;

import io.minio.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 图片对象存储：上传、下载、拼接可访问 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStorageService {

    private final MinioClient minioClient;

    @Value("${MINIO_BUCKET:gulimall}")
    private String bucket;

    @Value("${MINIO_PUBLIC_PREFIX:/oss}")
    private String publicPrefix;

    /**
     * 应用启动时确保目标 MinIO 桶存在，不存在则自动创建。
     */
    @PostConstruct
    public void ensureBucket() {
        // 1. 首次部署时桶可能还没建，自动建一个；已存在则跳过
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO bucket：{}", bucket);
            }
        } catch (Exception ex) {
            // 2. MinIO 暂时不可用不阻止应用启动，真正上传时再报错
            log.warn("检查 MinIO bucket 失败：{}", ex.getMessage());
        }
    }

    /**
     * 将图片字节上传到 MinIO 并返回可访问的公开地址。
     *
     * @param bytes       图片的字节内容
     * @param contentType 图片的 MIME 类型，如 image/png
     * @param objectKey   对象在桶内的存储路径
     * @return 拼接后的公开访问地址
     */
    public String upload(byte[] bytes, String contentType, String objectKey) {
        try {
            // 1. 直接写字节数组，大小已知
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
            return publicUrl(objectKey);
        } catch (Exception ex) {
            throw new IllegalStateException("图片上传 MinIO 失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 从 MinIO 读取指定对象的完整字节内容。
     *
     * @param objectKey 对象在桶内的存储路径
     * @return 图片的字节内容
     */
    public byte[] download(String objectKey) {
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return input.readAllBytes();
        } catch (Exception ex) {
            throw new IllegalStateException("读取 MinIO 图片失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 拼接对象的外部访问地址，统一使用根相对路径。
     *
     * @param objectKey 对象在桶内的存储路径
     * @return 以公共前缀和桶名开头的访问地址
     */
    public String publicUrl(String objectKey) {
        // 1. 数据库/页面里统一用 /oss/gulimall/... 这种根相对路径
        return publicPrefix + "/" + bucket + "/" + objectKey;
    }
}
