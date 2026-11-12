package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.api.seckill.vo.SeckillSkuVo;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.service.SeckillSkuService;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 14：秒杀商品上架。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-product。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow14SeckillSkuUploadTest extends SeckillIntegrationTestSupport {

    @Autowired
    private SeckillSkuService seckillSkuService;

    /**
     * 测试内容：删除商品 hash 与库存 key 后执行上架，验证商品信息与库存被重建、randomCode 已生成，且重复上架沿用原 randomCode。
     */
    @Test
    void uploadRestoresMissingHashAndStock() throws Exception {
        // 1. 确认商品服务可用，并创建一份包含测试场次和商品的夹具数据
        requireService("gulimall-product");
        Fixture fixture = createFixture();
        // 2. 主动删掉场次商品 hash 和库存 key，模拟缓存缺失，验证上架能重新补齐
        redis.delete(SeckillConstant.sessionSkusKey(fixture.sessionId()));
        redis.delete(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId()));

        // 3. 调用上架方法，扫描最近三天数据并把商品信息与库存写回 Redis
        seckillSkuService.uploadSeckillSkuLatest3Days();

        // 4. 检查商品 hash 已重建、商品 id 正确、randomCode 已生成，并且库存被补成 100
        Object json = redis.opsForHash().get(SeckillConstant.sessionSkusKey(fixture.sessionId()),
                String.valueOf(fixture.skuId()));
        assertNotNull(json, "上架后应写回商品 hash");
        SeckillSkuVo vo = objectMapper.readValue(String.valueOf(json), SeckillSkuVo.class);
        assertEquals(TEST_SKU_ID, vo.getSkuId());
        assertNotNull(vo.getRandomCode());
        assertEquals("100", redis.opsForValue().get(
                SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId())));

        // 5. 记下第一次生成的 randomCode，再执行一次上架，模拟定时任务重复执行
        String randomCode = vo.getRandomCode();
        seckillSkuService.uploadSeckillSkuLatest3Days();
        // 6. 读取第二次上架后的商品 hash，确认 randomCode 没有被覆盖，保证已发出去的随机码仍然有效
        SeckillSkuVo second = objectMapper.readValue(String.valueOf(
                redis.opsForHash().get(SeckillConstant.sessionSkusKey(fixture.sessionId()),
                        String.valueOf(fixture.skuId()))), SeckillSkuVo.class);
        assertEquals(randomCode, second.getRandomCode(), "重复上架必须沿用旧 randomCode");
    }

    /**
     * 测试内容：预先写入值为 7 的秒杀库存 key 后执行上架，验证上架不会覆盖已有库存值，库存 key 只补不覆盖。
     */
    @Test
    void uploadDoesNotOverwriteExistingStockKey() {
        // 1. 确认商品服务可用，并准备一份测试场次和商品数据
        requireService("gulimall-product");
        Fixture fixture = createFixture();
        // 2. 先往库存 key 里写入特殊值 7，模拟已有库存已经初始化过的情况
        redis.opsForValue().set(SeckillConstant.stockKey(
                fixture.sessionId(), fixture.skuId()), "7");

        // 3. 执行上架，正常情况下它只应该在上架时补缺失的库存 key
        seckillSkuService.uploadSeckillSkuLatest3Days();

        // 4. 确认库存仍然是 7，没有被数据库里的初始库存覆盖，证明补库存是幂等的
        assertEquals("7", redis.opsForValue().get(
                SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId())),
                "库存 key 只补不覆盖");
    }

    /**
     * 测试内容：删除场次商品 hash 后调用手动上架接口，验证接口成功，并重新生成商品 hash 与秒杀库存 key。
     */
    @Test
    void manualUploadEndpointTriggersUpload() {
        // 1. 确认商品服务可用，并创建一份测试场次和商品数据
        requireService("gulimall-product");
        Fixture fixture = createFixture();
        // 2. 先删掉场次商品 hash，让手动上架接口有缓存需要重建
        redis.delete(SeckillConstant.sessionSkusKey(fixture.sessionId()));

        // 3. 调用手动上架接口，验证接口本身返回成功
        Map<String, Object> response = postForm("/seckill/upload", Map.of(), null);
        assertOk(response);
        // 4. 确认手动上架后商品 hash 和秒杀库存 key 都被重新生成，接口确实触发了上架逻辑
        assertNotNull(redis.opsForHash().get(SeckillConstant.sessionSkusKey(fixture.sessionId()),
                String.valueOf(fixture.skuId())));
        assertTrue(redis.hasKey(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId())));
    }
}
