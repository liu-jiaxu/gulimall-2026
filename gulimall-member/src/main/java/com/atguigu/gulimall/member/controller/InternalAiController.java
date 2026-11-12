package com.atguigu.gulimall.member.controller;

import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.common.web.InternalAiControllerSupport;
import com.atguigu.gulimall.member.entity.MemberEntity;
import com.atguigu.gulimall.member.entity.MemberReceiveAddressEntity;
import com.atguigu.gulimall.member.feign.CouponFeignClient;
import com.atguigu.gulimall.member.service.MemberReceiveAddressService;
import com.atguigu.gulimall.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 gulimall-agent 用的会员只读接口。
 *
 * <p>手机号、详细地址都做脱敏；密码、社交 token 等字段绝不返回。
 */
@RestController
@RequestMapping("/internal/ai/member")
@RequiredArgsConstructor
public class InternalAiController extends InternalAiControllerSupport {

    private final MemberService memberService;
    private final MemberReceiveAddressService addressService;
    private final CouponFeignClient couponFeignClient;

    /**
     * 会员概要（不含密码）。
     */
    @GetMapping("/{id}/profile")
    public R profile(HttpServletRequest request, @PathVariable("id") Long id) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 只挑可公开给 AI 的字段
        MemberEntity member = memberService.getById(id);
        if (member == null) {
            return R.error(8001, "会员不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", member.getId());
        data.put("username", member.getUsername());
        data.put("nickname", member.getNickname());
        data.put("mobile", maskPhone(member.getMobile()));
        data.put("email", member.getEmail());
        data.put("levelId", member.getLevelId());
        data.put("integration", member.getIntegration());
        data.put("growth", member.getGrowth());
        data.put("city", member.getCity());
        return R.ok().put("data", data);
    }

    /**
     * 收货地址列表。
     */
    @GetMapping("/{id}/addresses")
    public R addresses(HttpServletRequest request, @PathVariable("id") Long id) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 手机号脱敏后再返回
        List<MemberReceiveAddressEntity> addresses = addressService.getAddress(id);
        if (addresses != null) {
            for (MemberReceiveAddressEntity address : addresses) {
                address.setPhone(maskPhone(address.getPhone()));
            }
        }
        return R.ok().put("data", addresses);
    }

    /**
     * 我的优惠券（远程调 coupon）。
     */
    @GetMapping("/{id}/coupons")
    public R coupons(HttpServletRequest request, @PathVariable("id") Long id) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. coupon 服务不可用时返回空列表，不影响 AI 其它回答
        try {
            return couponFeignClient.memberCoupons();
        } catch (Exception ex) {
            return R.ok().put("coupons", List.of());
        }
    }

    /**
     * 积分/成长值。
     */
    @GetMapping("/{id}/integration")
    public R integration(HttpServletRequest request, @PathVariable("id") Long id) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 只返回积分相关字段
        MemberEntity member = memberService.getById(id);
        if (member == null) {
            return R.error(8001, "会员不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("integration", member.getIntegration());
        data.put("growth", member.getGrowth());
        data.put("levelId", member.getLevelId());
        return R.ok().put("data", data);
    }
}
