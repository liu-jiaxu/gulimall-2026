package com.atguigu.gulimall.product;

import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.vo.Catelog2Vo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证「更新分类后，L1 与 L2 两级缓存是否都失效」。
 * <p>
 * 通过 application-test.yaml 把 L1 的 TTL 从生产的 5min 压到 1s，
 * 这样几秒内就能验证"L1 过期 -> 回落到 L2 重新加载"的完整链路。
 * <p>
 * 被测数据：cat_id=22 的二级分类「电子书刊」（父分类 cat_id=1「图书、音像、电子书刊」）。
 * 它出现在 getCatelogJson() 返回结构的 map.get("1") 里，字段是 Catelog2Vo.name。
 * <p>
 * 测试用 try/finally 保证跑完把名字还原回去。
 */
@ActiveProfiles("test")
@SpringBootTest
class CategoryCacheEvictTest {

    /** 二级分类「电子书刊」的 cat_id */
    private static final Long TARGET_CAT_ID = 22L;
    /** 它在 getCatelogJson() 返回结构里的 key —— 一级父分类的 catId */
    private static final String PARENT_KEY = "1";

    private static final String OLD_NAME = "电子书刊";
    private static final String NEW_NAME = "电子书刊test";

    @Autowired
    CategoryService categoryService;

    @Autowired
    RedisTemplate<String, byte[]> byteRedisTemplate;

    /** 走完整缓存链路查一次，取出目标分类当前显示的名字 */
    private String cachedName() {
        Map<String, List<Catelog2Vo>> data = categoryService.getCatelogJson();
        List<Catelog2Vo> level2 = data.get(PARENT_KEY);
        assertNotNull(level2, "一级分类 " + PARENT_KEY + " 下应有二级分类");
        return level2.stream()
                .filter(vo -> String.valueOf(TARGET_CAT_ID).equals(vo.getId()))
                .map(Catelog2Vo::getName)
                .findFirst()
                .orElse(null);
    }

    private void rename(String name) {
        CategoryEntity entity = new CategoryEntity();
        entity.setCatId(TARGET_CAT_ID);
        entity.setName(name);
        categoryService.updateDetails(entity);
    }

    /** L2(Redis) 里的缓存 key 是否还在 */
    private boolean redisKeyExists() {
        return Boolean.TRUE.equals(byteRedisTemplate.hasKey("catelogJSON"));
    }

    @Test
    void updateShouldEvictBothL1AndL2() throws Exception {
        System.out.println("================ 改名前的初始状态 ================");
        System.out.println(">>> 【1】查询，建立缓存");
        String before = cachedName();
        System.out.println(">>>     catId=" + TARGET_CAT_ID + " 名字 = " + before);
        System.out.println(">>>     L2(Redis) key 存在 = " + redisKeyExists());
        assertEquals(OLD_NAME, before, "初始应是「" + OLD_NAME + "」");

        try {
            System.out.println();
            System.out.println("================ 执行改名 ================");
            System.out.println(">>> 【2】" + OLD_NAME + " -> " + NEW_NAME);
            rename(NEW_NAME);
            System.out.println(">>>     改名完成");
            System.out.println(">>>     L2(Redis) key 还存在 = " + redisKeyExists()
                    + "   ← 应为 false，说明 L1 与 L2 都被清掉了");

            System.out.println();
            System.out.println("================ 改名后立即查询 ================");
            System.out.println(">>> 【3】验证 @CacheEvict 是否立即生效（不等 TTL）");
            String immediately = cachedName();
            System.out.println(">>>     catId=" + TARGET_CAT_ID + " 名字 = " + immediately);
            assertEquals(NEW_NAME, immediately,
                    "改名后应立即读到新名字，实际读到「" + immediately + "」");

            System.out.println();
            System.out.println("================ 等 L1 自然过期 ================");
            System.out.println(">>> 【4】等 1.5s 让 L1 过期（test profile TTL=1s），再查询");
            Thread.sleep(1500);
            String afterTtl = cachedName();
            System.out.println(">>>     catId=" + TARGET_CAT_ID + " 名字 = " + afterTtl);
            assertEquals(NEW_NAME, afterTtl, "L1 过期后应回落到 L2 重新加载，仍是新名字");
        } finally {
            System.out.println();
            System.out.println("================ 还原数据 ================");
            System.out.println(">>> 【5】" + NEW_NAME + " -> " + OLD_NAME);
            rename(OLD_NAME);
            String restored = cachedName();
            System.out.println(">>>     catId=" + TARGET_CAT_ID + " 名字 = " + restored);
            assertEquals(OLD_NAME, restored, "数据应已还原");
        }
    }
}
