package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 库存/仓库只读内部接口。
 */
@FeignClient(name = "gulimall-ware", path = "/internal/ai/ware")
public interface WareAiClient {

    /**
     * 查询指定 SKU 在所有仓库的库存。
     *
     * @param skuId SKU id
     * @return SKU 库存的响应数据
     */
    @GetMapping("/stock/{skuId}")
    Map<String, Object> stock(@PathVariable("skuId") Long skuId);

    /**
     * 批量查询多个 SKU 的库存。
     *
     * @param skuIds SKU id 列表，多个 id 用逗号分隔
     * @return 批量库存的响应数据
     */
    @GetMapping("/stock")
    Map<String, Object> stockBatch(@RequestParam("skuIds") String skuIds);

    /**
     * 查询仓库列表。
     *
     * @return 仓库列表的响应数据
     */
    @GetMapping("/warehouses")
    Map<String, Object> warehouses();
}
