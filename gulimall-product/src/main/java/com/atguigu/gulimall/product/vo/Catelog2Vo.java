package com.atguigu.gulimall.product.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @ClassName: BrandVo
 * @Package: com.atguigu.gulimall.product.vo
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/17 - 10:16
 * @Version: v1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Catelog2Vo {

    /**
     * 一级父分类的id
     */
    private String catelog1Id;

    /**
     * 三级子分类
     */
    private List<Catelog3Vo> catelog3List;

    private String id;

    private String name;


    /**
     * 三级分类vo
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Catelog3Vo {

        /**
         * 父分类、二级分类id
         */
        private String catelog2Id;

        private String id;

        private String name;
    }

}
