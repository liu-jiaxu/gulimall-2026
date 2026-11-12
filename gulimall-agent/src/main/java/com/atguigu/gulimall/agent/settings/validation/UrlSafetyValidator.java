package com.atguigu.gulimall.agent.settings.validation;

import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * 自定义模型 baseUrl 的安全校验。
 *
 * <p>用户能自己填 baseUrl，如果不校验，就可能让服务器去访问内网地址
 * （SSRF：把服务器当成跳板去访问 Redis、MySQL、云元数据等）。
 * 这里做三层：协议必须是 HTTPS、拒绝常见内网网段、DNS 解析后再检查真实 IP。
 */
@Component
public class UrlSafetyValidator {

    /**
     * 校验用户填写的 baseUrl 是否允许访问，防止 SSRF。
     *
     * @param baseUrl 用户填写的模型服务地址
     * @param config  自定义模型的安全配置，决定是否强制 HTTPS 和拒绝内网地址
     */
    public void validate(String baseUrl, AiProperties.CustomModel config) {
        // 1. 基本格式：必须是 http(s) 的绝对地址
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "baseUrl 格式不正确");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "baseUrl 必须包含协议和主机名");
        }

        // 2. 生产要求 HTTPS，防止 apiKey 明文上网
        if (config.isRequireHttps() && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "baseUrl 必须使用 https://");
        }

        // 3. 先按字符串拦掉明显的本机/内网地址
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (isPrivateHost(host)) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "baseUrl 不允许指向内网或本机地址");
        }

        // 4. 再做 DNS 解析，防止用域名绕过字符串检查（例如 xxx.com 解析到 127.0.0.1）
        if (config.isDenyPrivateAddresses()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.isAnyLocalAddress()
                            || address.isLoopbackAddress()
                            || address.isSiteLocalAddress()
                            || address.isLinkLocalAddress()
                            || address.isMulticastAddress()) {
                        throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID,
                                "baseUrl 解析到了内网地址，已拒绝");
                    }
                }
            } catch (AgentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "baseUrl 域名无法解析");
            }
        }
    }

    /**
     * 按主机名字符串判断是否为本机或常见内网地址。
     *
     * @param host 已转为小写的主机名或 IP 字符串
     * @return 命中本机、回环或内网网段时返回 true
     */
    private boolean isPrivateHost(String host) {
        return "localhost".equals(host)
                || "127.0.0.1".equals(host)
                || "0.0.0.0".equals(host)
                || "::1".equals(host)
                || host.startsWith("10.")
                || host.startsWith("192.168.")
                || host.startsWith("169.254.")
                || host.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }
}
