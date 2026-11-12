package com.atguigu.gulimall.seckill.feign;

import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * product 不可用时返回空 skuInfo，页面降级/上架任务跳过，不把异常抛给抢购链路。
 */
@Slf4j
@Component
public class ProductFeignClientFallback implements ProductFeignClient {

    @Override
    public R getSkuInfo(Long skuId) {
        // 1. 记错误日志并把 skuId 带上，便于事后知道是哪个商品查询失败
        log.error("[seckill-feign] 查询商品失败，降级返回空 skuInfo：skuId={}", skuId);
        // 2. 返回不带 skuInfo 的成功响应，让页面降级、上架任务跳过这个商品而不是整条链路失败
        return R.ok();
    }
}
