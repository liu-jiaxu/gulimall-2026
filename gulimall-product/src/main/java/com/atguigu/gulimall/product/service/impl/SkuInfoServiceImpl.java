package com.atguigu.gulimall.product.service.impl;

import com.atguigu.gulimall.api.product.SkuDetailVo;
import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.product.dao.ProductAttrValueDao;
import com.atguigu.gulimall.product.dao.SkuImagesDao;
import com.atguigu.gulimall.product.dao.SkuInfoDao;
import com.atguigu.gulimall.product.dao.SkuSaleAttrValueDao;
import com.atguigu.gulimall.product.entity.SkuImagesEntity;
import com.atguigu.gulimall.product.entity.SkuInfoEntity;
import com.atguigu.gulimall.product.entity.SkuSaleAttrValueEntity;
import com.atguigu.gulimall.product.entity.SpuInfoDescEntity;
import com.atguigu.gulimall.product.feign.WareSkuFeignClient;
import com.atguigu.gulimall.product.method.SkuInfoMethod;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.service.SkuInfoService;
import com.atguigu.gulimall.product.service.SpuInfoDescService;
import com.atguigu.gulimall.product.vo.SkuItemSaleAttrVo;
import com.atguigu.gulimall.product.vo.SkuItemVo;
import com.atguigu.gulimall.product.vo.SpuItemAttrGroupVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;


@Service("skuInfoService")
public class SkuInfoServiceImpl extends ServiceImpl<SkuInfoDao, SkuInfoEntity> implements SkuInfoService {

    @Autowired
    private SpuInfoDescService spuInfoDescService;
    @Autowired
    private WareSkuFeignClient wareSkuFeignClient;
    @Autowired
    private SkuImagesDao skuImagesDao;
    @Autowired
    private ProductAttrValueDao productAttrValueDao;
    @Autowired
    private SkuSaleAttrValueDao skuSaleAttrValueDao;
    @Autowired
    private SkuInfoMethod skuInfoMethod;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolExecutor;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SkuInfoEntity> page = this.page(
                new Query<SkuInfoEntity>().getPage(params),
                new QueryWrapper<SkuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        // key=5, catelogId=225, brandId=1, min=0, max=0
        QueryWrapper<SkuInfoEntity> queryWrapper = new QueryWrapper<>();
        String key = (String) params.get("key");
        if (StringUtils.isNotEmpty(key)) {
            queryWrapper.and(w -> w.eq("sku_id", key).or().like("sku_name", key));
        }
        String catelogId = (String) params.get("catelogId");
        if (StringUtils.isNotEmpty(catelogId) && !"0".equalsIgnoreCase(catelogId)) {
            queryWrapper.eq("catelog_id", catelogId);
        }

        String brandId = (String) params.get("brandId");
        if (StringUtils.isNotEmpty(brandId) && !"0".equalsIgnoreCase(brandId)) {
            queryWrapper.eq("brand_id", brandId);
        }

        String min = (String) params.get("min");
        String max = (String) params.get("max");
        if (StringUtils.isNotEmpty(min) && StringUtils.isNotEmpty(max)
                && !("0".equals(min) && "0".equals(max))) {
            queryWrapper.between("price", min, max);
        }
        IPage<SkuInfoEntity> page = this.page(
                new Query<SkuInfoEntity>().getPage(params),
                queryWrapper
        );

        return new PageUtils(page);
    }

    /**
     * 查询当前spu对应的所有sku信息
     *
     * @param spuId spuId
     * @return 当前spu对应的所有sku信息
     */
    @Override
    public List<SkuInfoEntity> getSkuBySpuId(Long spuId) {
        return this.list(new QueryWrapper<SkuInfoEntity>().eq("spu_id", spuId));
    }

    /**
     * sku详情查询
     *
     * @param skuId skuId
     * @return sku详情
     */
    @Override
    public SkuItemVo item(Long skuId) {

        /*
            注意：当前功能存在业务逻辑bug，此处为了演示暂不修改。
                画面http://search.gulimall.vip/list.html?catelog3Id=225上展示的是每个商品的sku信息
                但是点击sku信息跳转后还可以选择spu的全部sku属性，设计不合理
                如果这样的话，那么画面应该显示spu信息（只有spu_name和默认图片），点进去再选择sku
         */

        /*
        CompletableFuture接口的主要方法包括：
            - thenApply(): 在异步计算完成后，对结果进行转换。
            - thenAccept(): 在异步计算完成后，对结果进行消费。
            - thenRun(): 在异步计算完成后，执行一个Runnable任务。
            - whenComplete(): 在异步计算完成后，提供一个处理结果和异常的函数。
            - exceptionally(): 在异步计算过程中发生异常时，提供一个处理异常的函数。
            - allOf(): 等待多个CompletableFuture都完成。
            - anyOf(): 等待任意一个CompletableFuture完成。
            - handle(): 在异步计算完成后，提供一个处理结果和异常的函数。
            - thenCombine(): 将两个CompletableFuture的结果进行组合。
            - thenCompose(): 将一个CompletableFuture的结果作为另一个CompletableFuture的输入。
            - complete(): 手动完成一个CompletableFuture，设置其结果或异常。
            - completeExceptionally(): 手动完成一个CompletableFuture，设置一个异常。
            - obtrudeValue(): 强制设置一个CompletableFuture的结果，无论它是否已经完成。
            - obtrudeException(): 强制设置一个CompletableFuture的异常，无论它是否已经完成。
            - getNow(): 获取异步计算的结果，如果计算尚未完成，则返回一个默认值。
            - get(): 获取异步计算的结果，如果计算尚未完成，则会阻塞当前线程直到计算完成。
            - join(): 获取异步计算的结果，如果计算尚未完成，则会阻塞当前线程直到计算完成（无需捕获异常）。
        */

        // 1.sku基本信息 pms_sku_info
        CompletableFuture<SkuInfoEntity> skuInfo = CompletableFuture.supplyAsync(() -> getById(skuId), threadPoolExecutor);

        // 3.spu销售属性组合
        CompletableFuture<List<SkuItemSaleAttrVo>> saleAttrVoList = skuInfo.thenApplyAsync(
                skuInfoEntity -> skuSaleAttrValueDao.getSaleAttr(skuInfoEntity.getSpuId()), threadPoolExecutor);

        // 4.spu介绍 pms_spu_info_desc (spu_id在这张表中不是主键，使用默认的getById方法会因为没有主键无法生成sql报错)
        CompletableFuture<SpuInfoDescEntity> spuInfoDesc =
                skuInfo.thenApplyAsync(skuInfoEntity -> spuInfoDescService.getOne(
                        new QueryWrapper<SpuInfoDescEntity>().eq("spu_id", skuInfoEntity.getSpuId())), threadPoolExecutor);

        // 5.spu的规格参数
        CompletableFuture<List<SpuItemAttrGroupVo>> spuItemAttrGroupVoList = skuInfo.thenApplyAsync(
                skuInfoEntity -> productAttrValueDao.getAttrGroupWithAttrsBySpuId(skuInfoEntity.getSpuId()), threadPoolExecutor);

        // 2.sku图片信息 pms_sku_images
        CompletableFuture<List<SkuImagesEntity>> skuImagesEntityList = CompletableFuture.supplyAsync(() ->
                skuImagesDao.selectList(new QueryWrapper<SkuImagesEntity>().eq("sku_id", skuId)), threadPoolExecutor);

        // 6.商品sku是否有货
        CompletableFuture<Boolean> hasStock = CompletableFuture.supplyAsync(() ->
                wareSkuFeignClient.hasStock(Collections.singletonList(skuId)).get(skuId), threadPoolExecutor);

        // 合并结果集
        return CompletableFuture.allOf(
                        skuInfo, saleAttrVoList, spuInfoDesc, spuItemAttrGroupVoList, skuImagesEntityList, hasStock)
                .thenApply(vo -> {
                            SkuInfoEntity skuInfoEntity = skuInfo.join();
                            SkuItemVo skuItemVo = new SkuItemVo();
                            skuItemVo.setInfo(skuInfoEntity);
                            skuItemVo.setSaleAttr(saleAttrVoList.join());
                            skuItemVo.setDesc(spuInfoDesc.join());
                            skuItemVo.setGroupAttrs(spuItemAttrGroupVoList.join());
                            skuItemVo.setImages(skuImagesEntityList.join());
                            skuItemVo.setHasStock(hasStock.join());

                            // 8、三级分类路径：一级 -> 二级 -> 三级
                            try {
                                Long catelogId = skuInfoEntity.getCatelogId();
                                if (catelogId == null) {
                                    skuItemVo.setCategoryPath(List.of());
                                } else {
                                    Long[] path = categoryService.findCatelogPath(catelogId);
                                    skuItemVo.setCategoryPath(Arrays.stream(path)
                                            .map(categoryService::getById)
                                            .filter(Objects::nonNull)
                                            .toList());
                                }
                            } catch (Exception e) {
                                skuItemVo.setCategoryPath(List.of());
                            }

                            // 9、当前 SKU 自己的销售属性，页面只展示不切换
                            try {
                                skuItemVo.setSkuSaleAttrs(skuSaleAttrValueDao.selectList(
                                        new QueryWrapper<SkuSaleAttrValueEntity>()
                                                .eq("sku_id", skuId)
                                                .orderByAsc("attr_sort")));
                            } catch (Exception e) {
                                skuItemVo.setSkuSaleAttrs(List.of());
                            }

                            // 10、可发货仓库
                            try {
                                List<SkuShippingFromVo> shippingFroms =
                                        wareSkuFeignClient.getShippingFrom(List.of(
                                                new FareRequestVo.Item(skuId, 1, null)));
                                skuItemVo.setShippingFroms(shippingFroms == null
                                        ? List.of() : shippingFroms);
                            } catch (Exception e) {
                                skuItemVo.setShippingFroms(List.of());
                            }

                            return skuItemVo;
                        }
                )
                .join();

    }

    @Override
    public List<SkuDetailVo> getSkuDetails(List<Long> skuIds) {
        return skuInfoMethod.getSkuDetails(skuIds);
    }

}