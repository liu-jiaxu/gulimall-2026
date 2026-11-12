package com.atguigu.gulimall.thirdparty.dto;

/**
 * Gitee「换 access_token」接口的响应
 *
 * <p>对应 {@code POST https://gitee.com/oauth/token}
 *
 * <p>和 GitHub/微信一样，出错时不一定改状态码，所以判断成功要看
 * {@code access_token} 在不在，别只看 HTTP 状态。
 *
 * @param access_token      访问令牌
 * @param token_type        令牌类型，一般是 bearer
 * @param expires_in        有效期（秒）
 * @param refresh_token     刷新令牌
 * @param scope             实际授予的范围
 * @param created_at        创建时间戳
 * @param error             出错时的错误码，如 invalid_grant
 * @param error_description 错误描述
 */
public record GiteeTokenResponse(
        String access_token,
        String token_type,
        Integer expires_in,
        String refresh_token,
        String scope,
        Long created_at,
        String error,
        String error_description
) {
}
