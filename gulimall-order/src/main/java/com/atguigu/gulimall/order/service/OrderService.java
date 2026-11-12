package com.atguigu.gulimall.order.service;

import com.atguigu.gulimall.api.order.vo.OrderConfirmVo;
import com.atguigu.gulimall.api.order.vo.OrderSubmitVo;
import com.atguigu.gulimall.api.order.vo.OrderVo;
import com.atguigu.gulimall.api.order.vo.SeckillOrderCreateVo;
import com.atguigu.gulimall.order.vo.OrderListVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.order.entity.OrderEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 订单
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:07:07
 */
public interface OrderService extends IService<OrderEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 订单确认页所需数据
     * @return 订单确认页所需数据
     */
    OrderConfirmVo confirmOrder();

    OrderVo submitOrder(OrderSubmitVo orderSubmitVo);

    /**
     * 用户主动取消订单：条件更新 status 0 -> 4，并通知 ware 释放锁定库存。
     *
     * <p>库存释放失败只记日志，由延时消息 / StockReleaseTask 兜底。
     */
    void cancelOrder(String orderSn);

    /**
     * 支付宝支付成功：把订单置为已支付，并写入支付信息。
     *
     * <p>只做数据库操作；ware 的库存扣减由调用方在本地事务提交后触发。
     *
     * @return true 表示订单已经是/刚刚变为已支付；false 表示订单不存在、
     *         金额不一致或订单已关闭
     */
    boolean markOrderPaid(String orderSn, String tradeNo,
                          BigDecimal paidAmount, String callbackContent);

    /**
     * 支付成功后需要从购物车删除的商品 ID 列表。
     */
    List<Long> getOrderSkuIds(String orderSn);

    /**
     * 订单是否已经关闭（status=4）。
     * 用于支付回调到达时判断是否需要走“超时后付款”的自动退款。
     */
    boolean isOrderClosed(String orderSn);

    /**
     * 订单列表页：按当前用户、状态、关键字分页查询，并带出订单明细。
     */
    IPage<OrderListVo> queryUserOrders(Long memberId, Integer status,
                                       String keyword, long pageNo, long pageSize);

    /**
     * 确认收货：只有已发货（status=2）的订单可以确认。
     */
    boolean confirmReceive(String orderSn);

    /**
     * 后台交易统计：交易额、订单数、商品销量、状态/支付方式分布。
     *
     * @param start   开始时间（yyyy-MM-dd HH:mm:ss）
     * @param end     结束时间（yyyy-MM-dd HH:mm:ss）
     * @param keyword 商品名称关键字，可为空
     */
    com.atguigu.gulimall.order.vo.OrderStatVo getOrderStat(String start, String end, String keyword);

    /**
     * 秒杀建单：seckill 消费建单消息后调用。
     *
     * <p>订单号由 seckill 预生成，按 orderSn 幂等；
     * 建单同时调 ware 把 seckill_stock_locked 转成 stock_locked。
     */
    Map<String, Object> createSeckillOrder(SeckillOrderCreateVo vo);

    /**
     * 秒杀单状态查询（seckill 对账用）。
     */
    Map<String, Object> getSeckillOrderStatus(String orderSn);

    /**
     * 批量查订单状态：orderSn -> status，不存在的订单不返回。
     */
    Map<String, Integer> batchGetOrderStatus(List<String> orderSns);

    /**
     * 订单关闭后的库存处理：普通订单调 ware 释放，秒杀订单交给 seckill 决定回哪个池。
     *
     * <p>释放失败只记日志，普通订单由 ware 延时消息兜底，秒杀订单由对账任务兜底。
     */
    void afterOrderClosed(OrderEntity order, String reason);

    /**
     * 是否秒杀订单（source_type=2）。支付成功清购物车时要跳过秒杀订单。
     */
    boolean isSeckillOrder(String orderSn);

}

