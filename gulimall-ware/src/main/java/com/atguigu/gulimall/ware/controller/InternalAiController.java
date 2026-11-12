package com.atguigu.gulimall.ware.controller;

import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.common.web.InternalAiControllerSupport;
import com.atguigu.gulimall.ware.service.WareInfoService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 给 gulimall-agent 用的库存/仓库只读接口。
 */
@RestController
@RequestMapping("/internal/ai/ware")
@RequiredArgsConstructor
public class InternalAiController extends InternalAiControllerSupport {

    private final WareSkuService wareSkuService;
    private final WareInfoService wareInfoService;

    /**
     * 单个 SKU 的总可用库存。
     */
    @GetMapping("/stock/{skuId}")
    public R stock(HttpServletRequest request, @PathVariable("skuId") Long skuId) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 复用现有库存汇总（所有仓库的可用库存之和）
        return R.ok().put("data", wareSkuService.getStockNums(List.of(skuId)));
    }

    /**
     * 批量查库存。
     */
    @GetMapping("/stock")
    public R stockBatch(HttpServletRequest request, @RequestParam("skuIds") String skuIds) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 逗号分隔转 Long
        List<Long> ids = Arrays.stream(skuIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
        return R.ok().put("data", wareSkuService.getStockNums(ids))
                .put("hasStock", wareSkuService.getSkuHasStock(ids));
    }

    /**
     * 仓库列表。
     */
    @GetMapping("/warehouses")
    public R warehouses(HttpServletRequest request) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 仓库数量很少，直接全量返回
        return R.ok().put("data", wareInfoService.list());
    }
}
