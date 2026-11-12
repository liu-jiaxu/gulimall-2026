package com.atguigu.gulimall.order.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;



/**
 * 订单
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:07:07
 */
@RestController
@RequestMapping("order/order")
public class OrderController {
    @Autowired
    private OrderService orderService;

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("order:order:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = orderService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 后台交易统计。
     *
     * <p>不传时间时默认最近 30 天；keyword 按商品名称过滤。
     */
    @RequestMapping("/stat")
    @RequiresPermissions("order:order:list")
    public R stat(@RequestParam(value = "startTime", required = false) String startTime,
                  @RequestParam(value = "endTime", required = false) String endTime,
                  @RequestParam(value = "keyword", required = false) String keyword) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String end = (endTime == null || endTime.isBlank())
                ? LocalDateTime.now().format(formatter) : endTime;
        String start = (startTime == null || startTime.isBlank())
                ? LocalDate.now().minusDays(29).atStartOfDay().format(formatter) : startTime;

        return R.ok().put("stat", orderService.getOrderStat(start, end, keyword));
    }

    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("order:order:info")
    public R info(@PathVariable("id") Long id){
		OrderEntity order = orderService.getById(id);

        return R.ok().put("order", order);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("order:order:save")
    public R save(@RequestBody OrderEntity order){
		orderService.save(order);

        return R.ok();
    }

    /**
     * 修改订单。
     *
     * <p>后台只允许改关键字段，金额、商品、下单人等一律不允许通过该接口修改：
     * 订单状态、物流公司、物流单号、订单备注、收货人信息。
     */
    @RequestMapping("/update")
    @RequiresPermissions("order:order:update")
    public R update(@RequestBody OrderEntity order){
        if (order == null || order.getId() == null) {
            return R.error("订单 id 不能为空");
        }

        UpdateWrapper<OrderEntity> update = new UpdateWrapper<>();
        update.eq("id", order.getId());

        if (order.getStatus() != null) {
            update.set("status", order.getStatus());
            // 置为「已发货」时补发货时间；置为「已完成」时补收货时间
            if (order.getStatus() == 2) {
                update.setSql("delivery_time = COALESCE(delivery_time, NOW())");
            } else if (order.getStatus() == 3) {
                update.setSql("receive_time = COALESCE(receive_time, NOW())");
            }
        }
        if (order.getDeliveryCompany() != null) {
            update.set("delivery_company", order.getDeliveryCompany());
        }
        if (order.getDeliverySn() != null) {
            update.set("delivery_sn", order.getDeliverySn());
        }
        if (order.getNote() != null) {
            update.set("note", order.getNote());
        }
        if (order.getReceiverName() != null) {
            update.set("receiver_name", order.getReceiverName());
        }
        if (order.getReceiverPhone() != null) {
            update.set("receiver_phone", order.getReceiverPhone());
        }
        if (order.getReceiverProvince() != null) {
            update.set("receiver_province", order.getReceiverProvince());
        }
        if (order.getReceiverCity() != null) {
            update.set("receiver_city", order.getReceiverCity());
        }
        if (order.getReceiverRegion() != null) {
            update.set("receiver_region", order.getReceiverRegion());
        }
        if (order.getReceiverDetailAddress() != null) {
            update.set("receiver_detail_address", order.getReceiverDetailAddress());
        }

        if (update.getSqlSet() == null || update.getSqlSet().isBlank()) {
            return R.error("没有可修改的字段");
        }
        orderService.getBaseMapper().update(null, update);

        return R.ok();
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("order:order:delete")
    public R delete(@RequestBody Long[] ids){
		orderService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

}
