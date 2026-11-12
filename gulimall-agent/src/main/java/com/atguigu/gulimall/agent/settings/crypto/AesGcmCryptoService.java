package com.atguigu.gulimall.agent.settings.crypto;

import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 自定义模型 apiKey 的加密存储。
 *
 * <p>格式：{@code v1:} + Base64(12 字节 IV || GCM 密文 || 16 字节 Tag)。
 * 主密钥来自环境变量 GULIMALL_AI_AES_KEY（Base64 编码的 16/24/32 字节）。
 * 主密钥不写进数据库，也不返回给前端。
 */
@Slf4j
@Component
public class AesGcmCryptoService {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 使用配置的 Base64 主密钥初始化加密服务。
     *
     * @param base64Key Base64 编码的 AES 主密钥，未配置时服务仍可启动但加密功能不可用
     */
    public AesGcmCryptoService(@Value("${AI_SETTINGS_AES_KEY:}") String base64Key) {
        // 1. 没配主密钥时不阻止应用启动，但保存自定义模型会给出明确错误
        if (base64Key == null || base64Key.isBlank()) {
            this.secretKey = null;
            log.warn("未配置 AI_SETTINGS_AES_KEY，自定义模型保存功能不可用");
            return;
        }

        // 2. 解码并校验长度：AES 只接受 16/24/32 字节
        byte[] keyBytes = Base64.getDecoder().decode(base64Key.trim());
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("AI_SETTINGS_AES_KEY 必须是 Base64 编码的 16/24/32 字节密钥");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 判断是否已配置可用的主密钥。
     *
     * @return 主密钥已加载时返回 true，否则返回 false
     */
    public boolean isAvailable() {
        return secretKey != null;
    }

    /**
     * 使用 AES-GCM 加密明文 apiKey，结果带 v1: 前缀。
     *
     * @param plainText 待加密的明文 apiKey
     * @return v1: 前缀加上 Base64 编码的 IV 与密文
     */
    public String encrypt(String plainText) {
        // 1. 没有主密钥时直接拒绝，不能把明文写进数据库
        if (!isAvailable()) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID,
                    "未配置 AI_SETTINGS_AES_KEY，无法保存自定义模型的 apiKey");
        }
        if (plainText == null || plainText.isBlank()) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "apiKey 不能为空");
        }

        try {
            // 2. 每次加密都用新的随机 IV，避免相同明文产生相同密文
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 3. IV 和密文拼在一起存，解密时前 12 字节就是 IV
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "apiKey 加密失败", ex);
        }
    }

    /**
     * 解密 v1: 前缀的 apiKey 密文。
     *
     * @param cipherText 待解密的密文
     * @return 解密后的明文 apiKey
     */
    public String decrypt(String cipherText) {
        // 1. 兼容历史数据：没有 v1: 前缀说明不是本服务写的
        if (cipherText == null || !cipherText.startsWith(PREFIX)) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "apiKey 密文格式不正确");
        }
        if (!isAvailable()) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID,
                    "未配置 AI_SETTINGS_AES_KEY，无法解密自定义模型");
        }

        try {
            // 2. 拆出 IV 和密文
            byte[] packed = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            byte[] data = new byte[packed.length - IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
            System.arraycopy(packed, IV_LENGTH, data, 0, data.length);

            // 3. GCM 解密（Tag 校验失败会抛 AEADBadTagException，通常说明主密钥换了）
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID,
                    "apiKey 解密失败，请检查 AI_SETTINGS_AES_KEY 是否变更", ex);
        }
    }

    /**
     * 前端展示用的掩码，绝不返回明文。
     *
     * @param plainText 原始 apiKey 明文
     * @return 只保留前三位和后四位的掩码字符串
     */
    public String mask(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        if (plainText.length() <= 8) {
            return "****";
        }
        return plainText.substring(0, 3) + "****" + plainText.substring(plainText.length() - 4);
    }
}
