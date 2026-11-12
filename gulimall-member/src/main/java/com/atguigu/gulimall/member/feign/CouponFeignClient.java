package com.atguigu.gulimall.member.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * @ClassName: CouponFeignClient
 * @Package: com.atguigu.gulimall.member.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/8/25 - 16:10
 * @Version: v1.0
 */

@FeignClient("gulimall-coupon")
public interface CouponFeignClient {

    /**
     * 获取会员优惠券列表（member服务远程调用）
     * @return 会员优惠券列表
     */
    @RequestMapping("coupon/coupon/member/list")
    R memberCoupons();

}
