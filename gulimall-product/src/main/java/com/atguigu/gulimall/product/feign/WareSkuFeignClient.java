package com.atguigu.gulimall.product.feign;

import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * @ClassName: WareSkuFeignClient
 * @Package: com.atguigu.gulimall.product.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 12:01
 * @Version: v1.0
 */
@FeignClient("gulimall-ware")
public interface WareSkuFeignClient {

    @PostMapping("ware/waresku/hasStock")
    Map<Long, Boolean> hasStock(@RequestBody List<Long> skuIds);

    /**
     * 查询 SKU 可发货的仓库及可用库存。
     */
    @PostMapping("ware/wareinfo/shippingFrom")
    List<SkuShippingFromVo> getShippingFrom(@RequestBody List<FareRequestVo.Item> items);

}
