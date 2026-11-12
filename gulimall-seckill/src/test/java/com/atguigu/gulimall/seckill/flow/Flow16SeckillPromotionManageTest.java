package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 16：秒杀活动管理。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-coupon。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow16SeckillPromotionManageTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：验证秒杀活动的保存、详情、列表与修改，并确认存在未结束场次时活动和场次的修改、删除都会被拒绝。
     */
    @Test
    void promotionCrudAndUnfinishedSessionProtection() {
        // 1. 确认 coupon 服务可用，并准备一个唯一标题、已经开始且持续到明天的活动参数
        String base = requireService("gulimall-coupon");
        String title = PROMOTION_TITLE_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() - 60 * 60 * 1000L);
        Date end = new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);

        // 2. 调用保存接口新增活动，再按标题查出数据库生成的 id，供后续详情、修改和删除使用
        assertOk(postServiceJson(base, "/coupon/seckillpromotion/save",
                promotionBody(title, start, end)));
        long promotionId = findPromotionId(title);

        // 3. 分别调用详情接口和分页列表接口，确认新增的活动能被正常查询到
        assertOk(getServiceJson(base, "/coupon/seckillpromotion/info/" + promotionId));
        assertOk(getServiceJson(base, "/coupon/seckillpromotion/list?page=1&limit=10"));

        // 4. 调用修改接口把标题改成带 -UPDATED 的值，再直接查库确认标题真的被更新
        Map<String, Object> update = promotionBody(title + "-UPDATED", start, end);
        update.put("id", promotionId);
        assertOk(postServiceJson(base, "/coupon/seckillpromotion/update", update));
        assertEquals(title + "-UPDATED", jdbc("gulimall_sms").queryForObject(
                "SELECT title FROM sms_seckill_promotion WHERE id = ?",
                String.class, promotionId));

        // 建一个未结束场次，活动和场次的修改/删除都应按受保护处理
        // 5. 保存一个尚未结束的场次并查到它的 id，让活动处于“有未结束场次”的保护状态
        String sessionName = SESSION_NAME_PREFIX + System.nanoTime();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> session = new HashMap<>();
        session.put("name", sessionName);
        session.put("promotionId", promotionId);
        session.put("startTime", format.format(new Date(System.currentTimeMillis() + 60 * 60 * 1000L)));
        session.put("endTime", format.format(new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000L)));
        assertOk(postServiceJson(base, "/coupon/seckillsession/save", session));
        long sessionId = smsJdbc.queryForObject(
                "SELECT id FROM sms_seckill_session WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, sessionName);
        trackSessionId(sessionId);

        // 6. 尝试修改活动，预期返回非 0 错误码，证明未结束场次会阻止活动修改
        Map<String, Object> updateBlocked = promotionBody("BLOCKED", start, end);
        updateBlocked.put("id", promotionId);
        Map<String, Object> updateResponse = postServiceJson(base,
                "/coupon/seckillpromotion/update", updateBlocked);
        assertTrue(((Number) updateResponse.get("code")).intValue() != 0,
                "有未结束场次时活动不能修改");

        // 7. 尝试删除活动，同样预期返回非 0 错误码，证明未结束场次也会阻止活动删除
        Map<String, Object> deleteBlocked = postServiceJson(base,
                "/coupon/seckillpromotion/delete", List.of(promotionId));
        assertTrue(((Number) deleteBlocked.get("code")).intValue() != 0,
                "有未结束场次时活动不能删除");

        // 8. 最后删掉上一步创建的未结束场次，清理测试数据，避免影响其它用例
        assertOk(postServiceJson(base, "/coupon/seckillsession/delete", List.of(sessionId)));
    }

    /**
     * 测试内容：在没有未结束场次时删除秒杀活动应成功，并确认数据库中该活动记录被真正清除。
     */
    @Test
    void deletePromotionAfterSessionsRemoved() {
        // 1. 确认 coupon 服务可用，并准备一个唯一标题和活动起止时间
        String base = requireService("gulimall-coupon");
        String title = PROMOTION_TITLE_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() - 60 * 60 * 1000L);
        Date end = new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        // 2. 保存一个没有关联未结束场次的新活动，再查出它的 id
        assertOk(postServiceJson(base, "/coupon/seckillpromotion/save",
                promotionBody(title, start, end)));
        long promotionId = findPromotionId(title);

        // 3. 调用删除接口删掉这个活动，再直接查库确认记录数变成 0，证明删除真实落库
        assertOk(postServiceJson(base, "/coupon/seckillpromotion/delete", List.of(promotionId)));
        Integer count = jdbc("gulimall_sms").queryForObject(
                "SELECT COUNT(*) FROM sms_seckill_promotion WHERE id = ?",
                Integer.class, promotionId);
        assertEquals(0, count);
    }

    private Map<String, Object> promotionBody(String title, Date start, Date end) {
        // 1. 把活动标题、起止时间、状态和创建人组装成接口需要的请求体
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("startTime", format.format(start));
        body.put("endTime", format.format(end));
        body.put("status", 1);
        body.put("userId", 1L);
        return body;
    }

    private long findPromotionId(String title) {
        // 1. 按唯一标题从数据库倒序取最新一条活动 id，并断言确实查到了记录
        Long id = jdbc("gulimall_sms").queryForObject(
                "SELECT id FROM sms_seckill_promotion WHERE title = ? ORDER BY id DESC LIMIT 1",
                Long.class, title);
        assertNotNull(id);
        return id;
    }
}
