package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 会员只读内部接口。
 */
@FeignClient(name = "gulimall-member", path = "/internal/ai/member")
public interface MemberAiClient {

    /**
     * 查询指定会员的基本资料。
     *
     * @param id 会员 id
     * @return 会员资料的响应数据
     */
    @GetMapping("/{id}/profile")
    Map<String, Object> profile(@PathVariable("id") Long id);

    /**
     * 查询指定会员的收货地址列表。
     *
     * @param id 会员 id
     * @return 会员收货地址的响应数据
     */
    @GetMapping("/{id}/addresses")
    Map<String, Object> addresses(@PathVariable("id") Long id);

    /**
     * 查询指定会员的优惠券列表。
     *
     * @param id 会员 id
     * @return 会员优惠券的响应数据
     */
    @GetMapping("/{id}/coupons")
    Map<String, Object> coupons(@PathVariable("id") Long id);

    /**
     * 查询指定会员的积分信息。
     *
     * @param id 会员 id
     * @return 会员积分的响应数据
     */
    @GetMapping("/{id}/integration")
    Map<String, Object> integration(@PathVariable("id") Long id);
}
