package com.atguigu.gulimall.thirdparty.dto;

/**
 * 微信「网页授权换 access_token」接口的响应
 *
 * <p>对应 {@code GET https://api.weixin.qq.com/sns/oauth2/access_token}
 *
 * <p>和 GitHub 一样，这个接口<b>出错也返回 HTTP 200</b>，错误藏在 body 里：
 * <pre>
 *   成功：{"access_token":"xxx","expires_in":7200,"refresh_token":"xxx","openid":"xxx","scope":"snsapi_userinfo"}
 *   失败：{"errcode":40029,"errmsg":"invalid code, rid: xxx"}
 * </pre>
 * 所以判断成功要看 {@code openid} / {@code access_token} 在不在，<b>不能看状态码</b>。
 *
 * @param access_token  网页授权令牌。<b>注意</b>：这和公众号的「全局 access_token」不是一回事，
 *                      全局那个是用来调管理接口的，两者不能混用
 * @param expires_in    有效期（秒），一般 7200
 * @param refresh_token 刷新令牌，access_token 过期后用它续
 * @param openid        用户<b>在本公众号下</b>的唯一标识 —— 这就是我们的 social_uid
 * @param scope         实际授予的范围
 * @param unionid       只有【公众号已绑定开放平台】时才会返回。
 *                      它是「同一个人在同一个开放平台账号下所有应用」的统一标识，
 *                      以后要做小程序 / App 打通同一个用户就靠它
 * @param errcode       错误码，成功时不存在
 * @param errmsg        错误描述
 */
public record WechatTokenResponse(
        String access_token,
        Integer expires_in,
        String refresh_token,
        String openid,
        String scope,
        String unionid,
        Integer errcode,
        String errmsg
) {
}
