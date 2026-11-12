package com.atguigu.gulimall.order.dao;

import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.vo.OrderStatVo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:07:07
 */
@Mapper
public interface OrderDao extends BaseMapper<OrderEntity> {

    /** 交易统计汇总：交易额 / 订单数 / 商品件数 */
    OrderStatVo selectSummary(@Param("start") String start,
                              @Param("end") String end,
                              @Param("keyword") String keyword);

    /** 每日交易趋势 */
    List<OrderStatVo.TrendPoint> selectDailyTrend(@Param("start") String start,
                                                  @Param("end") String end,
                                                  @Param("keyword") String keyword);

    /** 商品销量 Top N */
    List<OrderStatVo.ProductRank> selectTopProducts(@Param("start") String start,
                                                    @Param("end") String end,
                                                    @Param("keyword") String keyword,
                                                    @Param("limit") int limit);

    /** 订单状态分布（已支付订单） */
    List<OrderStatVo.NameValue> selectStatusDistribution(@Param("start") String start,
                                                         @Param("end") String end,
                                                         @Param("keyword") String keyword);

    /** 支付方式分布（已支付订单） */
    List<OrderStatVo.NameValue> selectPayTypeDistribution(@Param("start") String start,
                                                          @Param("end") String end,
                                                          @Param("keyword") String keyword);
}
