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
 * 流程 21：秒杀商品通知订阅。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-coupon。
 * <p>需要关闭：本机 gulimall-seckill。
 * <p>当前只有后台 CRUD，没有订阅/发送/短信/邮件逻辑。
 */
@Tag("integration")
class Flow21SkuNoticeCrudTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：验证秒杀商品通知订阅的保存、列表、详情、修改与删除，并确认通知类型更新和记录删除真实落库。
     */
    @Test
    void noticeCrudWorks() {
        // 1. 确认 coupon 服务可用，组装一条通知订阅请求并调用保存接口
        String base = requireService("gulimall-coupon");
        Map<String, Object> body = noticeBody(0);
        assertOk(postServiceJson(base, "/coupon/seckillskunotice/save", body));

        // 2. 查询最新一条通知订阅，确认记录已经落库，拿到 id 并验证商品 id 正确
        Map<String, Object> row = noticeRow();
        assertNotNull(row, "通知订阅没有落库");
        long id = ((Number) row.get("id")).longValue();
        assertEquals(TEST_SKU_ID, ((Number) row.get("sku_id")).longValue());

        // 3. 分别调用分页列表和详情接口，确认刚保存的订阅可以被正常查询到
        assertOk(getServiceJson(base, "/coupon/seckillskunotice/list?page=1&limit=10"));
        assertOk(getServiceJson(base, "/coupon/seckillskunotice/info/" + id));

        // 4. 调用修改接口把通知类型改成 1，再查库确认 notice_type 已更新为 1
        Map<String, Object> update = new HashMap<>(body);
        update.put("id", id);
        update.put("noticeType", 1);
        assertOk(postServiceJson(base, "/coupon/seckillskunotice/update", update));
        assertEquals(1, ((Number) noticeRow().get("notice_type")).intValue());

        // 5. 调用删除接口删掉这条订阅，再按 id 查库确认记录数为 0，清理测试数据并验证删除生效
        assertOk(postServiceJson(base, "/coupon/seckillskunotice/delete", List.of(id)));
        assertEquals(0, smsJdbc.queryForObject(
                "SELECT COUNT(*) FROM sms_seckill_sku_notice WHERE id = ?",
                Integer.class, id));
    }

    /**
     * 测试内容：保存通知订阅前后 mq_message 中该会员相关消息数量不变，确认当前订阅不会触发任何发送消息。
     */
    @Test
    void savingNoticeDoesNotSendAnything() {
        // 1. 确认 coupon 服务可用，作为后续保存通知订阅的调用目标
        String base = requireService("gulimall-coupon");
        // 2. 保存前先统计 mq_message 中与该会员相关的消息数量，作为对比基线
        int before = smsJdbc.queryForObject(
                "SELECT COUNT(*) FROM mq_message WHERE content LIKE ?",
                Integer.class, "%" + TEST_MEMBER_ID + "%");
        // 3. 调用保存接口新增一条通知订阅，触发当前 CRUD 逻辑
        assertOk(postServiceJson(base, "/coupon/seckillskunotice/save", noticeBody(0)));
        // 4. 再统计一次会员相关消息数量，确认保存订阅没有产生任何发送消息
        int after = smsJdbc.queryForObject(
                "SELECT COUNT(*) FROM mq_message WHERE content LIKE ?",
                Integer.class, "%" + TEST_MEMBER_ID + "%");
        assertEquals(before, after, "通知订阅当前不应产生任何发送消息");
        // 5. 确认通知订阅本身已经落库，避免“没发消息”其实是因为保存也失败了
        assertTrue(noticeRow() != null);
    }

    private Map<String, Object> noticeBody(int noticeType) {
        // 1. 把会员、商品、订阅时间和通知类型组装成保存接口需要的请求体
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> body = new HashMap<>();
        body.put("memberId", TEST_MEMBER_ID);
        body.put("skuId", TEST_SKU_ID);
        body.put("sessionId", null);
        body.put("subcribeTime", format.format(new Date()));
        body.put("noticeType", noticeType);
        return body;
    }

    private Map<String, Object> noticeRow() {
        // 1. 按测试会员查最新一条通知订阅，没有记录时返回 null 供调用方断言
        List<Map<String, Object>> rows = smsJdbc.queryForList(
                "SELECT * FROM sms_seckill_sku_notice WHERE member_id = ? ORDER BY id DESC LIMIT 1",
                TEST_MEMBER_ID);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
