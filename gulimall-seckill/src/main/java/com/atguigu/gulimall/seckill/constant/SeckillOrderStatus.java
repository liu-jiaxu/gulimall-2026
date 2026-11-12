package com.atguigu.gulimall.seckill.constant;

/**
 * 秒杀订单预扣状态
 */
public class SeckillOrderStatus {

    /** Redis 已扣、建单消息已登记，等待 order 建单 */
    public static final int WAIT_BUILD = 0;

    /** order 已建单，等待支付 */
    public static final int BUILT = 1;

    /** 已取消/超时，库存已释放 */
    public static final int RELEASED = 2;

    /** 建单失败，库存已回补 */
    public static final int BUILD_FAILED = 3;

    /** 已支付 */
    public static final int PAID = 4;

    /** 释放方式：回秒杀池 */
    public static final int RELEASE_TO_SECKILL = 1;

    /** 释放方式：回普通可售池 */
    public static final int RELEASE_TO_NORMAL = 2;

    public static String text(Integer status) {
        // 1. 状态为空说明数据缺失，直接返回“未知”，避免自动拆箱时抛空指针
        if (status == null) {
            return "未知";
        }
        // 2. 按状态码翻译成前端能直接展示的中文文案，不认识的状态也兜底为“未知”
        return switch (status) {
            case WAIT_BUILD -> "排队建单中";
            case BUILT -> "待支付";
            case RELEASED -> "已取消";
            case BUILD_FAILED -> "建单失败";
            case PAID -> "已支付";
            default -> "未知";
        };
    }

    private SeckillOrderStatus() {
    }
}
