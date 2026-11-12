package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 第三方能力：百度地图。
 */
@FeignClient(name = "gulimall-third-party")
public interface ThirdPartyAiClient {

    /**
     * 根据地址获取经纬度。
     *
     * @param address 待解析的地址
     * @return 地理编码结果的响应数据
     */
    @GetMapping("/baidu/map/geocode")
    Map<String, Object> geocode(@RequestParam("address") String address);

    /**
     * 根据经纬度反查地址。
     *
     * @param lat 纬度
     * @param lng 经度
     * @return 逆地理编码结果的响应数据
     */
    @GetMapping("/baidu/map/reverse")
    Map<String, Object> reverse(@RequestParam("lat") String lat,
                                @RequestParam("lng") String lng);
}
