package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 商品只读内部接口。
 */
@FeignClient(name = "gulimall-product", path = "/internal/ai/product")
public interface ProductAiClient {

    /**
     * 按关键字、分类、品牌和价格区间分页搜索商品。
     *
     * @param key       搜索关键字，可为空
     * @param catelogId 三级分类 id，可为空
     * @param brandId   品牌 id，可为空
     * @param min       最低价格，可为空
     * @param max       最高价格，可为空
     * @param page      页码，从 1 开始
     * @param limit     每页数量
     * @return 商品搜索结果的响应数据
     */
    @GetMapping("/search")
    Map<String, Object> search(@RequestParam(value = "key", required = false) String key,
                               @RequestParam(value = "catelogId", required = false) String catelogId,
                               @RequestParam(value = "brandId", required = false) String brandId,
                               @RequestParam(value = "min", required = false) String min,
                               @RequestParam(value = "max", required = false) String max,
                               @RequestParam(value = "page", defaultValue = "1") String page,
                               @RequestParam(value = "limit", defaultValue = "10") String limit);

    /**
     * 查询指定 SKU 的详细信息。
     *
     * @param skuId SKU id
     * @return SKU 详情的响应数据
     */
    @GetMapping("/sku/{skuId}")
    Map<String, Object> sku(@PathVariable("skuId") Long skuId);

    /**
     * 查询商品分类树。
     *
     * @return 商品分类树的响应数据
     */
    @GetMapping("/categories")
    Map<String, Object> categories();

    /**
     * 查询指定分类下的品牌，不传分类时返回常用品牌。
     *
     * @param categoryId 分类 id，可为空
     * @return 品牌列表的响应数据
     */
    @GetMapping("/brands")
    Map<String, Object> brands(@RequestParam(value = "categoryId", required = false) Long categoryId);

    /**
     * 查询指定 SPU 下的所有 SKU。
     *
     * @param spuId SPU id
     * @return SPU 下 SKU 列表的响应数据
     */
    @GetMapping("/spu/{spuId}/skus")
    Map<String, Object> spuSkus(@PathVariable("spuId") Long spuId);

    /**
     * 查询指定 SPU 的基本信息。
     *
     * @param spuId SPU id
     * @return SPU 基本信息的响应数据
     */
    @GetMapping("/spu/{spuId}")
    Map<String, Object> spu(@PathVariable("spuId") Long spuId);
}
