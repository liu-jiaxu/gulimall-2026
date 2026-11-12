package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 订单只读内部接口。
 */
@FeignClient(name = "gulimall-order", path = "/internal/ai/order")
public interface OrderAiClient {

    /**
     * 分页查询指定会员的订单列表。
     *
     * @param memberId 会员 id
     * @param status   订单状态，可为空
     * @param keyword  订单号或商品名关键字，可为空
     * @param page     页码，从 1 开始
     * @param limit    每页数量
     * @return 会员订单列表的响应数据
     */
    @GetMapping("/member/{memberId}")
    Map<String, Object> memberOrders(@PathVariable("memberId") Long memberId,
                                     @RequestParam(value = "status", required = false) Integer status,
                                     @RequestParam(value = "keyword", required = false) String keyword,
                                     @RequestParam(value = "page", defaultValue = "1") long page,
                                     @RequestParam(value = "limit", defaultValue = "10") long limit);

    /**
     * 查询指定订单的详情，并可携带会员 id 校验订单归属。
     *
     * @param orderSn  订单号
     * @param memberId 会员 id，可为空
     * @return 订单详情的响应数据
     */
    @GetMapping("/detail/{orderSn}")
    Map<String, Object> detail(@PathVariable("orderSn") String orderSn,
                               @RequestParam(value = "memberId", required = false) Long memberId);

    /**
     * 管理端按订单号、会员、状态和时间范围分页搜索订单。
     *
     * @param orderSn   订单号，可为空
     * @param memberId  会员 id，可为空
     * @param status    订单状态，可为空
     * @param startTime 开始时间，可为空
     * @param endTime   结束时间，可为空
     * @param page      页码，从 1 开始
     * @param limit     每页数量
     * @return 管理端订单搜索结果的响应数据
     */
    @GetMapping("/admin/search")
    Map<String, Object> adminSearch(@RequestParam(value = "orderSn", required = false) String orderSn,
                                    @RequestParam(value = "memberId", required = false) Long memberId,
                                    @RequestParam(value = "status", required = false) Integer status,
                                    @RequestParam(value = "startTime", required = false) String startTime,
                                    @RequestParam(value = "endTime", required = false) String endTime,
                                    @RequestParam(value = "page", defaultValue = "1") long page,
                                    @RequestParam(value = "limit", defaultValue = "10") long limit);

    /**
     * 管理端按时间范围和关键字统计订单数据。
     *
     * @param startTime 开始时间，可为空
     * @param endTime   结束时间，可为空
     * @param keyword   统计关键字，可为空
     * @return 管理端订单统计数据的响应数据
     */
    @GetMapping("/admin/stat")
    Map<String, Object> adminStat(@RequestParam(value = "startTime", required = false) String startTime,
                                  @RequestParam(value = "endTime", required = false) String endTime,
                                  @RequestParam(value = "keyword", required = false) String keyword);
}
