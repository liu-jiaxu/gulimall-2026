package com.atguigu.gulimall.seckill.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.seckill.service.SeckillOrderService;
import com.atguigu.gulimall.seckill.thread.MemberResponseVoThreadLocal;
import com.atguigu.gulimall.seckill.vo.SeckillOrderSubmitVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀抢购与结果查询（拦截器已保证登录）
 */
@Slf4j
@RestController
@RequestMapping("/seckill/order")
public class SeckillOrderController {

    @Autowired
    private SeckillOrderService seckillOrderService;

    /**
     * 抢购入口。Sentinel 资源点在方法最外层，
     * 被限流时不会进入 Redis 扣减，因此不会占库存。
     */
    @SentinelResource(value = "seckillOrderSubmit", blockHandler = "submitBlocked")
    @PostMapping("/submit")
    public R submit(@RequestBody SeckillOrderSubmitVo vo) {
        // 1. 登录会员由拦截器放进 ThreadLocal
        MemberResponseVo member = currentMember();
        // 2. Redis 预扣 + 落预扣记录 + 发建单消息
        return R.ok().put("seckillOrder", seckillOrderService.submit(vo, member));
    }

    /**
     * Sentinel 限流/熔断降级：返回统一错误码，前端提示稍后再试
     */
    public R submitBlocked(SeckillOrderSubmitVo vo, BlockException ex) {
        // 1. 先记一条告警日志，方便排查是哪个资源、哪种限流规则把请求拦下来的
        log.warn("[seckill] 抢购被限流/熔断：{}", ex.getClass().getSimpleName());
        // 2. 返回统一的限流错误码和提示，前端据此告诉用户稍后再试
        return R.error(BizCodeEnum.SECKILL_FLOW_LIMIT.getCode(),
                BizCodeEnum.SECKILL_FLOW_LIMIT.getMsg());
    }

    @GetMapping("/result")
    public R result(@RequestParam(value = "orderSn", required = false) String orderSn,
                    @RequestParam(value = "requestId", required = false) String requestId) {
        // 1. 查自己的秒杀单状态
        MemberResponseVo member = currentMember();
        // 2. 用当前会员 ID 查结果，防止用户通过换单号看到别人的秒杀单
        return R.ok().put("seckillOrder",
                seckillOrderService.getResult(orderSn, requestId, member.getId()));
    }

    private MemberResponseVo currentMember() {
        // 1. 从 ThreadLocal 取拦截器解析好的登录会员，避免每个接口都重新读 Session
        MemberResponseVo member = MemberResponseVoThreadLocal.get();
        // 2. 取不到会员或会员 ID 说明登录态失效，直接抛业务异常让统一异常处理返回未登录
        if (member == null || member.getId() == null) {
            throw new RRException(BizCodeEnum.SECKILL_NOT_LOGIN);
        }
        // 3. 校验通过返回会员对象，供后续业务使用
        return member;
    }
}
