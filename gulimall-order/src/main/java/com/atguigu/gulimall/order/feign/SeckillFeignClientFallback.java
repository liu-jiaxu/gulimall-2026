package com.atguigu.gulimall.order.feign;

import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * seckill 不可用时的 Feign 兜底。
 *
 * <p>release/paid 都是 best-effort：失败只记日志，不能反过来影响订单状态。
 */
@Slf4j
@Component
public class SeckillFeignClientFallback implements SeckillFeignClient {

    @Override
    public R releaseSeckillOrder(String orderSn, String reason) {
        log.error("[order-feign] 通知秒杀释放失败，等待对账：orderSn={} reason={}", orderSn, reason);
        return R.error(BizCodeEnum.SECKILL_ORDER_BUILD_FAILED.getCode(),
                "秒杀服务繁忙，库存释放稍后对账处理");
    }

    @Override
    public R markSeckillOrderPaid(String orderSn) {
        log.error("[order-feign] 通知秒杀支付回填失败，等待对账：orderSn={}", orderSn);
        return R.error(BizCodeEnum.SECKILL_ORDER_BUILD_FAILED.getCode(),
                "秒杀服务繁忙，支付状态稍后对账处理");
    }
}
