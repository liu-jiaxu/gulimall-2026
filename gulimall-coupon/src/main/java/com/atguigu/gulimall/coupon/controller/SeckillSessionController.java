package com.atguigu.gulimall.coupon.controller;

import java.util.Arrays;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.service.SeckillSessionService;
import com.atguigu.gulimall.coupon.service.SeckillSettleService;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 秒杀活动场次
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
@RestController
@RequestMapping("coupon/seckillsession")
public class SeckillSessionController {
    @Autowired
    private SeckillSessionService seckillSessionService;

    @Autowired
    private SeckillSettleService seckillSettleService;

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("coupon:seckillsession:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = seckillSessionService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("coupon:seckillsession:info")
    public R info(@PathVariable("id") Long id){
		SeckillSessionEntity seckillSession = seckillSessionService.getById(id);

        return R.ok().put("seckillSession", seckillSession);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("coupon:seckillsession:save")
    public R save(@RequestBody SeckillSessionEntity seckillSession){
        try {
            // 1. 新增场次：状态固定未开始，创建时间由后端生成
            seckillSessionService.saveSession(seckillSession);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("coupon:seckillsession:update")
    public R update(@RequestBody SeckillSessionEntity seckillSession){
        try {
            // 1. 修改场次：状态不能被前端改，时间重新校验
            seckillSessionService.updateSession(seckillSession);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 下架场次：只把 status 改成 0，不动库存和 Redis
     */
    @RequestMapping("/offline")
    @RequiresPermissions("coupon:seckillsession:update")
    public R offline(@RequestParam("id") Long id) {
        try {
            // 1. 只有未开始/已结束/已取消的场次能下架
            seckillSettleService.offlineSession(id);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 取消场次：只有进行中能取消，先变停止中，30 分钟后变已取消
     */
    @RequestMapping("/cancel")
    @RequiresPermissions("coupon:seckillsession:update")
    public R cancel(@RequestParam("id") Long id) {
        try {
            // 1. 只有进行中的场次可以取消
            seckillSettleService.cancelSession(id);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 删除场次：物理删除场次和关联；未开始先释放库存，已结束/已取消只删记录
     */
    @RequestMapping("/delete")
    @RequiresPermissions("coupon:seckillsession:delete")
    public R delete(@RequestBody Long[] ids){
        if (ids == null || ids.length == 0) {
            return R.ok();
        }
        try {
            // 1. 物理删除场次和关联
            seckillSettleService.deleteSessions(Arrays.asList(ids));
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

}
