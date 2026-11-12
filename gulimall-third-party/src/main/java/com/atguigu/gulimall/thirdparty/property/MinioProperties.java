package com.atguigu.gulimall.thirdparty.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @ClassName: MinioProperties
 * @Package: com.atguigu.gulimall.thirdparty.property
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/2 - 21:55
 * @Version: v1.0
 */
@Data
@ConfigurationProperties(prefix="gulimall.minio") //读取节点
public class MinioProperties {

    private String endpointUrl;
    private String accessKey;
    private String secreKey;
    private String bucketName;

    /**
     * 图片【对外访问地址】的前缀，是个根相对路径（如 /img）。
     * <p>
     * 存进数据库 / ES 的图片地址 = publicPrefix + "/" + bucketName + "/" + 对象名，
     * 形如 {@code /img/gulimall/brand/2026-09-07/xxx.png}。
     * <p>
     * 与 endpointUrl 的区别：
     * <ul>
     *   <li>endpointUrl —— MinIO 的真实地址，给 SDK 用（上传签名等），是【内网】地址</li>
     *   <li>publicPrefix —— 给浏览器用的地址前缀，必须能跟着"当前页面的源"解析</li>
     * </ul>
     * 用根相对路径而不是绝对地址，是为了：手机走 IP+端口 入口、电脑走域名入口时，
     * 图片都能跟着页面的源走，库里也就不存任何 IP / 域名。
     * 对应的 nginx 转发规则见 img-proxy.inc 的 <code>location ^~ /img/</code>。
     */
    private String publicPrefix;

}