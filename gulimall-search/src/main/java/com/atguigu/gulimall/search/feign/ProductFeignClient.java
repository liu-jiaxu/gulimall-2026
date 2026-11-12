package com.atguigu.gulimall.search.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * @ClassName: ProductFeignClient
 * @Package: com.atguigu.gulimall.product.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/27 - 19:00
 * @Version: v1.0
 */
@FeignClient("gulimall-product")
public interface ProductFeignClient {

    @GetMapping("/product/attr/info/{attrId}")
    R attrInfo(@PathVariable("attrId") Long attrId);

}
