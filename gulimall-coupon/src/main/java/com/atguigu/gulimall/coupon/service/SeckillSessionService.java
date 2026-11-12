package com.atguigu.gulimall.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;

import java.util.Map;

/**
 * 秒杀活动场次
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
public interface SeckillSessionService extends IService<SeckillSessionEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /** 新增场次：校验时间，状态一律从未开始开始 */
    void saveSession(SeckillSessionEntity session);

    /** 修改场次：校验时间，不允许前端直接改状态 */
    void updateSession(SeckillSessionEntity session);

    /** 按存的状态 + 开始/结束时间算当前有效状态 */
    int effectiveState(SeckillSessionEntity session);

    /**
     * 到点开始：把未开始场次落库成进行中。
     * 旧消息（开始时间和当前不一致）直接忽略。
     */
    void markRunning(Long sessionId, Long expectedStartTimeMillis);
}

