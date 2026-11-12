package com.atguigu.gulimall.cart.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * @ClassName: WareSkuFeignClient
 * @Package: com.atguigu.gulimall.cart.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/8 - 20:36
 * @Version: v1.0
 */
@FeignClient("gulimall-ware")
public interface WareSkuFeignClient {

    /**
     * 查询sku是否有库存
     *
     * @param skuIds
     * @return
     */
    @PostMapping("ware/waresku/hasStock")
    Map<Long, Boolean> hasStock(@RequestBody List<Long> skuIds);

    /**
     * 查询sku库存数量
     *
     * @param skuIds
     * @return
     */
    @PostMapping("ware/waresku/stockNums")
    Map<Long, Long> getStockNums(@RequestBody List<Long> skuIds);

}
