package com.atguigu.gulimall.seckill.scheduled;

import com.atguigu.gulimall.seckill.service.SeckillSkuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 秒杀商品定时上架
 */
@Slf4j
@Service
public class SeckillSkuScheduled {

    @Autowired
    private SeckillSkuService seckillSkuService;

    /**
     * 每天凌晨 3 点：把未来 3 天 + 进行中的场次商品写到 Redis
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void uploadSeckillSku() {
        // 1. 把未来 3 天和进行中场次的商品重新写到 Redis，保证秒杀页和库存 key 不缺失
        seckillSkuService.uploadSeckillSkuLatest3Days();
    }
}
