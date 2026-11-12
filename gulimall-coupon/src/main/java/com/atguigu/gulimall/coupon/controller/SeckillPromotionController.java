package com.atguigu.gulimall.coupon.controller;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.service.SeckillSessionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.atguigu.gulimall.coupon.entity.SeckillPromotionEntity;
import com.atguigu.gulimall.coupon.service.SeckillPromotionService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 秒杀活动
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
@RestController
@RequestMapping("coupon/seckillpromotion")
public class SeckillPromotionController {
    @Autowired
    private SeckillPromotionService seckillPromotionService;

    @Autowired
    private SeckillSessionService seckillSessionService;

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("coupon:seckillpromotion:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = seckillPromotionService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("coupon:seckillpromotion:info")
    public R info(@PathVariable("id") Long id){
		SeckillPromotionEntity seckillPromotion = seckillPromotionService.getById(id);

        return R.ok().put("seckillPromotion", seckillPromotion);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("coupon:seckillpromotion:save")
    public R save(@RequestBody SeckillPromotionEntity seckillPromotion){
		seckillPromotionService.save(seckillPromotion);

        return R.ok();
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("coupon:seckillpromotion:update")
    public R update(@RequestBody SeckillPromotionEntity seckillPromotion){
        // 1. 活动下还有未结束场次时，不能修改/停用，避免活动没了但场次和库存还在跑
        if (seckillPromotion.getId() == null || hasUnfinishedSessions(seckillPromotion.getId())) {
            return R.error("该活动下还有未结束场次，请先停止或删除场次");
        }
		seckillPromotionService.updateById(seckillPromotion);

        return R.ok();
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("coupon:seckillpromotion:delete")
    public R delete(@RequestBody Long[] ids){
        // 1. 任意一个活动下还有未结束场次，就拒绝删除
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && hasUnfinishedSessions(id)) {
                    return R.error("活动 " + id + " 下还有未结束场次，请先停止或删除场次");
                }
            }
        }
		seckillPromotionService.removeByIds(Arrays.asList(ids));

        return R.ok();
    }

    private boolean hasUnfinishedSessions(Long promotionId) {
        List<SeckillSessionEntity> sessions = seckillSessionService.list(
                new QueryWrapper<SeckillSessionEntity>().eq("promotion_id", promotionId));
        Date now = new Date();
        for (SeckillSessionEntity session : sessions) {
            if (!Integer.valueOf(1).equals(session.getStatus())) {
                continue;
            }
            int state = SeckillSessionState.effectiveState(
                    session.getSeckillState(), session.getStartTime(), session.getEndTime(), now);
            if (state != SeckillSessionState.ENDED && state != SeckillSessionState.CANCELLED) {
                return true;
            }
        }
        return false;
    }

}
