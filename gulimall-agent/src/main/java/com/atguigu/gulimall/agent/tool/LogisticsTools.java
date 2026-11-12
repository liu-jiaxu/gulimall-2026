package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.ThirdPartyAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * 地理位置工具：复用项目已有的百度地图能力。
 */
public class LogisticsTools {

    private final ThirdPartyAiClient client;
    private final ObjectMapper mapper;

    /**
     * 创建地理位置工具实例。
     *
     * @param client 第三方内部接口客户端，用于调用百度地图能力
     * @param mapper JSON 序列化器，用于把接口响应转换成模型可读的文本
     */
    public LogisticsTools(ThirdPartyAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 把详细地址转换成经纬度。
     *
     * @param address 详细地址，例如 北京市昌平区回龙观
     * @return 地理编码结果，并在前面附带原始地址；地址为空或查询失败时返回中文提示
     */
    @Tool("把地址转换成经纬度（地理编码），例如 北京市昌平区回龙观")
    public String geocode(@P("详细地址") String address) {
        if (address == null || address.isBlank()) {
            return "查询失败：地址不能为空";
        }
        Map<String, Object> response = client.geocode(address.trim());
        String result = ToolResult.from(response, mapper);
        return "address=" + address + "，" + result;
    }

    /**
     * 根据经纬度反查所在的省、市、区。
     *
     * @param lat 纬度，例如 39.9042
     * @param lng 经度，例如 116.4074
     * @return 逆地理编码结果的 JSON 文本；经纬度为空或查询失败时返回中文提示
     */
    @Tool("根据经纬度反查省/市/区（逆地理编码）")
    public String reverseGeocode(@P("纬度，例如 39.9042") Double lat,
                                 @P("经度，例如 116.4074") Double lng) {
        if (lat == null || lng == null) {
            return "查询失败：经纬度不能为空";
        }
        Map<String, Object> response = client.reverse(String.valueOf(lat), String.valueOf(lng));
        return ToolResult.from(response, mapper);
    }
}
