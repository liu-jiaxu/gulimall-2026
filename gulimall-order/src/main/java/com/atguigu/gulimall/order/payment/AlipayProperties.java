package com.atguigu.gulimall.order.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付宝开放平台配置。
 *
 * <p>密钥不要提交到 Git：本地放 IDEA 环境变量，部署放配置中心/环境变量。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gulimall.alipay")
public class AlipayProperties {

    /** 沙箱 APPID（正式环境换成正式应用 APPID） */
    private String appId;

    /**
     * 应用私钥（PKCS8，单行字符串）。
     * 不是支付宝公钥，也不是应用公钥。
     */
    private String privateKey;

    /** 支付宝公钥：沙箱控制台上传应用公钥后复制回来的那个 */
    private String alipayPublicKey;

    /** 网关：沙箱用 https://openapi-sandbox.dl.alipaydev.com/gateway.do */
    private String gatewayUrl;

    /** 异步通知地址，必须公网可访问 */
    private String notifyUrl;

    /** 同步跳转地址 */
    private String returnUrl;

    /** 签名类型，固定 RSA2 */
    private String signType = "RSA2";

    /** 字符集 */
    private String charset = "UTF-8";

    /** 返回格式 */
    private String format = "json";
}
