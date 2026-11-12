package com.atguigu.gulimall.product.feign;

import com.atguigu.gulimall.api.product.SkuReductionTo;
import com.atguigu.gulimall.api.product.SpuBoundTo;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * @ClassName: couponFeignClient
 * @Package: com.atguigu.gulimall.product.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/11 - 19:33
 * @Version: v1.0
 */
@FeignClient("gulimall-coupon")
public interface CouponFeignClient {

    /**
     *1、CouponFeginService.saveSpuBounds(spuBoudnTo);
     *  1)、@RequestBody 将这个对象转化为json
     *  2)、找到gulimall-coupon服务，给coupon/spubounds/save发送请求
     *      将上一步转的json放在请求体位置，发送数据
     *  3)、对方服务接受请求，请求体里面有json数据
     *      public R save(@RequestBody SpuBoundsEntity spuBounds);
     *      将请求体的json转化为SpuBoundsEntity;
     * 只要json数据模型是兼容的。双方无需使用同一个to
     *@param:[spuBoundTo]
     *@return:com.atguigu.gulimall.common.utils.R
     *@date: 2021/8/18 1:28
     */
    @PostMapping("coupon/spubounds/save")
    R saveSpuBounds(@RequestBody SpuBoundTo spuBoundTo);

    @PostMapping("coupon/skufullreduction/saveInfo")
    R saveSkuReduction(@RequestBody SkuReductionTo skuReductionTo);
}