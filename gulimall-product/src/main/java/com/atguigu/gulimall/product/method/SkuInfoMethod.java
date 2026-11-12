package com.atguigu.gulimall.product.method;

import com.atguigu.gulimall.api.product.SkuDetailVo;
import com.atguigu.gulimall.product.dao.BrandDao;
import com.atguigu.gulimall.product.dao.SkuInfoDao;
import com.atguigu.gulimall.product.dao.SpuInfoDao;
import com.atguigu.gulimall.product.entity.BrandEntity;
import com.atguigu.gulimall.product.entity.SkuInfoEntity;
import com.atguigu.gulimall.product.entity.SpuInfoEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @ClassName: SkuInfoMethod
 * @Package: com.atguigu.gulimall.product.method
 * @Description: sku 共用的业务方法（只依赖 Dao，避免 service 之间循环依赖）
 * @Author: 刘家旭
 * @Version: v1.0
 */
@Component
public class SkuInfoMethod {

    @Autowired
    private SkuInfoDao skuInfoDao;
    @Autowired
    private SpuInfoDao spuInfoDao;
    @Autowired
    private BrandDao brandDao;

    public List<SkuDetailVo> getSkuDetails(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }

        List<SkuInfoEntity> skuInfos = skuInfoDao.selectByIds(skuIds);
        if (skuInfos.isEmpty()) {
            return List.of();
        }

        Map<Long, SpuInfoEntity> spuMap = spuInfoDao
                .selectByIds(skuInfos.stream().map(SkuInfoEntity::getSpuId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SpuInfoEntity::getId, Function.identity()));

        Map<Long, BrandEntity> brandMap = brandDao
                .selectByIds(skuInfos.stream().map(SkuInfoEntity::getBrandId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(BrandEntity::getBrandId, Function.identity()));

        return skuInfos.stream().map(sku -> {
            SkuDetailVo vo = new SkuDetailVo();
            vo.setSkuId(sku.getSkuId());
            vo.setSpuId(sku.getSpuId());
            vo.setSpuPic(sku.getSkuDefaultImg());
            vo.setCategoryId(sku.getCatelogId());

            SpuInfoEntity spu = spuMap.get(sku.getSpuId());
            if (spu != null) {
                vo.setSpuName(spu.getSpuName());
            }

            BrandEntity brand = brandMap.get(sku.getBrandId());
            if (brand != null) {
                vo.setSpuBrand(brand.getName());
            }

            return vo;
        }).toList();
    }

}
