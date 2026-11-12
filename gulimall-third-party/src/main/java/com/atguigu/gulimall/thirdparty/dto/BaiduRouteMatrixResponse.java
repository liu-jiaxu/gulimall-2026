package com.atguigu.gulimall.thirdparty.dto;

import java.util.List;

/**
 * 百度地图「批量算路（距离矩阵）」接口响应：两个坐标 → 驾车距离
 *
 * <p>文档：https://lbsyun.baidu.com/faq/api?title=webapi/route-matrix-api
 *
 * <p>本例是「单起点 + 单终点」，所以 {@code result} 里只会有 1 个元素；
 * 声明成 List 是为了跟接口的真实结构对齐（它有"多起点 × 多终点"的能力，一次最多 50 条）。
 *
 * @param status  0 = 正常；3 = 权限校验失败（多半是 AK 没开通「批量算路」服务）
 * @param result  起终点两两组合的结果
 * @param message 提示信息
 */
public record BaiduRouteMatrixResponse(Integer status, List<Item> result, String message) {

    public record Item(Distance distance, Duration duration) {
    }

    /**
     * @param text  文本描述，如「2.2公里」（单位会随数值自动在米/公里间切换）
     * @param value 距离<b>数值，单位固定是米</b>
     */
    public record Distance(String text, Integer value) {
    }

    /**
     * @param text  文本描述，如「32分钟」
     * @param value 耗时<b>数值，单位固定是秒</b>
     */
    public record Duration(String text, Integer value) {
    }

}
