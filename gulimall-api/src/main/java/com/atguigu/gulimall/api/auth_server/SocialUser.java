package com.atguigu.gulimall.api.auth_server;

import lombok.Data;

/**
 * 通用社交用户对象
 *
 * <p>各社交平台（GitHub / 微博 / 微信…）登录后拿到的用户信息，
 * 统一映射成这个结构，再交给 member 服务做「查会员 / 自动注册」。
 *
 * <p>它同时充当两个角色：
 * <ul>
 *   <li>third-party 调完平台 API 后，用它作为返回体</li>
 *   <li>member 侧按 uid 找会员、首次登录时用它填写会员资料</li>
 * </ul>
 */
@Data
public class SocialUser {

    /**
     * 社交平台的用户唯一标识
     *
     * <p>GitHub 用 {@code id}（数字，用户改名也不会变）；
     * <b>不要</b>用 login / nickname —— 那些用户可以随时改，
     * 一旦拿它当唯一键，用户改名后就会变成另一个人。
     */
    private String uid;

    /** 访问令牌 */
    private String access_token;

    /** 令牌有效期（秒）。GitHub 不返回该字段，这里保持 0 */
    private long expires_in;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String header;

    /** 邮箱（GitHub 需要额外调 /user/emails 才有，可能为空） */
    private String email;

    /** 登录名（GitHub 的 login），用于生成会员 username */
    private String login;

}
