package com.atguigu.gulimall.thirdparty.dto;

/**
 * GitHub 用户信息
 *
 * <p>对应 {@code GET https://api.github.com/user} 的返回（只挑我们需要的字段）。
 *
 * @param id         GitHub 的用户唯一 ID。<b>稳定不变，就是它来当 social_uid</b>
 * @param login      用户名。<b>用户可以随时改</b>，所以不能拿它当唯一键，
 *                   只用来生成会员的 username
 * @param name       昵称，用户可能没设置（为 null）
 * @param avatar_url 头像地址
 * @param email      邮箱。<b>经常是 null</b> —— 用户开了邮箱隐私，
 *                   或者根本没设公开邮箱。要准确拿到得再调 /user/emails
 */
public record GithubUserResponse(
        Long id,
        String login,
        String name,
        String avatar_url,
        String email
) {
}
