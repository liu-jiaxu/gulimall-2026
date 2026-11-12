package com.atguigu.gulimall.agent.model;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.settings.service.SettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模型目录：把 yaml 内置模型和用户自定义模型合并成页面看到的一份列表。
 */
@Component
@RequiredArgsConstructor
public class ModelCatalog {

    private final AiProperties properties;
    private final SettingsService settingsService;

    private List<AiModelDefinition> builtInModels = List.of();

    /**
     * 初始化内置模型列表：为 yaml 中的每个模型补齐默认值并按排序值排列。
     */
    @PostConstruct
    public void init() {
        // 1. 启动时把 yaml 里的内置模型补齐默认值，之后不再修改
        List<AiModelDefinition> initialized = new ArrayList<>();
        for (AiModelDefinition definition : properties.getModels()) {
            initialized.add(withDefaults(definition));
        }
        initialized.sort(Comparator.comparingInt(AiModelDefinition::getSort));
        this.builtInModels = List.copyOf(initialized);
    }

    /**
     * 列出当前身份可见且已启用的模型，内置模型所有人可见，自定义模型仅创建者可见。
     *
     * @param principal 当前请求身份，用于判断可见的自定义模型
     * @return 按排序值升序排列的可用模型列表
     */
    public List<AiModelDefinition> list(Principal principal) {
        // 1. 内置模型所有人可见
        List<AiModelDefinition> result = new ArrayList<>(builtInModels);

        // 2. 自定义模型只对创建者可见
        if (principal != null) {
            result.addAll(settingsService.listCustomModels(principal));
        }

        // 3. 过滤禁用项并按 sort 排序
        return result.stream()
                .filter(def -> Boolean.TRUE.equals(def.getEnabled()))
                .sorted(Comparator.comparingInt(AiModelDefinition::getSort))
                .toList();
    }

    /**
     * 按模型 id 在可见列表中查找模型，未指定 id 时使用默认模型。
     *
     * @param principal 当前请求身份，用于确定可见模型范围
     * @param modelId   要查找的模型 id，为空时使用配置中的默认模型
     * @return 匹配到的模型定义
     */
    public AiModelDefinition find(Principal principal, String modelId) {
        // 1. 没传模型就用默认模型
        String id = StringUtils.hasText(modelId) ? modelId : properties.getDefaultModel();

        // 2. 在可见列表里查找
        return list(principal).stream()
                .filter(def -> def.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AgentException(AgentErrorCode.MODEL_NOT_FOUND, "模型不存在：" + id));
    }

    /**
     * 获取配置中的默认模型。
     *
     * @param principal 当前请求身份，用于确定可见模型范围
     * @return 默认模型定义
     */
    public AiModelDefinition defaultModel(Principal principal) {
        return find(principal, properties.getDefaultModel());
    }

    /**
     * 看图用的视觉模型：优先取配置的 vision-model，找不到再取列表里第一个支持视觉的。
     *
     * @param principal 当前请求身份，用于确定可见模型范围
     * @return 用于图片理解的视觉模型定义
     */
    public AiModelDefinition visionModel(Principal principal) {
        List<AiModelDefinition> models = list(principal);
        String configured = properties.getImage().getVisionModel();
        for (AiModelDefinition model : models) {
            if (model.isVision() && model.getId().equals(configured)) {
                return model;
            }
        }
        return models.stream()
                .filter(AiModelDefinition::isVision)
                .findFirst()
                .orElseThrow(() -> new AgentException(AgentErrorCode.MODEL_NOT_FOUND, "没有可用的视觉模型"));
    }

    /**
     * 复制一份模型定义，并把未填写的字段用配置中的 defaults 补齐。
     *
     * @param source yaml 绑定得到的原始模型定义
     * @return 补齐默认值后的新模型定义对象
     */
    private AiModelDefinition withDefaults(AiModelDefinition source) {
        // 1. 新建一份副本，避免直接修改 YAML 绑定出来的原始对象
        AiModelDefinition def = new AiModelDefinition();

        // 2. id：页面/接口使用的唯一标识，启动校验已保证非空，原样保留
        def.setId(source.getId());

        // 3. displayName：页面展示名；没配就用 id，保证下拉框一定有文字
        def.setDisplayName(StringUtils.hasText(source.getDisplayName()) ? source.getDisplayName() : source.getId());

        // 4. modelName：真正发给模型服务的 model 字段；没配就用 id
        def.setModelName(StringUtils.hasText(source.getModelName()) ? source.getModelName() : source.getId());

        // 5. provider：走哪种模型创建策略；没配继承 defaults
        def.setProvider(StringUtils.hasText(source.getProvider()) ? source.getProvider() : properties.getDefaults().getProvider());

        // 6. baseUrl：模型服务地址；没配继承 defaults
        def.setBaseUrl(StringUtils.hasText(source.getBaseUrl()) ? source.getBaseUrl() : properties.getDefaults().getBaseUrl());

        // 7. apiKey：模型服务密钥；没配继承 defaults，避免每个内置模型重复写 key
        def.setApiKey(StringUtils.hasText(source.getApiKey()) ? source.getApiKey() : properties.getDefaults().getApiKey());

        // 8. vision：模型自身是否支持看图，必须每个模型单独配，不能继承
        def.setVision(source.isVision());

        // 9. streaming：是否支持流式输出；null 表示继承 defaults
        def.setStreaming(source.getStreaming() == null ? properties.getDefaults().isStreaming() : source.getStreaming());

        // 10. tools：是否支持 function calling；null 表示继承 defaults
        def.setTools(source.getTools() == null ? properties.getDefaults().isTools() : source.getTools());

        // 11. enabled：是否启用；null 表示继承 defaults
        def.setEnabled(source.getEnabled() == null ? properties.getDefaults().isEnabled() : source.getEnabled());

        // 12. sort：页面排序值，越小越靠前，原样保留
        def.setSort(source.getSort());

        // 13. temperature：采样温度；没配继承 defaults
        def.setTemperature(source.getTemperature() == null ? properties.getDefaults().getTemperature() : source.getTemperature());

        // 14. temperatureSupported：是否允许发送 temperature；
        //     GPT-6.1/Claude 5.5/Kimi 等只接受默认温度，配 false 后工厂不会传 temperature
        def.setTemperatureSupported(source.getTemperatureSupported() == null
                ? Boolean.TRUE : source.getTemperatureSupported());

        // 15. maxRetries：模型调用失败重试次数；没配继承 defaults
        def.setMaxRetries(source.getMaxRetries() == null ? properties.getDefaults().getMaxRetries() : source.getMaxRetries());

        // 16. timeout：非流式调用超时；没配继承 defaults（当前 60 秒）
        def.setTimeout(source.getTimeout() == null ? properties.getDefaults().getTimeout() : source.getTimeout());

        // 17. streamingTimeout：流式调用超时；没配继承 defaults（当前 90 秒）
        def.setStreamingTimeout(source.getStreamingTimeout() == null
                ? properties.getDefaults().getStreamingTimeout() : source.getStreamingTimeout());

        // 18. logRequests：是否打印模型请求日志；没配继承 defaults
        def.setLogRequests(source.getLogRequests() == null ? properties.getDefaults().isLogRequests() : source.getLogRequests());

        // 19. logResponses：是否打印模型响应日志；没配继承 defaults
        def.setLogResponses(source.getLogResponses() == null ? properties.getDefaults().isLogResponses() : source.getLogResponses());

        // 20. custom：是否是用户自定义模型，页面会据此标注
        def.setCustom(source.isCustom());

        // 21. customModelId：自定义模型在 MySQL 里的主键，编辑/删除时用
        def.setCustomModelId(source.getCustomModelId());

        return def;
    }
}
