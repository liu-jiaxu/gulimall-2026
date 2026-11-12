package com.atguigu.gulimall.api.auth_server;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录成功后返回给调用方的会员信息
 *
 * <p>为什么不直接把 {@code MemberEntity} 返回出去？
 * <ul>
 *   <li>那个类在 gulimall-member 模块里，auth-server 根本拿不到</li>
 *   <li>实体里带着 password 等敏感字段，不该跨服务传</li>
 * </ul>
 *
 * <p>它会被放进 session（Spring Session → Redis），所以要能序列化。
 */
@Data
public class MemberResponseVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String nickname;

    /** 头像 */
    private String header;

    private String mobile;

    private String email;

    private Long levelId;

    /** 积分 */
    private Integer integration;

    /** 成长值 */
    private Integer growth;

}
