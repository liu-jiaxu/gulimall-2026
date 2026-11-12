package com.atguigu.gulimall.order.feign;

import com.atguigu.gulimall.api.member.MemberReceiveAddressVo;
import com.atguigu.gulimall.api.order.vo.MemberAddressVo;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * @ClassName: MemberReceiveAddressFeignClient
 * @Package: com.atguigu.gulimall.order.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/14 - 10:50
 * @Version: v1.0
 */
@FeignClient("gulimall-member")
public interface MemberReceiveAddressFeignClient {

    @GetMapping(value = "member/memberreceiveaddress/{memberId}/address")
    List<MemberAddressVo> getAddress(@PathVariable Long memberId);

    /**
     * 按地址 id 查单条地址（下单时用来取收货人信息，并核对该地址是否属于当前用户）
     */
    @GetMapping(value = "member/memberreceiveaddress/{addrId}")
    MemberAddressVo getAddressById(@PathVariable("addrId") Long addrId);

    /**
     * 新增收货地址
     *
     * <p>⚠️ 这里<b>不传 memberId</b>：member 服务是从会话里的登录信息取的。
     * 而它能取到会话，靠的是 order 把浏览器带来的 Cookie 一起转发过去（见 {@code FeignCookieConfig}）——
     * 所以这个调用同样必须在【主线程】发起，或者确保 TaskDecorator 生效。
     */
    @PostMapping("member/memberreceiveaddress/save")
    R saveAddress(@RequestBody MemberReceiveAddressVo address);

    /**
     * 修改收货地址
     *
     * <p>同样不传 memberId：member 侧会先按 id 查出这条地址、核对是不是当前登录用户的，再改。
     */
    @PostMapping("member/memberreceiveaddress/update")
    R updateAddress(@RequestBody MemberReceiveAddressVo address);

}
