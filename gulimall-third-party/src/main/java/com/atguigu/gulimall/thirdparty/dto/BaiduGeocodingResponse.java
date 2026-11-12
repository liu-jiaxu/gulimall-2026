package com.atguigu.gulimall.thirdparty.dto;

import java.math.BigDecimal;

/**
 * 百度地图「地理编码」接口响应：地址 → 经纬度
 *
 * <p>文档：https://lbsyun.baidu.com/faq/api?title=webapi/guide/webservice-geocoding
 *
 * <p>只声明用得到的字段，其余交给 Jackson 忽略（和 {@link WechatUserResponse} 一个套路）。
 *
 * <p>⚠️ 判断成功不能看 HTTP 状态码（它恒为 200），要看 {@code status}：
 * {@code status != 0} 或 {@code result == null} 都算失败。
 *
 * @param status  0 = 正常；3 = 权限校验失败（多半是 AK 没开通「地理编码」服务）
 * @param result  解析结果；<b>地址解析不出来时为 null</b>
 * @param message 提示信息
 */
public record BaiduGeocodingResponse(Integer status, Result result, String message) {

    /**
     * @param location   解析出的坐标（坐标系 bd09ll，百度自己的坐标系）
     * @param precise    是否精确打点：1 = 精确，0 = 不精确
     * @param confidence 可信度，0 ~ 100
     * @param level      匹配到的行政区划级别
     */
    public record Result(Location location, Integer precise, Integer confidence, String level) {
    }

    /**
     * 坐标
     *
     * <p>⚠️ 字段顺序是 <b>lng / lat</b>（经度在前）—— 而算路接口要的顺序恰恰相反，
     * 拼参数时得写成 {@code lat + "," + lng}，详见 LogisticsDistanceController#toLatLng
     */
    public record Location(BigDecimal lng, BigDecimal lat) {
    }

}
