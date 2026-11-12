package com.atguigu.gulimall.order.feign;

import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * @ClassName: CartFeignClient
 * @Package: com.atguigu.gulimall.order.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/14 - 12:31
 * @Version: v1.0
 */
@FeignClient("gulimall-cart")
public interface CartFeignClient {

    /**
     * <p>获取购物车商品
     * <p>远程调用 HTTP 传输的是 JSON 字符串，两边靠 JSON 反序列化映射字段。
     * <p>结果按照字段名称匹配，一致的话就会自动赋值，多余的会自动取消赋值不用管，还有字段类型必须一致，不一致需要手动配置转换规则
     * @return 购物车商品
     */
    @GetMapping(value = "/currentUserCartItems")
    List<OrderItemVo> getCurrentCartItems();

    /**
     * 支付成功后删除购物车里已下单的商品。
     *
     * <p>走 cart 的 /deleteCheckedItems 接口，skuIds 是订单明细里的商品 ID。
     * 删除失败只记日志，不影响支付成功。
     */
    @GetMapping("/deleteCheckedItems")
    void deleteCheckedItems(@RequestParam("skuIds") List<Long> skuIds);

}
