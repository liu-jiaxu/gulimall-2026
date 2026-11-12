package com.atguigu.gulimall.cart.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @ClassName: ProductFeignClient
 * @Package: com.atguigu.gulimall.cart.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/8 - 10:45
 * @Version: v1.0
 */
@FeignClient("gulimall-product")
public interface ProductFeignClient {

    /**
     * 根据skuId查询sku信息
     * @param skuId
     * @return
     */
    @RequestMapping("/product/skuinfo/info/{skuId}")
    R getSkuInfo(@PathVariable("skuId") Long skuId);

    /**
     * 根据skuId查询pms_sku_sale_attr_value表中的信息
     * @param skuId
     * @return
     */
    @GetMapping(value = "/product/skusaleattrvalue/stringList/{skuId}")
    List<String> getSkuSaleAttrValues(@PathVariable("skuId") Long skuId);

    /**
     * 根据skuId查询当前商品的价格
     * @param skuId
     * @return
     */
    @GetMapping(value = "/product/skuinfo/skuIds/price")
    Map<Long, BigDecimal> getPriceBatch(@RequestParam List<Long> skuIds);

    /**
     * 根据skuId查询当前商品的重量
     * @param skuId
     * @return
     */
    @GetMapping(value = "/product/spuinfo/{skuId}/weight")
    BigDecimal getSkuWeight(@PathVariable Long skuId);

}
