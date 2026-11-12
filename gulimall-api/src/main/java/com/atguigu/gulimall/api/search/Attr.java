package com.atguigu.gulimall.api.search;

import lombok.Data;

/**
 * @ClassName: Attr
 * @Package: com.atguigu.gulimall.api.to.es
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 10:01
 * @Version: v1.0
 */
@Data
public class Attr{
    private Long attrId;
    private String attrName;
    private String attrValue;
}
