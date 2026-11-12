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
 * 流程 10：后台场次管理。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-coupon（cancelRunningSessionMovesToStopping 还需要 gulimall-ware）。
 * <p>需要关闭：本机 gulimall-seckill。
 */
@Tag("integration")
class Flow10AdminSessionManageTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：保存一个未来开始的后台场次，验证落库后 status 被强制为 1、seckill_state 为 0，且 mq_message 中登记了开始和结束两条延时消息。
     */
    @Test
    void saveSessionForcesNotStartedAndSendsDelayMessages() {
        // 1. 准备测试数据：确认 coupon 服务可用，生成唯一场次名和一小时后开始的两小时时间窗口
        String base = requireService("gulimall-coupon");
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        Date end = new Date(start.getTime() + 60 * 60 * 1000L);

        // 2. 发起调用：保存这个未来场次，确认接口调用成功
        Map<String, Object> response = postServiceJson(base, "/coupon/seckillsession/save",
                sessionBody(name, start, end, 0));
        assertOk(response);

        // 3. 查询结果：按名称找到新场次的 id 并登记以便清理
        long sessionId = findSessionId(name);
        trackSessionId(sessionId);
        Map<String, Object> row = sessionRow(sessionId);
        // 4. 断言结果：status 被服务端强制为 1（未开始），seckill_state 为 0
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertEquals(0, ((Number) row.get("seckill_state")).intValue());

        // 5. 验证副作用：可靠消息表中至少登记了开始和结束两条延时消息
        List<Map<String, Object>> messages = smsJdbc.queryForList(
                "SELECT * FROM mq_message WHERE content LIKE ?", "%\"sessionId\":" + sessionId + "%");
        assertTrue(messages.size() >= 2, "场次保存后应登记开始和结束两条延时消息");
    }

    /**
     * 测试内容：提交结束时间早于开始时间的场次保存请求，验证接口返回非 0 业务码，即非法时间被拒绝。
     */
    @Test
    void invalidTimeIsRejected() {
        // 1. 准备测试数据：确认 coupon 服务可用，构造结束时间比开始时间早 1 分钟的非法时间
        String base = requireService("gulimall-coupon");
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        Date end = new Date(start.getTime() - 60 * 1000L);

        // 2. 发起调用：保存这个时间非法的场次
        Map<String, Object> response = postServiceJson(base, "/coupon/seckillsession/save",
                sessionBody(SESSION_NAME_PREFIX + System.nanoTime(), start, end, 0));
        // 3. 断言结果：接口返回非 0 业务码，说明非法时间被拒绝
        assertNotNull(response);
        assertTrue(((Number) response.get("code")).intValue() != 0, "结束早于开始应拒绝");
    }

    /**
     * 测试内容：先修改未开始场次的时间并验证落库生效，再把场次改成已开始后再次修改，验证已开始场次的修改请求返回非 0 业务码被拒绝。
     */
    @Test
    void updateBeforeStartChangesTimeButUpdateAfterStartIsRejected() {
        // 1. 准备测试数据：确认 coupon 服务可用，先保存一个未来开始的场次并拿到 id
        String base = requireService("gulimall-coupon");
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        Date end = new Date(start.getTime() + 60 * 60 * 1000L);
        assertOk(postServiceJson(base, "/coupon/seckillsession/save",
                sessionBody(name, start, end, 0)));
        long sessionId = findSessionId(name);
        trackSessionId(sessionId);

        // 2. 发起调用：把未开始场次的开始和结束时间整体往后挪 30 分钟并确认成功
        Date newStart = new Date(start.getTime() + 30 * 60 * 1000L);
        Date newEnd = new Date(end.getTime() + 30 * 60 * 1000L);
        Map<String, Object> update = sessionBody(name, newStart, newEnd, 0);
        update.put("id", sessionId);
        assertOk(postServiceJson(base, "/coupon/seckillsession/update", update));
        // 3. 断言结果：数据库里的开始时间已更新为新时间
        assertEquals(newStart.getTime() / 1000,
                ((Date) sessionRow(sessionId).get("start_time")).getTime() / 1000);

        // 4. 修改数据：把场次时间改成已经开始的进行中状态，制造不允许修改的条件
        smsJdbc.update("UPDATE sms_seckill_session SET start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR), "
                + "end_time = DATE_ADD(NOW(), INTERVAL 1 HOUR) WHERE id = ?", sessionId);
        // 5. 发起调用：再次提交这个已开始场次的修改请求
        Map<String, Object> rejected = sessionBody(name, newStart, newEnd, 0);
        rejected.put("id", sessionId);
        Map<String, Object> response = postServiceJson(base,
                "/coupon/seckillsession/update", rejected);
        // 6. 断言结果：返回非 0 业务码，说明已开始场次不允许修改
        assertNotNull(response);
        assertTrue(((Number) response.get("code")).intValue() != 0, "已开始场次不允许修改");
    }

    /**
     * 测试内容：对一个未开始的场次执行下线操作，验证接口成功且数据库中的 status 由 1 变为 0。
     */
    @Test
    void offlineNotStartedSession() {
        // 1. 准备测试数据：确认 coupon 服务可用，保存一个未来开始的场次并拿到 id
        String base = requireService("gulimall-coupon");
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        assertOk(postServiceJson(base, "/coupon/seckillsession/save",
                sessionBody(name, start, new Date(start.getTime() + 60 * 60 * 1000L), 0)));
        long sessionId = findSessionId(name);
        trackSessionId(sessionId);

        // 2. 发起调用：对这个未开始场次调用下线接口，确认请求成功
        Map<String, Object> response = postServiceForm(base, "/coupon/seckillsession/offline",
                Map.of("id", String.valueOf(sessionId)));
        assertOk(response);
        // 3. 断言结果：数据库中的 status 由 1 变成 0，表示已下线
        assertEquals(0, ((Number) sessionRow(sessionId).get("status")).intValue());
    }

    /**
     * 测试内容：把场次改为进行中后调用取消接口，验证场次进入停止中（seckill_state 为 2）且 settle_type 被置为 2。
     */
    @Test
    void cancelRunningSessionMovesToStopping() {
        // 1. 准备测试数据：确认 coupon、ware 服务可用，保存一个未来场次并拿到 id
        String base = requireService("gulimall-coupon");
        requireService("gulimall-ware");
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        assertOk(postServiceJson(base, "/coupon/seckillsession/save",
                sessionBody(name, start, new Date(start.getTime() + 60 * 60 * 1000L), 0)));
        long sessionId = findSessionId(name);
        trackSessionId(sessionId);
        // 2. 修改数据：把场次时间调整成进行中，并把 seckill_state 置为 1
        smsJdbc.update("UPDATE sms_seckill_session SET start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR), "
                + "end_time = DATE_ADD(NOW(), INTERVAL 1 HOUR), seckill_state = 1 WHERE id = ?",
                sessionId);

        // 3. 发起调用：调用取消接口，确认请求成功
        Map<String, Object> response = postServiceForm(base, "/coupon/seckillsession/cancel",
                Map.of("id", String.valueOf(sessionId)));
        assertOk(response);
        // 4. 断言结果：场次进入停止中（seckill_state=2），settle_type 被置为 2
        Map<String, Object> row = sessionRow(sessionId);
        assertEquals(2, ((Number) row.get("seckill_state")).intValue());
        assertEquals(2, ((Number) row.get("settle_type")).intValue());
    }

    /**
     * 测试内容：删除一个未开始的场次，验证接口成功且 sms_seckill_session 中对应记录被清除。
     */
    @Test
    void deleteNotStartedSessionRemovesRows() {
        // 1. 准备测试数据：确认 coupon 服务可用，保存一个未来开始的场次并拿到 id
        String base = requireService("gulimall-coupon");
        String name = SESSION_NAME_PREFIX + System.nanoTime();
        Date start = new Date(System.currentTimeMillis() + 60 * 60 * 1000L);
        assertOk(postServiceJson(base, "/coupon/seckillsession/save",
                sessionBody(name, start, new Date(start.getTime() + 60 * 60 * 1000L), 0)));
        long sessionId = findSessionId(name);
        trackSessionId(sessionId);

        // 2. 发起调用：按 id 删除这个未开始场次，确认请求成功
        Map<String, Object> response = postServiceJson(base, "/coupon/seckillsession/delete",
                List.of(sessionId));
        assertOk(response);
        // 3. 断言结果：场次表中已经查不到这条记录
        Integer count = smsJdbc.queryForObject(
                "SELECT COUNT(*) FROM sms_seckill_session WHERE id = ?", Integer.class, sessionId);
        assertEquals(0, count);
    }

    private Map<String, Object> sessionBody(String name, Date start, Date end, int ignoredState) {
        // 1. 把场次名称和起止时间格式化成保存接口需要的请求体
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("startTime", format.format(start));
        body.put("endTime", format.format(end));
        body.put("promotionId", null);
        return body;
    }

    private long findSessionId(String name) {
        // 1. 按场次名称从数据库查出 id 最大（最新）的一条记录
        Long id = smsJdbc.queryForObject(
                "SELECT id FROM sms_seckill_session WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, name);
        // 2. 断言确实查到了 id，再返回给用例使用
        assertNotNull(id);
        return id;
    }

    private Map<String, Object> sessionRow(long sessionId) {
        // 1. 按 id 查出秒杀场次表的整行数据，供用例断言使用
        return smsJdbc.queryForMap("SELECT * FROM sms_seckill_session WHERE id = ?", sessionId);
    }
}
