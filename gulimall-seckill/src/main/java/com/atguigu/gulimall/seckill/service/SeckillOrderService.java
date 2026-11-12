package com.atguigu.gulimall.seckill.service;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.seckill.vo.SeckillOrderResultVo;
import com.atguigu.gulimall.seckill.vo.SeckillOrderSubmitVo;

import java.math.BigDecimal;

/**
 * 秒杀抢购与结果查询
 */
public interface SeckillOrderService {

    /** 抢购：Redis 原子预扣 + 落预扣记录 + 发建单消息 */
    SeckillOrderResultVo submit(SeckillOrderSubmitVo vo, MemberResponseVo member);

    /** 查询抢购结果，orderSn 和 requestId 二选一 */
    SeckillOrderResultVo getResult(String orderSn, String requestId, Long memberId);

    /** 消费端建单成功：状态 0→1，回填订单 id 和金额 */
    void onBuildSuccess(String orderSn, Long orderId, BigDecimal payAmount);

    /** 建单明确失败：状态 0→3，回补 Redis 库存和限购 */
    void failAndRelease(String orderSn, String reason);

    /**
     * 订单取消/超时后归还库存：场次仍进行中回秒杀池，否则回普通池。
     *
     * @return 秒杀单记录；普通订单没有记录时返回 null
     */
    SeckillOrderResultVo releaseByOrderSn(String orderSn, String reason);

    /** 支付成功：把秒杀单从已建单条件更新为已支付 */
    void markPaid(String orderSn);
}
