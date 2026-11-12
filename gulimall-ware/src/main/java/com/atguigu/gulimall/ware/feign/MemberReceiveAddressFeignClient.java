package com.atguigu.gulimall.ware.feign;

import com.atguigu.gulimall.api.member.MemberReceiveAddressVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * @ClassName: MemberReceiveAddressFeignClient
 * @Package: com.atguigu.gulimall.ware.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/16 - 17:25
 * @Version: v1.0
 */
@FeignClient("gulimall-member")
public interface MemberReceiveAddressFeignClient {

    @GetMapping(value = "member/memberreceiveaddress/{addrId}")
    MemberReceiveAddressVo getAddressById(@PathVariable Long addrId);

}
