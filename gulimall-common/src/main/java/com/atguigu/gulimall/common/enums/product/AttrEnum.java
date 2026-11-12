package com.atguigu.gulimall.common.enums.product;

/**
 * @ClassName: AttrEnum
 * @Package: com.atguigu.gulimall.common.enums.product
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/9 - 16:02
 * @Version: v1.0
 */
public enum AttrEnum {
    ATTR_TYPE_BASE(1, "基本属性"), ATTR_TYPE_SALE(0, "销售属性");

    private final int code;
    private final String msg;

    AttrEnum(int code, String msg) {
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
