package com.atguigu.gulimall.auth_server.service;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.order.vo.MemberAddressVo;
import com.atguigu.gulimall.auth_server.feign.MemberFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 登录成功后的统一跳转：
 * 会员没有默认收货地址时，先去个人中心设置，设置完再回到原目标页。
 */
@Slf4j
@Service
public class LoginRedirectService {

    @Value("${gulimall.member-url}")
    private String memberUrl;

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    @Autowired
    private MemberFeignClient memberFeignClient;

    /**
     * @param member 登录成功的会员
     * @param target 登录后原本要去的地址（调用方必须已做安全校验）
     */
    public String afterLogin(MemberResponseVo member, String target) {
        String destination = StringUtils.hasText(target) ? target : shopHomeUrl;
        if (member == null || member.getId() == null) {
            return "redirect:" + destination;
        }

        // 1. 没有默认地址：先去个人中心设置，并把原目标地址带回去
        if (!hasDefaultAddress(member.getId())) {
            return "redirect:" + memberUrl + "/member/center.html?needAddress=1&returnUrl="
                    + URLEncoder.encode(destination, StandardCharsets.UTF_8);
        }

        // 2. 已有默认地址：正常回目标页
        return "redirect:" + destination;
    }

    private boolean hasDefaultAddress(Long memberId) {
        try {
            List<MemberAddressVo> addresses = memberFeignClient.getAddress(memberId);
            if (addresses == null) {
                return false;
            }
            return addresses.stream()
                    .anyMatch(address -> Integer.valueOf(1).equals(address.getDefaultStatus()));
        } catch (Exception e) {
            // 地址服务暂时不可用时不要卡住登录，按原目标走
            log.error("[login-redirect] 查询默认地址失败 memberId={}", memberId, e);
            return true;
        }
    }
}
