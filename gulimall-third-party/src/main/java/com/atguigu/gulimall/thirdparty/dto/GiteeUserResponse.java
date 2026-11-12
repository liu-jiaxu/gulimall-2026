package com.atguigu.gulimall.thirdparty.dto;

/**
 * Gitee 用户信息
 *
 * <p>对应 {@code GET https://gitee.com/api/v5/user}（只挑我们需要的字段）
 *
 * <p>字段名和 GitHub 高度一致（Gitee 本来就是照着 GitHub 做的），
 * 所以 member 侧「按 social_uid 查会员 / 自动注册」那套逻辑一个字都不用改。
 *
 * @param id         Gitee 的用户唯一 ID。<b>数字、稳定不变，用它当 social_uid</b>
 * @param login      用户名。用户可改，所以不用它当唯一键，只用来生成会员 username
 * @param name       昵称，可能为空
 * @param avatar_url 头像地址
 * @param email      邮箱，可能为空
 * @param bio        个人简介
 */
public record GiteeUserResponse(
        Long id,
        String login,
        String name,
        String avatar_url,
        String email,
        String bio
) {
}
