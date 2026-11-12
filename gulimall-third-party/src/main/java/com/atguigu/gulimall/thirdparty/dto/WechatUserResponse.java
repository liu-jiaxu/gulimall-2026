package com.atguigu.gulimall.thirdparty.dto;

/**
 * 微信「拉取用户信息」接口的响应
 *
 * <p>对应 {@code GET https://api.weixin.qq.com/sns/userinfo}
 * （要求授权时 scope = {@code snsapi_userinfo}，否则调这个接口会报权限不足）
 *
 * <p>⚠️ 微信返回的昵称是 UTF-8，<b>可能包含 emoji</b> —— 存库时字段要够宽
 * （MySQL 用 utf8mb4，普通 utf8 存不下 emoji 会报错或变成问号）。
 *
 * <p>微信返回里还有个 {@code privilege} 数组（用户特权信息），我们不需要，
 * 这里不声明 —— Jackson 默认会忽略未知字段，不会报错。
 *
 * @param openid     用户唯一标识
 * @param nickname   昵称。注意很多用户从没设置过，微信会给一个默认的"微信用户"
 * @param sex        性别：1 男 / 2 女 / 0 未知
 * @param province   省份
 * @param city       城市
 * @param country    国家
 * @param headimgurl 头像地址（微信 CDN，链接可能失效或返回 403）
 * @param unionid    绑定开放平台才有，见 WechatTokenResponse 的说明
 * @param errcode    错误码，成功时不存在
 * @param errmsg     错误描述
 */
public record WechatUserResponse(
        String openid,
        String nickname,
        Integer sex,
        String province,
        String city,
        String country,
        String headimgurl,
        String unionid,
        Integer errcode,
        String errmsg
) {
}
