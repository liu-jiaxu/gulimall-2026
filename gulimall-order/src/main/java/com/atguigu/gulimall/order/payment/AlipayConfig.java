package com.atguigu.gulimall.order.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝客户端配置。
 */
@Configuration
public class AlipayConfig {

    /**
     * AlipayClient 是线程安全的，整个应用共用一个即可。
     *
     * <p>构造参数顺序：
     * 1. 网关地址（沙箱/正式不同）
     * 2. APPID
     * 3. 应用私钥（PKCS8）
     * 4. 返回格式 json
     * 5. 字符集 UTF-8
     * 6. 支付宝公钥（用于验签支付宝返回的数据）
     * 7. 签名类型 RSA2
     */
    @Bean
    public AlipayClient alipayClient(AlipayProperties properties) {
        return new DefaultAlipayClient(
                properties.getGatewayUrl(),
                properties.getAppId(),
                properties.getPrivateKey(),
                properties.getFormat(),
                properties.getCharset(),
                properties.getAlipayPublicKey(),
                properties.getSignType());
    }
}
