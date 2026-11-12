package com.atguigu.gulimall.agent.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 秒杀现有公开接口（无需内部 token）。
 */
@FeignClient(name = "gulimall-seckill")
public interface SeckillAiClient {

    /**
     * 查询秒杀首页数据，包括当前场次、即将开始的场次和秒杀商品。
     *
     * @return 秒杀首页数据的响应数据
     */
    @GetMapping("/seckill/home")
    Map<String, Object> home();

    /**
     * 查询今天的所有秒杀场次。
     *
     * @return 今日秒杀场次列表的响应数据
     */
    @GetMapping("/seckill/sessions")
    Map<String, Object> sessions();

    /**
     * 查询指定秒杀场次下的商品。
     *
     * @param sessionId 秒杀场次 id
     * @return 场次商品列表的响应数据
     */
    @GetMapping("/seckill/skus")
    Map<String, Object> skus(@RequestParam("sessionId") Long sessionId);
}
