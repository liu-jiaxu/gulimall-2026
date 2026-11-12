package com.atguigu.gulimall.seckill.feign;

import com.atguigu.gulimall.api.order.vo.MemberAddressVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 会员服务：秒杀确认页拉收货地址
 */
@FeignClient("gulimall-member")
public interface MemberFeignClient {

    @GetMapping("/member/memberreceiveaddress/{memberId}/address")
    List<MemberAddressVo> getAddress(@PathVariable("memberId") Long memberId);
}
