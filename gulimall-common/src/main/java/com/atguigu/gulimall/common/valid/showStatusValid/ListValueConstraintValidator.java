package com.atguigu.gulimall.common.valid.showStatusValid;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.Set;

/**
 * @ClassName: ListValueConstraintValidator
 * @Package: com.atguigu.gulimall.common.valid
 * @Description: 校验器
 * @Author: 刘家旭
 * @Create: 2026/9/3 - 12:42
 * @Version: v1.0
 */
public class ListValueConstraintValidator implements ConstraintValidator<ListValue, Integer> {

    private final Set<Integer> set = new HashSet<>();

    /**
     * 初始化验证器，为isValid调用做准备
     * @param constraintAnnotation 给定约束声明的注释实例
     */
    @Override
    public void initialize(ListValue constraintAnnotation) {
        int[] value = constraintAnnotation.values();
        for (int i : value) {
            set.add(i);
        }

    }

    /**
     * 验证给定的值是否有效
     * @param value 要验证的值
     * @param context 对约束进行评估的上下文
     *
     * @return 如果值有效，则为true；否则为false
     */
    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        return set.contains(value);
    }
}
