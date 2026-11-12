package com.atguigu.gulimall.ware.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.ware.constant.WareConstant;
import com.atguigu.gulimall.ware.dao.PurchaseDao;
import com.atguigu.gulimall.ware.entity.PurchaseDetailEntity;
import com.atguigu.gulimall.ware.entity.PurchaseEntity;
import com.atguigu.gulimall.ware.feign.ProductFeignClient;
import com.atguigu.gulimall.ware.service.PurchaseDetailService;
import com.atguigu.gulimall.ware.service.PurchaseService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import com.atguigu.gulimall.ware.vo.MergeVo;
import com.atguigu.gulimall.ware.vo.PurchaseDoneVo;
import com.atguigu.gulimall.ware.vo.PurchaseItemDoneVo;


@Slf4j
@Service("purchaseService")
public class PurchaseServiceImpl extends ServiceImpl<PurchaseDao, PurchaseEntity> implements PurchaseService {

    @Autowired
    private PurchaseDetailService detailService;

    @Autowired
    private WareSkuService wareSkuService;

    @Autowired
    private ProductFeignClient productFeignService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(params),
                new QueryWrapper<PurchaseEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageUnreceive(Map<String, Object> params) {
        QueryWrapper<PurchaseEntity> wrapper = new QueryWrapper<>();
        // 新建 或 已分配 的采购单都还没被领取
        // 这里 or() 是平级的，只因为当前没有任何其它条件才安全；
        // 以后要再加条件，必须改成 wrapper.and(w -> w.eq(...).or().eq(...))
        wrapper.eq("status", WareConstant.PurchaseStatusEnum.CREATED.getCode())
                .or()
                .eq("status", WareConstant.PurchaseStatusEnum.ASSIGNED.getCode());

        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void mergePurchase(MergeVo mergeVo) {
        List<Long> items = mergeVo.getItems();
        if (CollectionUtils.isEmpty(items)) {
            return;
        }

        Long purchaseId = mergeVo.getPurchaseId();
        // 采购单id为 null 说明没选采购单，新建一个
        if (purchaseId == null) {
            PurchaseEntity purchaseEntity = new PurchaseEntity();
            purchaseEntity.setStatus(WareConstant.PurchaseStatusEnum.CREATED.getCode());
            this.save(purchaseEntity);
            purchaseId = purchaseEntity.getId();
        }

        // 分配：就是改采购需求的【采购单id】和【状态】
        // 不能把采购需求重复分配给不同的采购单，所以只挑还没去采购、或者采购失败的
        Long finalPurchaseId = purchaseId;
        List<PurchaseDetailEntity> list = detailService.getBaseMapper().selectBatchIds(items)
                .stream()
                .filter(entity -> entity.getStatus() < WareConstant.PurchaseDetailStatusEnum.BUYING.getCode()
                        || entity.getStatus() == WareConstant.PurchaseDetailStatusEnum.HASERROR.getCode())
                .peek(entity -> {
                    entity.setStatus(WareConstant.PurchaseDetailStatusEnum.ASSIGNED.getCode());
                    entity.setPurchaseId(finalPurchaseId);
                })
                .collect(Collectors.toList());

        detailService.updateBatchById(list);
    }

    @Transactional
    @Override
    public void received(List<Long> ids) {
        // 没有采购单id直接返回，否则下面 in() 空集合会破坏采购单状态
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }

        // 1、确认当前采购单是新建或者已分配状态，然后改成已领取
        List<PurchaseEntity> list = this.getBaseMapper().selectBatchIds(ids)
                .stream()
                .filter(entity -> entity.getStatus() <= WareConstant.PurchaseStatusEnum.ASSIGNED.getCode())
                .map(entity -> {
                    entity.setStatus(WareConstant.PurchaseStatusEnum.RECEIVE.getCode());
                    return entity;
                })
                .collect(Collectors.toList());
        this.updateBatchById(list);

        // 2、把该采购单下的所有采购需求改成正在采购
        UpdateWrapper<PurchaseDetailEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.in("purchase_id", ids);
        PurchaseDetailEntity purchaseDetailEntity = new PurchaseDetailEntity();
        purchaseDetailEntity.setStatus(WareConstant.PurchaseDetailStatusEnum.BUYING.getCode());
        // 实体里只有 status 非空，MyBatis-Plus 默认会忽略 null 字段，所以只会 update status
        detailService.update(purchaseDetailEntity, updateWrapper);
    }

    @Transactional
    @Override
    public void done(PurchaseDoneVo vo) {
        List<PurchaseItemDoneVo> items = vo.getItems();
        if (CollectionUtils.isEmpty(items)) {
            return;
        }

        // 1、根据前端发过来的信息，更新采购需求的状态
        List<PurchaseDetailEntity> updateList = new ArrayList<>();
        boolean flag = true;
        for (PurchaseItemDoneVo item : items) {
            Long detailId = item.getItemId();
            PurchaseDetailEntity detailEntity = detailService.getById(detailId);
            detailEntity.setStatus(item.getStatus());

            if (item.getStatus() == WareConstant.PurchaseDetailStatusEnum.HASERROR.getCode()) {
                // 只要有任意一项采购失败，整单就是有异常
                flag = false;
            } else {
                // 2、采购项完成，入库（调用远程服务拿 skuName）
                String skuName = "";
                try {
                    R info = productFeignService.info(detailEntity.getSkuId());
                    if (info.getCode() == 0) {
                        Map<String, Object> data = (Map<String, Object>) info.get("skuInfo");
                        skuName = (String) data.get("skuName");
                    }
                } catch (Exception e) {
                    // 远程调用失败不影响入库，skuName 留空即可
                    log.warn("远程获取 skuName 失败，skuId={}", detailEntity.getSkuId(), e);
                }
                wareSkuService.addStock(detailEntity.getSkuId(), detailEntity.getWareId(), skuName,
                        detailEntity.getSkuNum());
            }
            updateList.add(detailEntity);
        }
        detailService.updateBatchById(updateList);

        // 3、所有采购需求都完成了，采购单才算完成，否则整单标记为有异常
        PurchaseEntity purchaseEntity = new PurchaseEntity();
        purchaseEntity.setId(vo.getId());
        purchaseEntity.setStatus(flag ? WareConstant.PurchaseStatusEnum.FINISH.getCode()
                : WareConstant.PurchaseStatusEnum.HASERROR.getCode());
        this.updateById(purchaseEntity);
    }

}
