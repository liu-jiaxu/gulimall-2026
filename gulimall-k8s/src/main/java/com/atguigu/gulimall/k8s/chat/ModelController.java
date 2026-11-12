package com.atguigu.gulimall.k8s.chat;

import com.atguigu.gulimall.k8s.ai.ModelCatalog;
import com.atguigu.gulimall.k8s.auth.SessionUser;
import com.atguigu.gulimall.k8s.config.AiProperties;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 页面模型下拉框数据。
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final AiProperties aiProperties;
    private final ModelCatalog modelCatalog;

    /**
     * 返回当前可用模型和默认模型。
     *
     * @param session 当前 Session
     * @return 模型列表和默认模型 id
     */
    @GetMapping
    public Map<String, Object> list(HttpSession session) {
        // 1. 登录后才能取模型列表，和聊天接口保持一致
        SessionUser.require(session);

        // 2. 只返回展示字段，不暴露 baseUrl/apiKey
        List<ModelView> models = aiProperties.getModels().stream()
                .map(model -> new ModelView(
                        model.getId(),
                        model.displayNameOrId(),
                        model.modelNameOrId()
                ))
                .toList();

        return Map.of(
                "models", models,
                "defaultModel", modelCatalog.defaultModelId()
        );
    }
}
