package com.atguigu.gulimall.agent.chat.controller;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.model.ModelCatalog;
import com.atguigu.gulimall.agent.model.ModelFactory;
import com.atguigu.gulimall.common.utils.R;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端逐个探测模型是否可用。
 *
 * <p>closeai 上的模型名可能随时调整，联调时先用它确认哪个模型能通：
 * <pre>
 *   GET /gulimall/agent/admin/models/probe?modelId=gpt-6.1-sol
 * </pre>
 * 不传 modelId 会逐个探测全部模型（较慢）。
 */
@RestController
@RequestMapping("/agent/admin/models")
@RequiredArgsConstructor
public class AdminModelProbeController {

    private final ModelCatalog modelCatalog;
    private final ModelFactory modelFactory;

    /**
     * 探测指定模型或全部模型是否可用：向模型发送“只回复OK”，记录是否成功、回复内容与耗时。
     *
     * @param principal 当前登录身份，非管理员会直接拒绝
     * @param modelId   要探测的模型 id；为空时逐个探测当前身份可见的全部模型
     * @return 统一响应，results 里是每个模型的探测结果（id、ok、reply/error、costMs）
     */
    @GetMapping("/probe")
    public R probe(Principal principal,
                   @RequestParam(value = "modelId", required = false) String modelId) {
        // 1. 只有管理员能触发（会消耗真实 token）
        if (!principal.isAdmin()) {
            throw new AgentException(AgentErrorCode.NOT_LOGIN, "只有管理员可以探测模型");
        }

        // 2. 指定模型就只测一个，否则测全部
        List<AiModelDefinition> models = new ArrayList<>();
        if (StringUtils.hasText(modelId)) {
            models.add(modelCatalog.find(principal, modelId));
        } else {
            models.addAll(modelCatalog.list(principal));
        }

        // 3. 逐个调用，失败只记录错误，不中断其它模型
        List<Map<String, Object>> results = new ArrayList<>();
        for (AiModelDefinition definition : models) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", definition.getId());
            long start = System.currentTimeMillis();
            try {
                ChatModel model = modelFactory.chat(definition);
                String reply = model.chat("只回复OK");
                row.put("ok", true);
                row.put("reply", reply);
            } catch (Exception ex) {
                row.put("ok", false);
                row.put("error", ex.getMessage());
            }
            row.put("costMs", System.currentTimeMillis() - start);
            results.add(row);
        }
        return R.ok().put("results", results);
    }
}
