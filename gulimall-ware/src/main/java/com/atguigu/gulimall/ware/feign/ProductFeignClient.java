package com.atguigu.gulimall.ware.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 远程调用商品服务
 *
 * @author liujiaxu
 */
@FeignClient("gulimall-product")
public interface ProductFeignClient {

    /**
     * 查询 sku 信息，用于取 skuName 落库到 wms_ware_sku
     *
     * @param skuId sku id
     * @return R，其中 skuInfo 为 SkuInfoEntity
     */
    @RequestMapping("/product/skuinfo/info/{skuId}")
    R info(@PathVariable("skuId") Long skuId);
}
