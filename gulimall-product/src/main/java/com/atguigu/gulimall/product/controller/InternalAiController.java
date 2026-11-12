package com.atguigu.gulimall.product.controller;

import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.common.web.InternalAiControllerSupport;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.service.BrandService;
import com.atguigu.gulimall.product.service.CategoryBrandRelationService;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.service.SkuInfoService;
import com.atguigu.gulimall.product.service.SpuInfoService;
import com.atguigu.gulimall.product.vo.Catelog2Vo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 gulimall-agent 用的商品只读接口。
 *
 * <p>只暴露查询，不提供新增/修改/删除；所有请求必须带 X-Internal-Token。
 * 工具返回给 AI 的数据尽量精简，避免把整个商品详情塞爆上下文。
 */
@RestController
@RequestMapping("/internal/ai/product")
@RequiredArgsConstructor
public class InternalAiController extends InternalAiControllerSupport {

    private final SkuInfoService skuInfoService;
    private final SpuInfoService spuInfoService;
    private final CategoryService categoryService;
    private final BrandService brandService;
    private final CategoryBrandRelationService categoryBrandRelationService;

    /**
     * 按关键字/分类/品牌/价格区间查 SKU。
     */
    @GetMapping("/search")
    public R search(HttpServletRequest request,
                    @RequestParam(value = "key", required = false) String key,
                    @RequestParam(value = "catelogId", required = false) String catelogId,
                    @RequestParam(value = "brandId", required = false) String brandId,
                    @RequestParam(value = "min", required = false) String min,
                    @RequestParam(value = "max", required = false) String max,
                    @RequestParam(value = "page", defaultValue = "1") String page,
                    @RequestParam(value = "limit", defaultValue = "10") String limit) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 复用后台 SKU 条件查询，参数名与 queryPageByCondition 保持一致
        Map<String, Object> params = new HashMap<>();
        params.put("key", key);
        params.put("catelogId", catelogId);
        params.put("brandId", brandId);
        params.put("min", min);
        params.put("max", max);
        params.put("page", page);
        params.put("limit", limit);
        PageUtils result = skuInfoService.queryPageByCondition(params);
        return R.ok().put("page", result);
    }

    /**
     * SKU 详情：包含图片、销售属性、规格参数、库存、分类路径。
     */
    @GetMapping("/sku/{skuId}")
    public R sku(HttpServletRequest request, @PathVariable("skuId") Long skuId) {
        // 1. 校验凭证
        verifyInternal(request);

        // 2. 复用商品详情页的聚合查询
        return R.ok().put("data", skuInfoService.item(skuId));
    }

    /**
     * 分类树（一级 + 二级）。
     *
     * <p>首页用的 getCatelogJson() 只有二级/三级，一级名称在 key 里丢了；
     * 完整三级树又有几千个节点，塞给模型会直接截断。
     * 这里返回“一级 id/name + 二级 id/name”，三级让模型按关键字搜索。
     */
    @GetMapping("/categories")
    public R categories(HttpServletRequest request) {
        // 1. 校验凭证
        verifyInternal(request);

        // 2. 一级分类来自分类表，保证名称完整
        List<CategoryEntity> level1List = categoryService.getLevel1CategoryList();
        Map<String, List<Catelog2Vo>> catelogJson = categoryService.getCatelogJson();

        // 3. 组装精简树：只保留 id/name/children
        List<Map<String, Object>> tree = new ArrayList<>();
        for (CategoryEntity level1 : level1List) {
            Map<String, Object> node1 = new LinkedHashMap<>();
            node1.put("id", level1.getCatId());
            node1.put("name", level1.getName());

            List<Map<String, Object>> children = new ArrayList<>();
            for (Catelog2Vo level2 : catelogJson.getOrDefault(String.valueOf(level1.getCatId()), List.of())) {
                Map<String, Object> node2 = new LinkedHashMap<>();
                node2.put("id", level2.getId());
                node2.put("name", level2.getName());
                children.add(node2);
            }
            node1.put("children", children);
            tree.add(node1);
        }
        return R.ok().put("data", tree);
    }

    /**
     * 某个分类下的品牌。
     */
    @GetMapping("/brands")
    public R brands(HttpServletRequest request,
                    @RequestParam(value = "categoryId", required = false) Long categoryId) {
        // 1. 校验凭证
        verifyInternal(request);

        // 2. 没传分类就返回全部品牌（最多 100 个，避免过大）
        if (categoryId == null) {
            return R.ok().put("data", brandService.list().stream().limit(100).toList());
        }
        return R.ok().put("data", categoryBrandRelationService.getBrandsByCatId(categoryId));
    }

    /**
     * SPU 下的所有 SKU。
     */
    @GetMapping("/spu/{spuId}/skus")
    public R spuSkus(HttpServletRequest request, @PathVariable("spuId") Long spuId) {
        // 1. 校验凭证
        verifyInternal(request);

        // 2. 复用现有查询
        return R.ok().put("data", skuInfoService.getSkuBySpuId(spuId));
    }

    /**
     * SPU 基本信息。
     */
    @GetMapping("/spu/{spuId}")
    public R spu(HttpServletRequest request, @PathVariable("spuId") Long spuId) {
        // 1. 校验凭证
        verifyInternal(request);

        // 2. 返回 SPU 实体
        return R.ok().put("data", spuInfoService.getById(spuId));
    }
}
