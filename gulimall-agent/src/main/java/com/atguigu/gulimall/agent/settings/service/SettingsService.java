package com.atguigu.gulimall.agent.settings.service;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.settings.crypto.AesGcmCryptoService;
import com.atguigu.gulimall.agent.settings.dto.CustomModelRequest;
import com.atguigu.gulimall.agent.settings.dto.CustomModelView;
import com.atguigu.gulimall.agent.settings.dto.SettingsUpdateRequest;
import com.atguigu.gulimall.agent.settings.entity.AiCustomModel;
import com.atguigu.gulimall.agent.settings.entity.AiUserSetting;
import com.atguigu.gulimall.agent.settings.mapper.AiCustomModelMapper;
import com.atguigu.gulimall.agent.settings.mapper.AiUserSettingMapper;
import com.atguigu.gulimall.agent.settings.validation.UrlSafetyValidator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用户设置与自定义模型的读写。
 *
 * <p>聊天主链路即使数据库表还没建也应当能跑：
 * 读设置失败时退回一份内存默认值，只记录警告；
 * 真正保存设置/自定义模型时再报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final AiUserSettingMapper settingMapper;
    private final AiCustomModelMapper customModelMapper;
    private final AesGcmCryptoService cryptoService;
    private final UrlSafetyValidator urlSafetyValidator;
    private final AiProperties properties;

    /**
     * 读取设置，没有就创建默认行。
     *
     * @param principal 当前登录身份，用于定位唯一的设置行
     * @return 已存在的设置，读取失败时返回内存中的默认设置
     */
    public AiUserSetting getOrCreate(Principal principal) {
        try {
            // 1. 按身份查唯一一行
            AiUserSetting setting = settingMapper.selectOne(
                    Wrappers.<AiUserSetting>lambdaQuery()
                            .eq(AiUserSetting::getPrincipalType, principal.type().name())
                            .eq(AiUserSetting::getPrincipalId, principal.id())
            );
            if (setting != null) {
                return setting;
            }

            // 2. 首次使用：写入默认值
            setting = defaultSetting(principal);
            settingMapper.insert(setting);
            return setting;
        } catch (Exception ex) {
            // 3. 表未初始化/数据库暂时不可用时，聊天仍可用，只是没有个性化设置
            log.warn("读取 AI 设置失败，使用默认值：{}", ex.getMessage());
            return defaultSetting(principal);
        }
    }

    /**
     * 更新当前身份的 AI 设置，只覆盖请求中非空的字段。
     *
     * @param principal 当前登录身份
     * @param request   前端提交的设置字段，null 表示保持原值
     * @return 更新后的设置
     */
    public AiUserSetting update(Principal principal, SettingsUpdateRequest request) {
        // 1. 先拿到当前行（没有会自动创建）
        AiUserSetting setting = getOrCreate(principal);
        if (setting.getId() == null) {
            throw new AgentException(AgentErrorCode.AI_NOT_CONFIGURED, "AI 设置表未初始化，请先执行 sql/gulimall_ai.sql");
        }

        // 2. 逐项校验并覆盖，null 表示前端没改这一项
        if (request.getThemeColor() != null) {
            String color = request.getThemeColor().trim();
            if (!color.matches("^#[0-9a-fA-F]{6}$")) {
                throw new AgentException(AgentErrorCode.MESSAGE_INVALID, "主题色必须是 #RRGGBB 格式");
            }
            setting.setThemeColor(color);
        }
        if (request.getStreamEnabled() != null) {
            setting.setStreamEnabled(request.getStreamEnabled());
        }
        if (request.getDefaultModel() != null) {
            setting.setDefaultModel(request.getDefaultModel().trim());
        }
        if (request.getCustomPrompt() != null) {
            String prompt = request.getCustomPrompt().trim();
            int max = properties.getConversation().getMaxCustomPromptChars();
            if (prompt.length() > max) {
                throw new AgentException(AgentErrorCode.MESSAGE_INVALID, "自定义提示词不能超过 " + max + " 字");
            }
            setting.setCustomPrompt(prompt);
        }
        if (request.getPetPositionX() != null) {
            setting.setPetPositionX(request.getPetPositionX());
        }
        if (request.getPetPositionY() != null) {
            setting.setPetPositionY(request.getPetPositionY());
        }

        // 3. 写回数据库
        settingMapper.updateById(setting);
        return setting;
    }

    /**
     * 某个身份可用的自定义模型（已解密，仅供后端构建模型使用）。
     *
     * @param principal 当前登录身份
     * @return 已启用的自定义模型定义列表，单个模型解密失败会被跳过
     */
    public List<AiModelDefinition> listCustomModels(Principal principal) {
        List<AiCustomModel> models;
        try {
            models = customModelMapper.selectList(
                    Wrappers.<AiCustomModel>lambdaQuery()
                            .eq(AiCustomModel::getPrincipalType, principal.type().name())
                            .eq(AiCustomModel::getPrincipalId, principal.id())
                            .eq(AiCustomModel::getEnabled, true)
                            .orderByAsc(AiCustomModel::getId)
            );
        } catch (Exception ex) {
            log.warn("读取自定义模型失败：{}", ex.getMessage());
            return List.of();
        }

        List<AiModelDefinition> result = new ArrayList<>();
        for (AiCustomModel model : models) {
            try {
                result.add(toDefinition(model));
            } catch (Exception ex) {
                // 1. 单个模型密钥坏了不影响其它模型
                log.warn("自定义模型 {} 解密失败，已跳过：{}", model.getModelId(), ex.getMessage());
            }
        }
        return result;
    }

    /**
     * 查询当前身份的全部自定义模型，返回不含明文密钥的视图。
     *
     * @param principal 当前登录身份
     * @return 自定义模型视图列表，查询失败时返回空列表
     */
    public List<CustomModelView> listCustomModelViews(Principal principal) {
        List<AiCustomModel> models;
        try {
            models = customModelMapper.selectList(
                    Wrappers.<AiCustomModel>lambdaQuery()
                            .eq(AiCustomModel::getPrincipalType, principal.type().name())
                            .eq(AiCustomModel::getPrincipalId, principal.id())
                            .orderByAsc(AiCustomModel::getId)
            );
        } catch (Exception ex) {
            return List.of();
        }

        List<CustomModelView> views = new ArrayList<>();
        for (AiCustomModel model : models) {
            CustomModelView view = new CustomModelView();
            view.setId(model.getId());
            view.setModelId(model.getModelId());
            view.setDisplayName(model.getDisplayName());
            view.setProvider(model.getProvider());
            view.setModelName(model.getModelName());
            view.setBaseUrl(model.getBaseUrl());
            view.setApiKeyMask(model.getApiKeyMask());
            view.setUseSharedKey(model.getUseSharedKey());
            view.setSupportsVision(model.getSupportsVision());
            view.setSupportsStreaming(model.getSupportsStreaming());
            view.setEnabled(model.getEnabled());
            views.add(view);
        }
        return views;
    }

    /**
     * 新建自定义模型，校验与加密后写入数据库。
     *
     * @param principal 当前登录身份
     * @param request   前端提交的模型信息
     * @return 新建模型对应的视图，不包含明文 apiKey
     */
    public CustomModelView createCustomModel(Principal principal, CustomModelRequest request) {
        AiProperties.CustomModel config = properties.getSecurity().getCustomModel();

        // 1. 功能开关与数量上限
        if (!config.isEnabled()) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "自定义模型功能已关闭");
        }
        long count = customModelMapper.selectCount(
                Wrappers.<AiCustomModel>lambdaQuery()
                        .eq(AiCustomModel::getPrincipalType, principal.type().name())
                        .eq(AiCustomModel::getPrincipalId, principal.id())
        );
        if (count >= config.getMaxPerPrincipal()) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID,
                    "最多只能添加 " + config.getMaxPerPrincipal() + " 个自定义模型");
        }

        // 2. 字段校验
        String provider = StringUtils.hasText(request.getProvider()) ? request.getProvider().trim() : "openai-compatible";
        if (!config.getAllowedProviders().contains(provider)) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "不支持的模型协议：" + provider);
        }
        String displayName = required(request.getDisplayName(), "模型名称", 64);
        String modelName = required(request.getModelName(), "modelName", 128);
        String baseUrl = required(request.getBaseUrl(), "baseUrl", 512);
        urlSafetyValidator.validate(baseUrl, config);

        double temperature = request.getTemperature() == null ? 0.7 : request.getTemperature();
        if (temperature < 0 || temperature > 2) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "temperature 必须在 0~2 之间");
        }
        int timeoutSeconds = request.getTimeoutSeconds() == null ? 10 : request.getTimeoutSeconds();
        if (timeoutSeconds < 5 || timeoutSeconds > 120) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "超时时间必须在 5~120 秒之间");
        }

        boolean useSharedKey = Boolean.TRUE.equals(request.getUseSharedKey());
        String cipher = null;
        String mask;
        if (useSharedKey) {
            // 3. 用公共 key：不存用户 key，掩码固定提示
            mask = "公共 closeai key";
        } else {
            String apiKey = required(request.getApiKey(), "apiKey", 512);
            cipher = cryptoService.encrypt(apiKey);
            mask = cryptoService.mask(apiKey);
        }

        // 4. 入库
        AiCustomModel entity = new AiCustomModel();
        entity.setPrincipalType(principal.type().name());
        entity.setPrincipalId(principal.id());
        entity.setModelId("custom:" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setDisplayName(displayName);
        entity.setProvider(provider);
        entity.setModelName(modelName);
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyCipher(cipher);
        entity.setApiKeyMask(mask);
        entity.setUseSharedKey(useSharedKey);
        entity.setSupportsVision(Boolean.TRUE.equals(request.getSupportsVision()));
        entity.setSupportsStreaming(request.getSupportsStreaming() == null || request.getSupportsStreaming());
        entity.setTemperature(BigDecimal.valueOf(temperature));
        entity.setTimeoutSeconds(timeoutSeconds);
        entity.setEnabled(true);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        customModelMapper.insert(entity);

        CustomModelView view = new CustomModelView();
        view.setId(entity.getId());
        view.setModelId(entity.getModelId());
        view.setDisplayName(displayName);
        view.setProvider(provider);
        view.setModelName(modelName);
        view.setBaseUrl(baseUrl);
        view.setApiKeyMask(mask);
        view.setUseSharedKey(useSharedKey);
        view.setSupportsVision(entity.getSupportsVision());
        view.setSupportsStreaming(entity.getSupportsStreaming());
        view.setEnabled(true);
        return view;
    }

    /**
     * 删除当前身份自己的自定义模型。
     *
     * @param principal 当前登录身份
     * @param id        待删除模型的数据库主键
     */
    public void deleteCustomModel(Principal principal, Long id) {
        // 1. 只能删自己的模型
        AiCustomModel model = customModelMapper.selectById(id);
        if (model == null
                || !model.getPrincipalType().equals(principal.type().name())
                || !model.getPrincipalId().equals(principal.id())) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, "模型不存在或无权删除");
        }
        customModelMapper.deleteById(id);
    }

    /**
     * 把数据库中的自定义模型转换为统一的模型定义。
     *
     * @param model 自定义模型实体
     * @return 供模型工厂和聊天链路使用的模型定义
     */
    private AiModelDefinition toDefinition(AiCustomModel model) {
        // 1. 公共 key 或解密用户 key
        String apiKey = Boolean.TRUE.equals(model.getUseSharedKey())
                ? properties.getDefaults().getApiKey()
                : cryptoService.decrypt(model.getApiKeyCipher());

        // 2. 转成统一的模型定义，后面的工厂/页面完全复用内置模型逻辑
        AiModelDefinition definition = new AiModelDefinition();
        definition.setId(model.getModelId());
        definition.setDisplayName(model.getDisplayName());
        definition.setModelName(model.getModelName());
        definition.setProvider(model.getProvider());
        definition.setBaseUrl(model.getBaseUrl());
        definition.setApiKey(apiKey);
        definition.setVision(Boolean.TRUE.equals(model.getSupportsVision()));
        definition.setStreaming(model.getSupportsStreaming() == null || model.getSupportsStreaming());
        definition.setTools(true);
        definition.setEnabled(model.getEnabled() == null || model.getEnabled());
        definition.setSort(1000 + model.getId().intValue());
        definition.setTemperature(model.getTemperature() == null ? 0.7 : model.getTemperature().doubleValue());
        definition.setTemperatureSupported(true);
        definition.setMaxRetries(properties.getDefaults().getMaxRetries());
        definition.setTimeout(Duration.ofSeconds(model.getTimeoutSeconds() == null ? 10 : model.getTimeoutSeconds()));
        definition.setStreamingTimeout(properties.getDefaults().getStreamingTimeout());
        definition.setLogRequests(true);
        definition.setLogResponses(true);
        definition.setCustom(true);
        definition.setCustomModelId(model.getId());
        return definition;
    }

    /**
     * 构建某个身份的默认设置对象。
     *
     * @param principal 当前登录身份
     * @return 带默认主题色、默认模型等内容的设置对象
     */
    private AiUserSetting defaultSetting(Principal principal) {
        AiUserSetting setting = new AiUserSetting();
        setting.setPrincipalType(principal.type().name());
        setting.setPrincipalId(principal.id());
        // 管理端默认用 Element UI 主题青色，用户端保持商城红
        setting.setThemeColor(principal.isAdmin() ? "#0BB2D4" : "#e4393c");
        setting.setStreamEnabled(true);
        setting.setDefaultModel(properties.getDefaultModel());
        setting.setCreatedAt(LocalDateTime.now());
        setting.setUpdatedAt(LocalDateTime.now());
        return setting;
    }

    /**
     * 校验必填字段并返回去除首尾空白后的值。
     *
     * @param value     待校验的字段值
     * @param field     字段名称，用于拼接错误提示
     * @param maxLength 允许的最大字符数
     * @return 去除首尾空白后的字段值
     */
    private String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new AgentException(AgentErrorCode.CUSTOM_MODEL_INVALID, field + " 不能超过 " + maxLength + " 字符");
        }
        return trimmed;
    }
}
