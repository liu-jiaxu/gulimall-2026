package com.atguigu.gulimall.product.feign;

import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * @ClassName: CartFeignClient
 * @Package: com.atguigu.gulimall.product.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/8 - 21:45
 * @Version: v1.0
 */
@FeignClient("gulimall-cart")
public interface CartFeignClient {

    /**
     * 查购物车商品类型数量
     *
     * @return 购物车商品类型数量
     */
    @GetMapping("/cartCount")
    Long getCartCount();

    /**
     * 购物车商品明细，顶部 mini 购物车展示前两件。
     */
    @GetMapping("/currentUserCartItems")
    List<OrderItemVo> getCurrentCartItems();

}
