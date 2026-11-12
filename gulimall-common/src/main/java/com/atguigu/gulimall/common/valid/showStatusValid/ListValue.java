package com.atguigu.gulimall.common.valid.showStatusValid;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * @ClassName: ListValue
 * @Package: com.atguigu.gulimall.common.valid
 * @Description: 自定义注解
 * @Author: 刘家旭
 * @Create: 2026/9/3 - 12:36
 * @Version: v1.0
 */
@Documented
// 在校验注解的 @Constraint 注解上关联校验器
@Constraint(validatedBy = {ListValueConstraintValidator.class})
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ListValue {

    // 后续有多个自定义校验和校验器时，要根据校验规则一一分类，尽量不要合并，违反单一职责
    // 常用校验器：手机号、身份证、枚举值

    // 错误信息定义位置
    String message() default "{com.atguigu.gulimall.common.valid.ListValue.message}";

    // 分组校验
    Class<?>[] groups() default {};

    // 负载
    Class<? extends Payload>[] payload() default {};

    // 定义一个属性，允许传入多个值
    int[] values() default {};
}
