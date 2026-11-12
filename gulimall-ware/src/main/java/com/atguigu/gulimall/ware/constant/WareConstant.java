package com.atguigu.gulimall.ware.constant;

/**
 * 库存系统常量枚举
 *
 * @author liujiaxu
 */
public class WareConstant {

    // ==================== 库存工作单状态 ====================

    /** 已锁定：库存已经锁住，等待支付或超时解锁 */
    public static final int TASK_STATUS_LOCKED = 1;

    /** 已解锁：订单超时/取消，锁定库存已经还回去 */
    public static final int TASK_STATUS_RELEASED = 2;

    /** 已扣减：支付成功，锁定库存转成真实扣减 */
    public static final int TASK_STATUS_DEDUCTED = 3;

    /** 已退货：售后退货处理完成，库存已经回补（同一明细只回补一次） */
    public static final int TASK_STATUS_RETURNED = 4;

    // ==================== 库存工作单业务类型 ====================

    /** 普通订单：超时/取消走 ware 自己的延时消息释放 */
    public static final int BIZ_TYPE_NORMAL = 0;

    /** 秒杀订单：超时/取消由 seckill 决定回秒杀池还是普通池 */
    public static final int BIZ_TYPE_SECKILL = 1;

    /** 采购单状态枚举 */
    public enum PurchaseStatusEnum {
        CREATED(0, "新建"),
        ASSIGNED(1, "已分配"),
        RECEIVE(2, "已领取"),
        FINISH(3, "已完成"),
        HASERROR(4, "有异常");

        private int code;
        private String msg;

        PurchaseStatusEnum(int code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public int getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }
    }

    /** 采购需求枚举 */
    public enum PurchaseDetailStatusEnum {
        CREATED(0, "新建"),
        ASSIGNED(1, "已分配"),
        BUYING(2, "正在采购"),
        FINISH(3, "已完成"),
        HASERROR(4, "采购失败");

        private int code;
        private String msg;

        PurchaseDetailStatusEnum(int code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public int getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }
    }
}
