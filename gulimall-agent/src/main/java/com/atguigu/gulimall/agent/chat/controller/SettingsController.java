package com.atguigu.gulimall.agent.chat.controller;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.model.ModelFactory;
import com.atguigu.gulimall.agent.settings.dto.CustomModelRequest;
import com.atguigu.gulimall.agent.settings.dto.SettingsUpdateRequest;
import com.atguigu.gulimall.agent.settings.service.SettingsService;
import com.atguigu.gulimall.common.utils.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 聊天窗设置：颜色、默认模型、流式开关、自定义提示词、自定义模型。
 */
@RestController
@RequestMapping("/agent/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final ModelFactory modelFactory;

    /**
     * 查询聊天窗设置，同时返回自定义模型列表（密钥只返回掩码）。
     *
     * @param principal 当前登录身份
     * @return 统一响应，setting 为设置内容，customModels 为自定义模型视图
     */
    @GetMapping
    public R get(Principal principal) {
        // 1. 返回设置 + 自定义模型（只有掩码）
        return R.ok()
                .put("setting", settingsService.getOrCreate(principal))
                .put("customModels", settingsService.listCustomModelViews(principal));
    }

    /**
     * 更新聊天窗设置，如颜色、默认模型、流式开关与自定义提示词。
     *
     * @param principal 当前登录身份
     * @param request   设置更新请求
     * @return 统一响应，setting 为更新后的设置内容
     */
    @PutMapping
    public R update(Principal principal, @RequestBody SettingsUpdateRequest request) {
        return R.ok().put("setting", settingsService.update(principal, request));
    }

    /**
     * 新增一个自定义模型，并清理新模型 id 对应的缓存。
     *
     * @param principal 当前登录身份
     * @param request   自定义模型请求，包含模型名称、地址、密钥等
     * @return 统一响应，model 为创建后的自定义模型视图
     */
    @PostMapping("/models")
    public R addModel(Principal principal, @RequestBody CustomModelRequest request) {
        var view = settingsService.createCustomModel(principal, request);
        // 1. 新模型 id 是新生成的，不存在旧缓存；这里调用只为语义清晰
        modelFactory.evict(view.getModelId());
        return R.ok().put("model", view);
    }

    /**
     * 删除一个自定义模型，并清理所有自定义模型的缓存。
     *
     * @param principal 当前登录身份
     * @param id        自定义模型的数据库 id
     * @return 统一响应，删除成功不返回业务数据
     */
    @DeleteMapping("/models/{id}")
    public R deleteModel(Principal principal, @PathVariable("id") Long id) {
        settingsService.deleteCustomModel(principal, id);
        // 2. 自定义模型 id 前缀固定为 custom:，删除后清掉所有自定义模型缓存
        modelFactory.evictPrefix("custom:");
        return R.ok();
    }
}
