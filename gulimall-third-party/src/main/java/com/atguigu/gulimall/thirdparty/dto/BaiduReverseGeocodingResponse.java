package com.atguigu.gulimall.thirdparty.dto;

/**
 * 百度地图「逆地理编码」响应：经纬度 → 省/市/区。
 *
 * <p>文档：https://lbsyun.baidu.com/faq/api?title=webapi/guide/webservice-geocoding-abroad
 */
public record BaiduReverseGeocodingResponse(Integer status, Result result, String message) {

    public record Result(AddressComponent addressComponent) {
    }

    public record AddressComponent(String province, String city, String district) {
    }
}
