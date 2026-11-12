package com.atguigu.gulimall.api.product;

import lombok.Data;

/**
 * @ClassName: AttrRespVo
 * @Package: com.atguigu.gulimall.product.vo
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/9 - 09:28
 * @Version: v1.0
 */
@Data
public class AttrRespVo extends AttrVo {
    private String catelogName;
    private String groupName;
    private Long[] catelogPath;
}
