package com.atguigu.gulimall.member.service;

import com.atguigu.gulimall.api.auth_server.SocialUser;
import com.atguigu.gulimall.api.auth_server.UserLoginVo;
import com.atguigu.gulimall.api.auth_server.UserRegisterVo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.member.entity.MemberEntity;

import java.util.Map;

/**
 * 会员
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:55:54
 */
public interface MemberService extends IService<MemberEntity> {

    PageUtils queryPage(Map<String, Object> params);

    void regist(UserRegisterVo vo);

    /**
     * 账号密码登录
     *
     * @param vo 登录信息（账号可以是用户名或手机号）
     * @return 登录成功的会员 —— 调用方（auth-server）要拿它写 session
     */
    MemberEntity login(UserLoginVo vo);

    /**
     * 社交登录：拿授权码换社交用户信息，按 social_uid 查会员；
     * 查不到就自动注册一个，然后返回会员信息
     *
     * @param code   社交平台回调带回来的授权码
     * @param source 渠道标识：github / wechat
     * @return 登录成功的会员
     */
    MemberEntity oauthLogin(String code, String source);
}

