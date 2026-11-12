package com.atguigu.gulimall.thirdparty.dto;

/**
 * GitHub 换 token 接口的响应
 *
 * <p>对应 {@code POST https://github.com/login/oauth/access_token} 的返回。
 *
 * <p>⚠️ 这个接口<b>成功和失败都返回 HTTP 200</b>，失败信息藏在 body 里：
 * <pre>
 *   成功：{"access_token":"gho_xxx","token_type":"bearer","scope":"read:user,user:email"}
 *   失败：{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}
 * </pre>
 * 所以判断成功要看 {@code access_token} 是否为空，<b>不能看状态码</b>。
 *
 * @param access_token      访问令牌，失败时为 null
 * @param token_type        令牌类型，一般是 bearer
 * @param scope             实际授予的权限范围
 * @param error             错误码，如 bad_verification_code
 * @param error_description 错误描述
 */
public record GithubTokenResponse(
        String access_token,
        String token_type,
        String scope,
        String error,
        String error_description
) {
}
