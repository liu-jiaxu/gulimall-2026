package com.atguigu.gulimall.seckill.service.impl;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.service.SeckillStockService;
import com.atguigu.gulimall.seckill.vo.SeckillReserveResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 秒杀库存 Lua 脚本。
 *
 * <p>库存、限购、防重、预扣标识必须在一次原子操作里完成，
 * 否则并发下会出现"库存扣了但限购没加"或"重复扣减"。
 */
@Slf4j
@Service
public class SeckillStockServiceImpl implements SeckillStockService {

    /** 10 秒内同一会员同场次同 sku 只受理一次 */
    private static final long DEDUPE_SECONDS = 10L;

    /** 预扣标识保留 30 分钟，足够建单和第一次对账 */
    private static final long RESERVE_MARKER_MILLIS = 30L * 60 * 1000;

    /** 释放标识保留 7 天，防止延迟消息重复回补 */
    private static final long RELEASE_MARKER_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /**
     * KEYS：库存、限购 hash、防重 key、预扣标识
     * ARGV：memberId、数量、限购、orderSn、now、start、end、防重秒数、marker millis
     */
    private static final RedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local stock = redis.call('get', KEYS[1])
            if not stock then return {'-1', '0'} end

            local now = tonumber(ARGV[5])
            if now < tonumber(ARGV[6]) then return {'-4', '0'} end
            if now >= tonumber(ARGV[7]) then return {'-5', '0'} end

            local exist = redis.call('get', KEYS[3])
            if exist then return {'2', exist} end

            local qty = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local bought = tonumber(redis.call('hget', KEYS[2], ARGV[1]) or '0')
            if bought + qty > limit then return {'-3', '0'} end
            if tonumber(stock) < qty then return {'-2', '0'} end

            local left = redis.call('decrby', KEYS[1], qty)
            redis.call('hincrby', KEYS[2], ARGV[1], qty)

            local ttl = redis.call('pttl', KEYS[1])
            if ttl > 0 then redis.call('pexpire', KEYS[2], ttl) end

            redis.call('set', KEYS[3], ARGV[4], 'EX', ARGV[8])
            redis.call('set', KEYS[4], ARGV[1] .. ':' .. qty, 'PX', ARGV[9])

            return {'1', tostring(left)}
            """, List.class);

    /**
     * KEYS：库存、限购 hash、释放标识
     * ARGV：memberId、数量、release marker millis
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[3]) == 1 then return 0 end
            if redis.call('exists', KEYS[1]) == 0 then return -1 end

            redis.call('incrby', KEYS[1], ARGV[2])

            local bought = redis.call('hincrby', KEYS[2], ARGV[1], -tonumber(ARGV[2]))
            if bought < 0 then redis.call('hset', KEYS[2], ARGV[1], 0) end

            redis.call('set', KEYS[3], '1', 'PX', ARGV[3])
            return 1
            """, Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public SeckillReserveResult tryReserve(Long sessionId, Long skuId, Long memberId, int quantity,
                                           int limit, String orderSn, long startMillis, long endMillis) {
        // 1. 执行预扣 Lua 脚本：把库存、限购、防重、预扣标识四个 key 和会员/数量/时间等参数交给 Redis 原子处理
        List<?> raw = stringRedisTemplate.execute(RESERVE_SCRIPT,
                List.of(SeckillConstant.stockKey(sessionId, skuId),
                        SeckillConstant.userCountKey(sessionId, skuId),
                        SeckillConstant.requestKey(sessionId, skuId, memberId),
                        SeckillConstant.reserveKey(orderSn)),
                memberId.toString(),
                String.valueOf(quantity),
                String.valueOf(limit),
                orderSn,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(startMillis),
                String.valueOf(endMillis),
                String.valueOf(DEDUPE_SECONDS),
                String.valueOf(RESERVE_MARKER_MILLIS));

        SeckillReserveResult result = new SeckillReserveResult();
        // 2. 脚本没返回或返回元素不够，按库存 key 不存在（-1）处理，避免把异常当成功
        if (raw == null || raw.size() < 2) {
            result.setCode(-1);
            return result;
        }
        // 3. 解析返回码：1 表示成功，第二个值是剩余库存；2 表示重复请求，第二个值是第一次的订单号
        int code = Integer.parseInt(String.valueOf(raw.get(0)));
        result.setCode(code);
        if (code == 1) {
            // 只有成功时第二个返回值才是剩余库存
            result.setLeft(Long.parseLong(String.valueOf(raw.get(1))));
        } else if (code == 2) {
            // 重复请求时第二个返回值是第一次的订单号，不是数字
            result.setExistingOrderSn(String.valueOf(raw.get(1)));
        }
        return result;
    }

    @Override
    public int releaseReservation(Long sessionId, Long skuId, Long memberId, int quantity, String orderSn) {
        // 1. 执行释放 Lua 脚本，把先前扣掉的库存和限购计数还回去，并写释放标识防止重复回补
        Long result = stringRedisTemplate.execute(RELEASE_SCRIPT,
                List.of(SeckillConstant.stockKey(sessionId, skuId),
                        SeckillConstant.userCountKey(sessionId, skuId),
                        SeckillConstant.releaseKey(orderSn)),
                memberId.toString(),
                String.valueOf(quantity),
                String.valueOf(RELEASE_MARKER_MILLIS));
        // 2. 返回 null 说明库存 key 已不存在（比如活动已结束），统一按 -1 告诉上层
        return result == null ? -1 : result.intValue();
    }
}
