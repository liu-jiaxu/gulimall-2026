package com.atguigu.gulimall.common.enums.product;

/**
 * @ClassName: StatusEnum
 * @Package: com.atguigu.gulimall.common.enums.product
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 13:05
 * @Version: v1.0
 */
public enum StatusEnum {
    NEW_SPU(0, "新建"),
    SPU_UP(1, "商品上架"),
    SPU_DOWN(1, "商品下架");

    private final int code;
    private final String msg;

    StatusEnum(int code, String msg) {
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
