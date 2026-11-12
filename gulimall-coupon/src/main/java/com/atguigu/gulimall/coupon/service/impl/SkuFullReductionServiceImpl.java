package com.atguigu.gulimall.coupon.service.impl;

import com.atguigu.gulimall.api.member.MemberPriceTo;
import com.atguigu.gulimall.api.product.SkuReductionTo;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.coupon.dao.SkuFullReductionDao;
import com.atguigu.gulimall.coupon.entity.MemberPriceEntity;
import com.atguigu.gulimall.coupon.entity.SkuFullReductionEntity;
import com.atguigu.gulimall.coupon.entity.SkuLadderEntity;
import com.atguigu.gulimall.coupon.service.MemberPriceService;
import com.atguigu.gulimall.coupon.service.SkuFullReductionService;
import com.atguigu.gulimall.coupon.service.SkuLadderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service("skuFullReductionService")
public class SkuFullReductionServiceImpl extends ServiceImpl<SkuFullReductionDao, SkuFullReductionEntity> implements SkuFullReductionService {

    @Autowired
    private SkuLadderService skuLadderService;
    @Autowired
    private MemberPriceService memberPriceService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SkuFullReductionEntity> page = this.page(
                new Query<SkuFullReductionEntity>().getPage(params),
                new QueryWrapper<>()
        );

        return new PageUtils(page);
    }

    public void saveSkuReduction(SkuReductionTo skuReductionTo) {
        //1、保存满减打折，会员价 sms_sku_ladder
        if (skuReductionTo.getFullCount() > 0) {
            SkuLadderEntity skuLadderEntity = new SkuLadderEntity();
            skuLadderEntity.setSkuId(skuReductionTo.getSkuId());
            skuLadderEntity.setAddOther(skuReductionTo.getCountStatus());
            skuLadderEntity.setFullCount(skuReductionTo.getFullCount());
            skuLadderEntity.setDiscount(skuReductionTo.getDiscount());
            skuLadderService.save(skuLadderEntity);
        }

        //2、保存满减信息 sms_sku_full_reduction
        if (skuReductionTo.getFullPrice().compareTo(BigDecimal.ZERO) > 0) {
            SkuFullReductionEntity skuFullReductionEntity = new SkuFullReductionEntity();
            skuFullReductionEntity.setSkuId(skuReductionTo.getSkuId());
            skuFullReductionEntity.setFullPrice(skuReductionTo.getFullPrice());
            skuFullReductionEntity.setReducePrice(skuReductionTo.getReducePrice());
            skuFullReductionEntity.setAddOther(skuReductionTo.getPriceStatus());
            this.save(skuFullReductionEntity);
        }

        //3、保存会员价格 sms_member_price
        List<MemberPriceTo> memberPrice = skuReductionTo.getMemberPrice();
        if (memberPrice != null) {
            List<MemberPriceEntity> collect = memberPrice.stream().map(item -> {
                MemberPriceEntity memberPriceEntity = new MemberPriceEntity();
                memberPriceEntity.setSkuId(skuReductionTo.getSkuId());
                memberPriceEntity.setMemberLevelId(item.getId());
                memberPriceEntity.setMemberPrice(item.getPrice());
                memberPriceEntity.setMemberLevelName(item.getName());
                memberPriceEntity.setAddOther(1);
                return memberPriceEntity;
            }).filter(
                    item -> (item.getMemberPrice().compareTo(BigDecimal.ZERO) > 0)
            ).collect(Collectors.toList());
            memberPriceService.saveBatch(collect);
        }

    }

}