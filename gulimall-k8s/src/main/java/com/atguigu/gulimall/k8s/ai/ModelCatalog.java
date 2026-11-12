package com.atguigu.gulimall.k8s.ai;

import com.atguigu.gulimall.k8s.common.ApiException;
import com.atguigu.gulimall.k8s.config.AiConfig;
import com.atguigu.gulimall.k8s.config.AiConfigRepository;
import com.atguigu.gulimall.k8s.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模型目录：把 YAML 里的模型清单和数据库里的默认模型合起来用。
 */
@Component
@RequiredArgsConstructor
public class ModelCatalog {

    private final AiProperties properties;
    private final AiConfigRepository aiConfigRepository;

    /**
     * 列出所有内置模型，顺序与 YAML 一致。
     *
     * @return 模型清单
     */
    public List<AiProperties.ModelDefinition> list() {
        // 1. 只保留纯文本对话模型，没有图片/工具/RAG 模型
        return properties.getModels();
    }

    /**
     * 按 id 查找模型；id 为空时使用默认模型。
     *
     * @param modelId 模型 id
     * @return 模型定义
     */
    public AiProperties.ModelDefinition find(String modelId) {
        // 1. 没传模型就用数据库/YAML 里的默认模型
        String id = StringUtils.hasText(modelId) ? modelId : defaultModelId();

        // 2. 在清单里查找，找不到给出明确错误
        return properties.getModels().stream()
                .filter(model -> model.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "模型不存在：" + id));
    }

    /**
     * 计算默认模型：
     * 1. 优先用数据库 ai_default_config.default_model；
     * 2. 数据库没配或配了不存在的模型时，用 YAML 的 default-model；
     * 3. 还不行就用清单第一个。
     *
     * @return 默认模型 id
     */
    public String defaultModelId() {
        // 1. 数据库里的默认模型优先，改库不用重启
        AiConfig config = aiConfigRepository.load();
        String dbDefault = config.defaultModel();
        if (StringUtils.hasText(dbDefault) && exists(dbDefault)) {
            return dbDefault;
        }

        // 2. 数据库配置不可用时回退到 YAML
        String yamlDefault = properties.getDefaultModel();
        if (StringUtils.hasText(yamlDefault) && exists(yamlDefault)) {
            return yamlDefault;
        }

        // 3. 最后回退到清单第一个，保证页面一定有模型可选
        return properties.getModels().get(0).getId();
    }

    /**
     * 判断模型 id 是否在清单里。
     *
     * @param modelId 模型 id
     * @return 存在时为 true
     */
    private boolean exists(String modelId) {
        return properties.getModels().stream().anyMatch(model -> model.getId().equals(modelId));
    }
}
