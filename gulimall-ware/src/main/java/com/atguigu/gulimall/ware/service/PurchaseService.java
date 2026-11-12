package com.atguigu.gulimall.ware.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.ware.entity.PurchaseEntity;
import com.atguigu.gulimall.ware.vo.MergeVo;
import com.atguigu.gulimall.ware.vo.PurchaseDoneVo;

import java.util.List;
import java.util.Map;

/**
 * 采购信息
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
public interface PurchaseService extends IService<PurchaseEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 查询未领取的采购单（新建、已分配）
     */
    PageUtils queryPageUnreceive(Map<String, Object> params);

    /**
     * 合并采购需求到采购单；未指定采购单时自动新建
     */
    void mergePurchase(MergeVo mergeVo);

    /**
     * 领取采购单
     */
    void received(List<Long> ids);

    /**
     * 完成采购
     */
    void done(PurchaseDoneVo vo);
}
