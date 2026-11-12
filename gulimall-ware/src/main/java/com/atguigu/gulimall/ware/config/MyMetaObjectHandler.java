package com.atguigu.gulimall.ware.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 字段自动填充处理器
 * <p>
 * 配合实体上的 {@code @TableField(fill = FieldFill.INSERT)} /
 * {@code FieldFill.INSERT_UPDATE} 使用，例如 {@code PurchaseEntity.createTime/updateTime}。
 *
 * @author liujiaxu
 */
@Slf4j
@Component // 一定要加到 IOC 容器里，否则注解不生效
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** 插入时的填充策略 */
    @Override
    public void insertFill(MetaObject metaObject) {
        log.info("start insert fill.....");
        this.setFieldValByName("createTime", new Date(), metaObject);
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }

    /** 更新时的填充策略 */
    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("start update fill.....");
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}
