package com.atguigu.gulimall.thirdparty.service;

import com.atguigu.gulimall.thirdparty.dto.BaiduGeocodingResponse;
import com.atguigu.gulimall.thirdparty.dto.BaiduReverseGeocodingResponse;
import com.atguigu.gulimall.thirdparty.dto.BaiduRouteMatrixResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

/**
 * 百度地图 Web 服务 API（声明式 HTTP 接口）
 *
 * <p>和 {@link GiteeOAuthInterface} 一个套路：这里只声明「调哪个路径、传什么参数、返回什么」，
 * baseUrl 由 {@code BaiduMapConfiguration} 提供。
 *
 * <p>距离计算要走两步 —— 两个接口都在 {@code api.map.baidu.com} 一个域名下，所以只需要一个 WebClient：
 * <pre>
 *   地址 ──geocoding──▶ 经纬度 ──routematrix──▶ 驾车距离（单位：米）
 * </pre>
 *
 * <p>⚠️ 这两个接口都<b>计费</b>（消耗 AK 的配额），调用方务必只在服务端调用，
 * 不要给前端留直达入口。
 */
public interface BaiduMapInterface {

    /**
     * 地理编码：地址 → 经纬度
     *
     * @param address 结构化地址，无空格（如「北京市昌平区回龙观东大街1号」）
     * @param output  返回格式，固定传 {@code json}
     * @param ak      开发者密钥
     * @return ⚠️ {@code result} 可能为 null（地址解析不出来时），调用方必须判空
     */
    @GetExchange(url = "/geocoding/v3/")
    Mono<BaiduGeocodingResponse> geocoding(@RequestParam("address") String address,
                                           @RequestParam("output") String output,
                                           @RequestParam("ak") String ak);

    /**
     * 批量算路（驾车）：两个坐标 → 驾车距离
     *
     * @param origins      起点坐标，格式「<b>纬度,经度</b>」；多个点用 {@code |} 分隔
     * @param destinations 终点坐标，格式同上
     * @param output       返回格式，固定传 {@code json}
     * @param ak           开发者密钥
     */
    @GetExchange(url = "/routematrix/v2/driving")
    Mono<BaiduRouteMatrixResponse> drivingDistance(@RequestParam("origins") String origins,
                                                   @RequestParam("destinations") String destinations,
                                                   @RequestParam("output") String output,
                                                   @RequestParam("ak") String ak);

    /**
     * 逆地理编码：经纬度 → 省/市/区。
     *
     * @param location  坐标，格式「纬度,经度」
     * @param coordType 坐标类型：浏览器定位拿到的是 wgs84ll，必须显式传，
     *                  否则百度按 bd09ll 解析，城市会偏几十公里
     */
    @GetExchange(url = "/reverse_geocoding/v3/")
    Mono<BaiduReverseGeocodingResponse> reverseGeocoding(
            @RequestParam("location") String location,
            @RequestParam("output") String output,
            @RequestParam("coordtype") String coordType,
            @RequestParam("ak") String ak);

}
