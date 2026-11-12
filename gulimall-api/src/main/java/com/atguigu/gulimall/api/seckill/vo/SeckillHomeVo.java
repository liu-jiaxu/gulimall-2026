package com.atguigu.gulimall.api.seckill.vo;

import lombok.Data;

import java.util.List;

/**
 * 首页/秒杀页展示的一个场次及其商品
 */
@Data
public class SeckillHomeVo {

    private Long sessionId;

    private String sessionName;

    /** 毫秒时间戳 */
    private Long startTime;

    private Long endTime;

    /** 0未开始 1进行中 2停止中 3已结束 4已取消 */
    private Integer state;

    private List<SeckillSkuVo> skus;
}
