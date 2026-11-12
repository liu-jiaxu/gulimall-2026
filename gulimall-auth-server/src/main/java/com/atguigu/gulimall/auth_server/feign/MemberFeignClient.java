package com.atguigu.gulimall.auth_server.feign;

import com.atguigu.gulimall.api.auth_server.UserLoginVo;
import com.atguigu.gulimall.api.auth_server.UserRegisterVo;
import com.atguigu.gulimall.api.order.vo.MemberAddressVo;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * @ClassName: MemberFeignClient
 * @Package: com.atguigu.gulimall.auth_server.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/5 - 09:04
 * @Version: v1.0
 */
@FeignClient("gulimall-member")
public interface MemberFeignClient {

    @PostMapping("member/member/regist")
    R regist(@RequestBody UserRegisterVo vo);

    @PostMapping("member/member/login")
    R login(@RequestBody UserLoginVo vo);

    /**
     * 社交登录：把授权码交给 member，由它去换社交用户 + 查/建会员
     *
     * @param code   社交平台回调带回来的授权码
     * @param source 渠道标识：github / wechat
     * @return 成功时 {@code data} 里是 MemberResponseVo
     */
    @PostMapping("member/member/oauth2/login")
    R oauthLogin(@RequestParam("code") String code, @RequestParam("source") String source);

    /**
     * 登录后判断会员有没有默认收货地址。
     */
    @GetMapping("member/memberreceiveaddress/{memberId}/address")
    List<MemberAddressVo> getAddress(@PathVariable("memberId") Long memberId);

}
