package com.atguigu.gulimall.member.controller;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.member.entity.MemberEntity;
import com.atguigu.gulimall.member.entity.MemberReceiveAddressEntity;
import com.atguigu.gulimall.member.service.MemberReceiveAddressService;
import com.atguigu.gulimall.member.service.MemberService;
import com.atguigu.gulimall.member.vo.MemberProfileUpdateVo;
import com.atguigu.gulimall.member.vo.PasswordUpdateVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 会员个人中心：资料、密码、收货地址。
 *
 * <p>页面和接口都只认 session 里的当前会员，不接受前端传 memberId。
 */
@Controller
public class MemberCenterController {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberReceiveAddressService memberReceiveAddressService;

    @Value("${gulimall.member-url}")
    private String memberUrl;

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    /**
     * 个人中心页面
     */
    @GetMapping("/member/center.html")
    public String centerPage(@RequestParam(value = "needAddress", required = false) Integer needAddress,
                             @RequestParam(value = "returnUrl", required = false) String returnUrl,
                             HttpServletRequest request, Model model) {
        // 1. 未登录回登录页，登录后回到个人中心
        MemberResponseVo loginUser = loginUser(request.getSession(false));
        if (loginUser == null) {
            String current = memberUrl + "/member/center.html";
            return "redirect:" + authUrl + "/login.html?returnUrl="
                    + URLEncoder.encode(current, StandardCharsets.UTF_8);
        }

        // 2. 会员和地址
        MemberEntity member = memberService.getById(loginUser.getId());
        if (member == null) {
            return "redirect:" + authUrl + "/login.html";
        }
        List<MemberReceiveAddressEntity> addresses =
                memberReceiveAddressService.getAddress(loginUser.getId());

        model.addAttribute("member", member);
        model.addAttribute("addresses", addresses);
        model.addAttribute("needAddress", needAddress != null && needAddress == 1);
        model.addAttribute("returnUrl", returnUrl);
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("shopHomeUrl", shopHomeUrl);
        model.addAttribute("authUrl", authUrl);
        return "member-center";
    }

    /**
     * 修改当前会员资料
     */
    @PostMapping("/member/center/profile")
    @ResponseBody
    public R updateProfile(@RequestBody MemberProfileUpdateVo vo, HttpSession session) {
        MemberResponseVo loginUser = loginUser(session);
        if (loginUser == null) {
            return notLogin();
        }
        MemberEntity member = memberService.getById(loginUser.getId());
        if (member == null) {
            return R.error("会员不存在");
        }

        // 1. 昵称必填，手机号如果填了要校验格式和唯一
        String nickname = trim(vo.getNickname());
        if (nickname == null || nickname.length() > 50) {
            return R.error("昵称不能为空且不能超过 50 个字符");
        }
        String mobile = trim(vo.getMobile());
        if (mobile != null && !mobile.matches("^1[3-9]\\d{9}$")) {
            return R.error("手机号格式不正确");
        }
        if (mobile != null) {
            long exists = memberService.count(new QueryWrapper<MemberEntity>()
                    .eq("mobile", mobile)
                    .ne("id", loginUser.getId()));
            if (exists > 0) {
                return R.error(BizCodeEnum.PHONE_EXISTS.getCode(), "手机号已被其他账号使用");
            }
        }

        String email = trim(vo.getEmail());
        String header = trim(vo.getHeader());
        String city = trim(vo.getCity());
        String job = trim(vo.getJob());
        String sign = trim(vo.getSign());

        // 2. 只更新个人资料字段，积分/等级/状态/密码都不允许从这里改
        memberService.update(new UpdateWrapper<MemberEntity>()
                .set("nickname", nickname)
                .set("mobile", mobile)
                .set("email", email)
                .set("header", header)
                .set("gender", vo.getGender())
                .set("city", city)
                .set("job", job)
                .set("sign", sign)
                .eq("id", loginUser.getId()));

        // 3. 同步 session，让页面顶部昵称立即变化
        loginUser.setNickname(nickname);
        loginUser.setMobile(mobile);
        loginUser.setEmail(email);
        loginUser.setHeader(header);
        session.setAttribute(AuthServerConstant.LOGIN_USER, loginUser);
        return R.ok();
    }

    /**
     * 修改密码
     */
    @PostMapping("/member/center/password")
    @ResponseBody
    public R updatePassword(@RequestBody PasswordUpdateVo vo, HttpSession session) {
        MemberResponseVo loginUser = loginUser(session);
        if (loginUser == null) {
            return notLogin();
        }
        if (vo == null || !StringUtils.hasText(vo.getNewPassword())) {
            return R.error("新密码不能为空");
        }
        String newPassword = vo.getNewPassword();
        if (newPassword.length() < 6 || newPassword.length() > 18) {
            return R.error("新密码长度必须是 6-18 位");
        }

        MemberEntity member = memberService.getById(loginUser.getId());
        if (member == null) {
            return R.error("会员不存在");
        }

        // 1. 有旧密码就必须校验；社交登录用户没有密码时允许直接设置
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        if (StringUtils.hasText(member.getPassword())
                && !encoder.matches(vo.getOldPassword() == null ? "" : vo.getOldPassword(),
                member.getPassword())) {
            return R.error("原密码不正确");
        }

        // 2. 更新密码
        memberService.update(new UpdateWrapper<MemberEntity>()
                .set("password", encoder.encode(newPassword))
                .eq("id", loginUser.getId()));
        return R.ok();
    }

    private MemberResponseVo loginUser(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(AuthServerConstant.LOGIN_USER);
        return value instanceof MemberResponseVo ? (MemberResponseVo) value : null;
    }

    private R notLogin() {
        return R.error(BizCodeEnum.MEMBER_NOT_LOGIN.getCode(), BizCodeEnum.MEMBER_NOT_LOGIN.getMsg());
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
