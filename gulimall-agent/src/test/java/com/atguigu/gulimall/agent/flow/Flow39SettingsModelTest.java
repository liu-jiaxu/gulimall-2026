package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.ModelCatalog;
import com.atguigu.gulimall.agent.settings.crypto.AesGcmCryptoService;
import com.atguigu.gulimall.agent.settings.validation.UrlSafetyValidator;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.9：设置、自定义模型与模型探测。
 */
@Tag("integration")
class Flow39SettingsModelTest extends AgentIntegrationTestSupport {

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private AesGcmCryptoService cryptoService;

    @Autowired
    private UrlSafetyValidator urlSafetyValidator;

    @Autowired
    private ModelCatalog modelCatalog;

    /**
     * 测试 AI 设置首次创建、更新和非法主题色校验。
     */
    @Test
    void settingsReadUpdateAndValidation() {
        // 1. 首次读取会自动创建默认设置
        Map<String, Object> getResponse = get("/agent/settings", userHeaders());
        assertEquals(0, code(getResponse));
        Map<String, Object> setting = (Map<String, Object>) getResponse.get("setting");
        assertNotNull(setting.get("themeColor"));
        assertNotNull(setting.get("defaultModel"));

        // 2. 更新设置
        Map<String, Object> update = new HashMap<>();
        update.put("themeColor", "#123456");
        update.put("streamEnabled", false);
        update.put("defaultModel", "deepseek-v4.1-flash");
        update.put("customPrompt", "用简洁的中文回答");
        update.put("petPositionX", 120);
        update.put("petPositionY", 240);
        Map<String, Object> updateResponse = put("/agent/settings", update, userHeaders());
        assertEquals(0, code(updateResponse));
        Map<String, Object> updated = (Map<String, Object>) updateResponse.get("setting");
        assertEquals("#123456", updated.get("themeColor"));
        assertEquals(false, updated.get("streamEnabled"));
        assertEquals(120, ((Number) updated.get("petPositionX")).intValue());

        // 3. 非法主题色返回 15005
        Map<String, Object> invalid = put("/agent/settings",
                Map.of("themeColor", "red"), userHeaders());
        assertEquals(15005, code(invalid));
    }

    /**
     * 测试自定义模型创建、列表可见性、真实模型探测、普通用户越权探测和删除。
     */
    @Test
    void customModelCreateListProbeDelete() {
        // 1. 创建一个使用公共 key 的自定义模型
        Map<String, Object> request = new HashMap<>();
        request.put("displayName", "IT 测试模型");
        request.put("provider", "openai-compatible");
        request.put("modelName", "deepseek-v4.1-flash");
        request.put("baseUrl", "https://api.openai-proxy.org/v1");
        request.put("useSharedKey", true);
        request.put("supportsVision", false);
        request.put("supportsStreaming", true);
        request.put("temperature", 0.3);
        request.put("timeoutSeconds", 60);

        Map<String, Object> createResponse = post("/agent/settings/models", request, adminHeaders());
        assertEquals(0, code(createResponse));
        Map<String, Object> model = (Map<String, Object>) createResponse.get("model");
        Long id = ((Number) model.get("id")).longValue();
        String modelId = String.valueOf(model.get("modelId"));
        assertTrue(modelId.startsWith("custom:"));
        assertEquals("公共 closeai key", model.get("apiKeyMask"));

        // 2. 管理端模型列表包含自定义模型，用户端看不到
        List<Map<String, Object>> adminModels = models(get("/agent/models", adminHeaders()));
        assertTrue(adminModels.stream().anyMatch(item -> modelId.equals(item.get("id"))));
        List<Map<String, Object>> userModels = models(get("/agent/models", userHeaders()));
        assertFalse(userModels.stream().anyMatch(item -> modelId.equals(item.get("id"))));

        // 3. 管理端探测该模型，真实调用模型服务
        Map<String, Object> probeResponse = get(
                "/agent/admin/models/probe?modelId=" + modelId, adminHeaders());
        assertEquals(0, code(probeResponse));
        List<Map<String, Object>> results = (List<Map<String, Object>>) probeResponse.get("results");
        assertEquals(1, results.size());
        assertEquals(true, results.get(0).get("ok"), String.valueOf(results.get(0).get("error")));

        // 4. 普通用户不能探测模型
        assertEquals(15001, code(get(
                "/agent/admin/models/probe?modelId=" + modelId, userHeaders())));

        // 5. 删除自定义模型
        assertEquals(0, code(delete("/agent/settings/models/" + id, adminHeaders())));
        assertNull(customModelMapper.selectById(id));
    }

    /**
     * 测试自定义模型 baseUrl 的 HTTPS 和内网地址安全校验。
     */
    @Test
    void customModelRejectsUnsafeUrls() {
        // 1. http 地址不满足 require-https
        Map<String, Object> httpRequest = new HashMap<>();
        httpRequest.put("displayName", "HTTP 模型");
        httpRequest.put("modelName", "deepseek-v4.1-flash");
        httpRequest.put("baseUrl", "http://api.openai-proxy.org/v1");
        httpRequest.put("useSharedKey", true);
        assertEquals(15007, code(post("/agent/settings/models", httpRequest, adminHeaders())));

        // 2. 内网地址被拒绝
        Map<String, Object> privateRequest = new HashMap<>();
        privateRequest.put("displayName", "内网模型");
        privateRequest.put("modelName", "deepseek-v4.1-flash");
        privateRequest.put("baseUrl", "https://127.0.0.1/v1");
        privateRequest.put("useSharedKey", true);
        assertEquals(15007, code(post("/agent/settings/models", privateRequest, adminHeaders())));
    }

    /**
     * 测试 apiKey 的 AES-GCM 加解密、随机 IV 和掩码。
     */
    @Test
    void cryptoAndMaskWork() {
        // 1. 加密后可解密
        String plain = "sk-1234567890abcd";
        String cipher = cryptoService.encrypt(plain);
        assertTrue(cipher.startsWith("v1:"));
        assertNotEquals(plain, cipher);
        assertEquals(plain, cryptoService.decrypt(cipher));

        // 2. 每次加密 IV 不同
        assertNotEquals(cipher, cryptoService.encrypt(plain));

        // 3. 掩码只保留前 3 位和后 4 位
        assertEquals("sk-****abcd", cryptoService.mask(plain));
        assertEquals("****", cryptoService.mask("short"));
        assertThrows(AgentException.class, () -> cryptoService.encrypt(" "));
    }

    /**
     * 测试 URL 安全校验器放行公网 HTTPS，拒绝 HTTP、本机和内网地址。
     */
    @Test
    void urlValidatorRejectsPrivateAddresses() {
        AiProperties.CustomModel config = aiProperties.getSecurity().getCustomModel();

        // 1. 公网 https 地址通过
        urlSafetyValidator.validate("https://api.openai-proxy.org/v1", config);

        // 2. 本机、内网和 http 地址被拒绝
        assertThrows(AgentException.class,
                () -> urlSafetyValidator.validate("http://api.openai-proxy.org/v1", config));
        assertThrows(AgentException.class,
                () -> urlSafetyValidator.validate("https://localhost/v1", config));
        assertThrows(AgentException.class,
                () -> urlSafetyValidator.validate("https://<your-vm-host-ip>/v1", config));
    }

    /**
     * 测试模型目录的可见性、默认模型查找和视觉模型选择。
     */
    @Test
    void modelCatalogVisibilityAndVisionModel() {
        // 1. 内置模型所有人都能看到
        assertFalse(modelCatalog.list(TEST_USER).isEmpty());
        assertNotNull(modelCatalog.find(TEST_USER, "deepseek-v4.1-flash"));
        assertNotNull(modelCatalog.defaultModel(TEST_USER));
        assertNotNull(modelCatalog.visionModel(TEST_USER));

        // 2. 默认模型与配置一致
        assertEquals(aiProperties.getDefaultModel(),
                modelCatalog.defaultModel(TEST_USER).getId());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> models(Map<String, Object> response) {
        Object models = response.get("models");
        assertNotNull(models, "响应中没有 models");
        return (List<Map<String, Object>>) models;
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
