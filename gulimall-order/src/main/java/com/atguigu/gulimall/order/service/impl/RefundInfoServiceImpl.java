package com.atguigu.gulimall.order.service.impl;

import org.springframework.stereotype.Service;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;

import com.atguigu.gulimall.order.dao.RefundInfoDao;
import com.atguigu.gulimall.order.entity.RefundInfoEntity;
import com.atguigu.gulimall.order.service.RefundInfoService;


@Service("refundInfoService")
public class RefundInfoServiceImpl extends ServiceImpl<RefundInfoDao, RefundInfoEntity> implements RefundInfoService {

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        QueryWrapper<RefundInfoEntity> wrapper = new QueryWrapper<>();

        Object key = params.get("key");
        if (key instanceof String text && text != null && !text.isBlank()) {
            wrapper.and(w -> {
                w.like("refund_sn", text);
                try {
                    long id = Long.parseLong(text);
                    w.or().eq("order_return_id", id);
                } catch (NumberFormatException ignored) {
                    // 关键字不是数字，只按退款流水号查
                }
            });
        }
        Object refundStatus = params.get("refundStatus");
        if (refundStatus != null && !refundStatus.toString().isBlank()) {
            wrapper.eq("refund_status", refundStatus.toString());
        }
        Object refundType = params.get("refundType");
        if (refundType != null && !refundType.toString().isBlank()) {
            wrapper.eq("refund_type", refundType.toString());
        }
        wrapper.orderByDesc("id");

        IPage<RefundInfoEntity> page = this.page(
                new Query<RefundInfoEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

}