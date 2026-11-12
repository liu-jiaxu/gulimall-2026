package com.atguigu.gulimall.thirdparty;

import com.atguigu.gulimall.thirdparty.property.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.ErrorResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * discovery.enabled=false：这个测试只用 MinIO，不需要注册到 Nacos，
 * 关掉后跑测试就不要求 Nacos 必须开着。
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false"})
class MinIOTest {

    @Autowired
    private MinioProperties minioProperties;

    @Test
    public void minIO() throws InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException, io.minio.errors.ServerException, io.minio.errors.ErrorResponseException {
        String endpoint = minioProperties.getEndpointUrl();
        String bucket = minioProperties.getBucketName();

        // 创建一个Minio的客户端对象
        MinioClient minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecreKey())
                .build();

        boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());

        // 如果不存在，那么此时就创建一个新的桶
        if (!found) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } else {  // 如果存在打印信息
            System.out.println("Bucket '" + bucket + "' already exists.");
        }

        // 从 classpath 加载测试资源（src/test/resources/kunkun.jpg 编译后进 target/test-classes），
        // 不依赖当前工作目录
        InputStream is = MinIOTest.class.getClassLoader().getResourceAsStream("kunkun.jpg");
        Assertions.assertNotNull(is);
        PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                .bucket(bucket)
                .stream(is, is.available(), -1)
                .object("kunkun.jpg")
                .build();
        minioClient.putObject(putObjectArgs);

        // 构建fileUrl
        String fileUrl = endpoint + "/" + bucket + "/kunkun.jpg";
        System.out.println(fileUrl);
    }

}
