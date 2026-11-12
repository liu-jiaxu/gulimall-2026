package com.atguigu.gulimall.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.order.entity.OrderReturnApplyEntity;

import java.util.Map;

/**
 * 订单退货申请
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:07:07
 */
public interface OrderReturnApplyService extends IService<OrderReturnApplyEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 用户提交退货申请（只允许对已发货/已完成的订单发起）。
     */
    OrderReturnApplyEntity applyReturn(String orderSn, Long skuId, Integer count,
                                       String reason, String description);

    /**
     * 管理员处理退货单：只更新关键字段；状态改为 2（已完成）时
     * 自动原路退款 + 回补库存 + 写退款记录。
     */
    void handle(OrderReturnApplyEntity form);
}

