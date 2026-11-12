package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * ES 商品搜索内部接口。
 */
@FeignClient(name = "gulimall-search", path = "/internal/ai/search")
public interface SearchAiClient {

    /**
     * 在 ES 商品索引中按关键字及筛选条件分页搜索商品。
     *
     * @param keyword    搜索关键字，可为空
     * @param catelog3Id 三级分类 id，可为空
     * @param brandId    品牌 id，多个用逗号分隔，可为空
     * @param hasStock   是否只看有货：1 表示是，0 表示否，可为空
     * @param skuPrice   价格区间，格式为 0-3000，可为空
     * @param sort       排序方式，可为空
     * @param page       页码，从 1 开始
     * @return 商品搜索结果的响应数据
     */
    @GetMapping
    Map<String, Object> search(@RequestParam(value = "keyword", required = false) String keyword,
                               @RequestParam(value = "catelog3Id", required = false) Long catelog3Id,
                               @RequestParam(value = "brandId", required = false) String brandId,
                               @RequestParam(value = "hasStock", required = false) Integer hasStock,
                               @RequestParam(value = "skuPrice", required = false) String skuPrice,
                               @RequestParam(value = "sort", required = false) String sort,
                               @RequestParam(value = "page", defaultValue = "1") Integer page);
}
