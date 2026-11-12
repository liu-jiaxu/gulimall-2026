package com.atguigu.gulimall.thirdparty.service.impl;

import com.atguigu.gulimall.thirdparty.property.MinioProperties;
import com.atguigu.gulimall.thirdparty.service.FileUploadService;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @ClassName: FileUploadServiceImpl
 * @Package: com.atguigu.gulimall.thirdparty.service.impl
 * @Description: MinIO 前端直传实现：签发 presigned PUT 上传地址，文件由前端直接上传 MinIO，后端不碰文件流
 * @Author: 刘家旭
 * @Create: 2026/9/2 - 21:54
 * @Version: v1.0
 */
@Service
public class FileUploadServiceImpl implements FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadServiceImpl.class);

    /** PUT 直传签名 URL 有效期（秒）。签名只用于【一次性上传】，短期有效足够，过期也没影响 */
    private static final int PUT_EXPIRY_SECONDS = 60 * 10;

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public FileUploadServiceImpl(MinioProperties minioProperties, MinioClient minioClient) {
        this.minioProperties = minioProperties;
        this.minioClient = minioClient;
    }

    /**
     * 应用启动时校正一次桶的公共读策略。
     * <p>
     * 放在启动时机的原因：<b>已有</b>的图片不必重新上传就能恢复访问 ——
     * 新版本 MinIO 控制台已移除 Access Policy 设置入口，这是最省事的设置方式。
     * <p>
     * 失败只告警、不阻断启动（MinIO 未就绪时应用照样能起，首次上传时会重试）。
     */
    @PostConstruct
    public void initBucketPolicy() {
        try {
            ensureBucketWithPublicRead();
            log.info("MinIO 桶 [{}] 已就绪（公共读）", minioProperties.getBucketName());
        } catch (Exception e) {
            log.warn("MinIO 桶初始化失败，将在首次上传时重试：{}", e.getMessage());
        }
    }

    @Override
    public Map<String, String> getUploadUrl(String originalFilename, String bucketPackageName) throws Exception {
        // 确保桶存在且【公共读】—— 桶被误改成私有会导致所有图片 403，所以每次上传都幂等地校正一遍
        ensureBucketWithPublicRead();

        // 对象 key：日期目录/uuid+原文件名（如 2026-09-02/443e...a901.png）
        String packageName = StringUtils.isNotBlank(bucketPackageName) ? bucketPackageName + "/" : "default/";
        String dateDir = LocalDate.now().format(DateTimeFormatter.ISO_DATE) + "/";
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String objectName = packageName + dateDir + uuid + originalFilename;

        // 1、PUT 直传签名 URL：前端拿到后直接 PUT 文件二进制上去
        //    这是【唯一】需要签名的地方 —— 上传是写操作，必须鉴权且短期有效
        String putUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(minioProperties.getBucketName())
                .object(objectName)
                .expiry(PUT_EXPIRY_SECONDS)
                .build());

        // 2、对外访问地址：永久 URL，【不带任何签名参数】
        //    品牌 logo / 商品图属于公开资源，桶设为公共读后这个地址永远有效，
        //    前端可直接存库、直接用于 <img src>，不用担心过期
        String publicUrl = buildPublicUrl(objectName);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("putUrl", putUrl);
        result.put("getUrl", publicUrl);
        return result;
    }

    /**
     * 拼接对象的永久公开访问地址（【根相对路径】，不含 host）
     * <p>
     * 形如 {@code /img/gulimall/brand/2026-09-07/xxx.png}。
     * 以 / 开头时浏览器会用【当前页面的源】补全，所以：
     * 手机走 IP+端口 入口、电脑走域名入口，图片都能跟着页面走 —— 库里不存 IP / 域名。
     * <p>
     * 由 nginx 的 {@code location ^~ /img/}（见 img-proxy.inc）转发到 MinIO。
     * <p>
     * 对象名做了 URL 编码（保留 / 作为路径分隔符），避免中文/空格文件名拼出非法 URL
     */
    private String buildPublicUrl(String objectName) {
        String prefix = StringUtils.removeEnd(minioProperties.getPublicPrefix(), "/");
        String objectPath = UriUtils.encodePath(objectName, StandardCharsets.UTF_8);
        return prefix + "/" + minioProperties.getBucketName() + "/" + objectPath;
    }

    /**
     * 确保桶存在，且拥有「匿名只读」策略。幂等，可重复调用。
     * <p>
     * 策略只授予 {@code s3:GetObject}，<b>不含</b> {@code s3:ListBucket} ——
     * 即：拿到完整地址的人能看到这张图，但无法列举桶里有哪些文件。
     * <p>
     * 若图片要求保密（合同、发票等），不要走这个策略，应改为
     * 「桶保持私有 + 返回前动态签名」或「后端代理转发」。
     */
    private void ensureBucketWithPublicRead() throws Exception {
        String bucket = minioProperties.getBucketName();

        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                .bucket(bucket)
                .config(publicReadPolicy(bucket))
                .build());
    }

    /**
     * 生成「匿名只读」桶策略 JSON。
     * <p>
     * 这是 <b>AWS S3 的桶策略（Bucket Policy）</b>格式 —— MinIO 兼容 S3 API，语法照搬 AWS，
     * 所以 ARN 里会看到 {@code arn:aws:s3:::} 里的 {@code aws}（MinIO 不校验这段，照写即可）。
     * <p>
     * <b>逐字段说明：</b>
     * <pre>
     * Version   策略语言的【版本号，不是日期】。"2012-10-17" 是当前唯一在用的值
     *           （AWS 2012 年改版策略语法引入），写别的值会解析失败。可省略，惯例都写。
     *
     * Statement 一个数组，一条策略可含多条规则。规则之间：【Deny 优先于 Allow】——
     *           只要有一条 Deny 命中就拒绝，不管别处怎么 Allow。
     *
     * Effect    这条规则的结论，只有 "Allow" / "Deny" 两个取值。
     *
     * Principal 【谁】——桶策略【独有】的字段，用户策略(IAM)里没有它。
     *           因为桶策略是"我作为资源所有者，声明谁能访问我"，必须指明主体；
     *           而 IAM 策略的主体就是"这个用户本人"，不言自明。
     *             {"AWS": ["*"]}  → 任何人，【包括未登录的匿名请求】← "公共读"的来源
     *             {"AWS": ["arn:aws:iam::123456789012:user/alice"]} → 只允许 alice
     *
     * Action     【能做什么操作】，格式 服务:操作。支持 "s3:Get*" 通配，但越具体越安全。
     *             s3:GetObject     下载/读取【对象】          ← 本策略只给这个
     *             s3:ListBucket    列举桶内【有哪些对象】      ← 故意不给，见下方说明
     *             s3:PutObject     上传
     *             s3:DeleteObject  删除
     *
     * Resource   【对哪些资源生效】，用 ARN 表示，见下方拆解。
     * </pre>
     * <p>
     * <b>ARN 拆解（arn:aws:s3:::gulimall/*）：</b>
     * <pre>
     * arn : aws : s3 : &lt;region&gt; : &lt;account-id&gt; : gulimall/*
     *  │     │    │        │             │           │       │
     *  │     │    │        │             │           │       └─ 桶内所有对象
     *  │     │    │        │             │           └───────── 桶名
     *  │     │    │        │             └───────────────────── S3 的 ARN 里这段【留空】
     *  │     │    │        └─────────────────────────────────── S3 的 ARN 里这段也【留空】
     *  │     │    └──────────────────────────────────────────── 服务名
     *  │     └───────────────────────────────────────────────── 分区（AWS 中国区是 aws-cn）
     *  └─────────────────────────────────────────────────────── Amazon Resource Name 前缀
     * </pre>
     * <p>
     * ⚠️ <b>最容易踩的坑：带 {@code /*} 和不带是两个不同的资源，写错会【静默失效】。</b>
     * <pre>
     * arn:aws:s3:::gulimall      桶【本身】   → 对应 s3:ListBucket 这类"桶级"操作
     * arn:aws:s3:::gulimall/*    桶内【对象】 → 对应 s3:GetObject  这类"对象级"操作
     * </pre>
     * 例如把 GetObject 的 Resource 写成不带 {@code /*} 的，策略看着没问题但访问图片一直 403
     * —— 这是 S3 策略最常见的故障原因。
     * <p>
     * ⚠️ <b>为什么只给 s3:GetObject？</b>对比下面这种「灾难写法」：
     * <pre>
     * "Principal": { "AWS": ["*"] }, "Action": ["s3:*"],
     * "Resource": ["arn:aws:s3:::gulimall", "arn:aws:s3:::gulimall/*"]
     * </pre>
     * 那等于整个桶对全世界可读可写可删 —— 任何人可以覆盖或删除你的图片，甚至塞入恶意文件。
     * 本策略刻意收窄到只有读取，并且不含 ListBucket：<b>知道完整地址的人能看图，但无法
     * "翻目录"看到桶里存了哪些文件</b>（对象名是 uuid，猜不到，攻击面因此小很多）。
     * <p>
     * <b>一句话总结：</b>任何人（Principal: *）都可以下载（Action: s3:GetObject）
     * gulimall 桶里的任意对象（Resource: .../gulimall/*），除此之外什么都不能做。
     * 写策略时按「<b>谁</b>、<b>干什么</b>、<b>对什么</b>」三个问题过一遍，基本不会错。
     * <p>
     * 附：查看/撤销策略（无需改代码）——<br>
     * {@code mc anonymous get myminio/gulimall}　查看当前策略<br>
     * {@code mc anonymous set none myminio/gulimall}　撤销，恢复私有（之后访问图片会 403，
     * 所以这是个可逆的一键开关）
     *
     * @param bucket 桶名。用 %s 占位而非硬编码，换桶/多环境（dev、prod 不同桶名）时无需改 JSON
     */
    private String publicReadPolicy(String bucket) {
        // 注意：JSON 是【原样发给 MinIO】的，所以下面文本块里不能写任何注释
        //（JSON 不支持注释，加了会解析失败）。所有说明只能放在文本块【外面】。
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": { "AWS": ["*"] },
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
    }

}
