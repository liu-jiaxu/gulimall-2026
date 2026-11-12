package com.atguigu.gulimall.agent.chat.controller;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.dto.ModelView;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.model.ModelCatalog;
import com.atguigu.gulimall.common.utils.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 页面模型下拉框数据。
 */
@RestController
@RequestMapping("/agent/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelCatalog modelCatalog;

    /**
     * 返回页面模型下拉框数据：内置模型加当前用户的自定义模型。
     *
     * @param principal 当前登录身份
     * @return 统一响应，models 里是页面展示用的模型视图
     */
    @GetMapping
    public R list(Principal principal) {
        // 1. 内置 + 当前用户自定义模型
        List<ModelView> views = modelCatalog.list(principal).stream().map(this::toView).toList();
        return R.ok().put("models", views);
    }

    /**
     * 把模型定义转换成页面展示用的模型视图，只保留展示字段，不暴露 baseUrl 和 apiKey。
     *
     * @param definition 模型定义
     * @return 页面展示用的模型视图
     */
    private ModelView toView(AiModelDefinition definition) {
        // 1. 页面只需要展示字段，不返回 baseUrl/apiKey
        ModelView view = new ModelView();
        view.setId(definition.getId());
        view.setDisplayName(definition.getDisplayName());
        view.setModelName(definition.getModelName());
        view.setVision(definition.isVision());
        view.setStreaming(Boolean.TRUE.equals(definition.getStreaming()));
        view.setTools(Boolean.TRUE.equals(definition.getTools()));
        view.setCustom(definition.isCustom());
        return view;
    }
}
