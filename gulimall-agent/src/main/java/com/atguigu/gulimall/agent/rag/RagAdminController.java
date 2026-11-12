package com.atguigu.gulimall.agent.rag;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.common.utils.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 管理接口：只有管理员能重建索引。
 */
@RestController
@RequestMapping("/agent/admin/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final RagIndexService ragIndexService;

    /**
     * 管理员重建知识库索引，并返回入库的文档片段数。
     *
     * @param principal 当前请求身份，仅管理员可执行
     * @return 包含入库片段数的统一响应
     */
    @PostMapping("/rebuild")
    public R rebuild(Principal principal) {
        // 1. 只有管理端身份可以重建
        if (!principal.isAdmin()) {
            throw new AgentException(AgentErrorCode.NOT_LOGIN, "只有管理员可以重建知识库");
        }

        // 2. 执行重建并返回片段数
        int segments = ragIndexService.rebuild();
        return R.ok().put("segments", segments);
    }

    /**
     * 管理员查看知识库是否已完成入库。
     *
     * @param principal 当前请求身份，仅管理员可执行
     * @return 包含入库状态标记的统一响应
     */
    @GetMapping("/status")
    public R status(Principal principal) {
        if (!principal.isAdmin()) {
            throw new AgentException(AgentErrorCode.NOT_LOGIN, "只有管理员可以查看知识库状态");
        }
        return R.ok().put("indexed", ragIndexService.isIndexed());
    }
}
