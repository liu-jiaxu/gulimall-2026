package com.atguigu.gulimall.common.constant;

import java.util.Date;

/**
 * 秒杀场次状态
 *
 * <p>0 未开始、1 进行中、2 停止中、3 已结束、4 已取消。
 * 数据库存的是人工状态；读的时候再结合开始/结束时间算“有效状态”。
 */
public class SeckillSessionState {

    public static final int NOT_STARTED = 0;
    public static final int RUNNING = 1;
    public static final int STOPPING = 2;
    public static final int ENDED = 3;
    public static final int CANCELLED = 4;

    /** 停止中之后的最终状态：自然结束 */
    public static final int SETTLE_TYPE_END = 1;

    /** 停止中之后的最终状态：管理员取消 */
    public static final int SETTLE_TYPE_CANCEL = 2;

    /**
     * 计算场次当前的有效状态
     *
     * @param storedState 数据库里存的状态
     * @param startTime   场次开始时间
     * @param endTime     场次结束时间
     * @param now         当前时间，传 null 就用系统当前时间
     * @return 有效状态
     */
    public static int effectiveState(Integer storedState, Date startTime, Date endTime, Date now) {
        // 1. 人工状态优先：停止中、已结束、已取消，直接返回存的值
        int state = storedState == null ? NOT_STARTED : storedState;
        if (state == STOPPING || state == ENDED || state == CANCELLED) {
            return state;
        }

        // 2. 时间不完整时，退化成存的状态
        if (startTime == null || endTime == null) {
            return state;
        }
        if (now == null) {
            now = new Date();
        }

        // 3. 还没到开始时间：未开始
        if (now.before(startTime)) {
            return NOT_STARTED;
        }

        // 4. 已经到了结束时间：已结束
        if (!now.before(endTime)) {
            return ENDED;
        }

        // 5. 开始时间和结束时间之间：进行中
        return RUNNING;
    }

    /**
     * 场次是否启用且还没开始
     *
     * <p>关联商品的增删改、停用场次等操作，只允许在这个状态下做。
     */
    public static boolean isEnabledAndNotStarted(Integer status, Integer storedState,
                                                 Date startTime, Date endTime, Date now) {
        // 1. status=1 才允许
        if (!Integer.valueOf(1).equals(status)) {
            return false;
        }
        // 2. 有效状态必须是未开始
        return effectiveState(storedState, startTime, endTime, now) == NOT_STARTED;
    }

    private SeckillSessionState() {
    }
}
