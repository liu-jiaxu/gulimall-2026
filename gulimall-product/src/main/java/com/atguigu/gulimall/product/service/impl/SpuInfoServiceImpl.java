package com.atguigu.gulimall.product.service.impl;

import com.atguigu.gulimall.api.product.SkuReductionTo;
import com.atguigu.gulimall.api.product.SpuBoundTo;
import com.atguigu.gulimall.api.search.Attr;
import com.atguigu.gulimall.api.search.SkuEsModel;
import com.atguigu.gulimall.common.enums.product.StatusEnum;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.product.dao.AttrDao;
import com.atguigu.gulimall.product.dao.SpuInfoDao;
import com.atguigu.gulimall.product.entity.*;
import com.atguigu.gulimall.product.feign.CouponFeignClient;
import com.atguigu.gulimall.product.feign.ElasticSaveFeignClient;
import com.atguigu.gulimall.product.feign.WareSkuFeignClient;
import com.atguigu.gulimall.product.method.ProductAttrValueMethod;
import com.atguigu.gulimall.product.service.*;
import com.atguigu.gulimall.product.vo.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;


@Service("spuInfoService")
@Builder
@Slf4j
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {

    private SpuInfoDao spuInfoDao;
    /*
        service层之间相互调用时，必须确保不会出现循环依赖，否则必须额外创建method层编写共通业务方法
        实际大型项目生产时禁止同级间相互调用，必须下沉共通层避免循环依赖！

        像一些大型项目还会有usecase层，用于调用service，编排流程和实现事务，controller层只负责与http交互
        对于vo实体类的业务检查，在service中新增check方法，usecase调用即可
     */
    private SpuInfoDescService spuInfoDescService;
    private SpuImagesService spuImagesService;
    private AttrService attrService;
    private ProductAttrValueService productAttrValueService;
    private SkuInfoService skuInfoService;
    private SkuImagesService skuImagesService;
    private SkuSaleAttrValueService skuSaleAttrValueService;
    private BrandService brandService;
    private CategoryService categoryService;
    private ProductAttrValueMethod productAttrValueMethod;
    private CouponFeignClient couponFeignClient;
    private WareSkuFeignClient wareSkuFeignClient;
    private ElasticSaveFeignClient elasticSaveFeignClient;
    private AttrDao attrDao;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<>()
        );

        return new PageUtils(page);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void saveSpuSaveVo(SpuSaveVo spuSaveVo) {
        //1、保存spu基本信息`pms_spu_info`
        SpuInfoEntity infoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(spuSaveVo, infoEntity);
        infoEntity.setCreateTime(new Date());
        infoEntity.setUpdateTime(new Date());
        this.save(infoEntity);

        //2、保存spu的描述图片`pms_spu_info_desc`
        List<String> decript = spuSaveVo.getDecript();
        SpuInfoDescEntity descEntity = new SpuInfoDescEntity();
        descEntity.setSpuId(infoEntity.getId());
        descEntity.setDecript(String.join(",", decript));
        spuInfoDescService.save(descEntity);

        //3、保存spu的图片集`pms_spu_images`
        List<String> images = spuSaveVo.getImages();
        if (images != null && !images.isEmpty()) {
            List<SpuImagesEntity> collect = images.stream().map(image -> {
                SpuImagesEntity imagesEntity = new SpuImagesEntity();
                imagesEntity.setSpuId(infoEntity.getId());
                imagesEntity.setImgUrl(image);
                return imagesEntity;
            }).toList();
            spuImagesService.saveBatch(collect);
        }

        //4、保存spu的规格参数`pms_product_attr_value`
        List<BaseAttrs> baseAttrs = spuSaveVo.getBaseAttrs();
        // 解决循环DB调用
        Map<String, String> attrNameMap = baseAttrs.stream()
                .collect(Collectors.toMap(
                        baseAttr -> baseAttr.getAttrId().toString(),
                        baseAttr -> attrDao.getAttrNameById(baseAttr.getAttrId())
                ));

        List<ProductAttrValueEntity> productAttrValueEntityList = baseAttrs.stream().map(attr -> {
            ProductAttrValueEntity valueEntity = new ProductAttrValueEntity();
            valueEntity.setAttrId(attr.getAttrId());
//            AttrEntity byId = attrService.getById(attr.getAttrId());
//            valueEntity.setAttrName(byId.getAttrName());
            valueEntity.setAttrName(attrNameMap.get(attr.getAttrId().toString()));
            valueEntity.setAttrValue(attr.getAttrValues());
            valueEntity.setQuickShow(attr.getShowDesc());
            valueEntity.setSpuId(infoEntity.getId());
            return valueEntity;
        }).collect(Collectors.toList());
        productAttrValueService.saveBatch(productAttrValueEntityList);

        //5、保存spu的积分信息`gulimall_sms`->`sms_spu_bounds`
        Bounds bounds = spuSaveVo.getBounds();
        SpuBoundTo spuBoundTo = new SpuBoundTo();
        BeanUtils.copyProperties(bounds, spuBoundTo);
        spuBoundTo.setSpuId(infoEntity.getId());
        R r = couponFeignClient.saveSpuBounds(spuBoundTo);
        if (r.getCode() != 0) {
            log.error("远程保存spu积分信息失败");
        }

        //6、保存spu对应的所有sku信息
        List<Skus> skus = spuSaveVo.getSkus();
        if (skus != null && !skus.isEmpty()) {
            skus.forEach(item -> {
                String defaultImg = "";
                //查找出默认图片
                for (Images image : item.getImages()) {
                    if (image.getDefaultImg() == 1) {
                        defaultImg = image.getImgUrl();
                    }
                }
                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(item, skuInfoEntity);
                skuInfoEntity.setSpuId(infoEntity.getId());
                skuInfoEntity.setBrandId(infoEntity.getBrandId());
                skuInfoEntity.setCatelogId(infoEntity.getCatelogId());
                skuInfoEntity.setSaleCount(0L);
                skuInfoEntity.setSkuDefaultImg(defaultImg);
                //6.1、sku的基本信息`pms_sku_info`
                skuInfoService.save(skuInfoEntity);

                //6.2、sku的图片信息`pms_sku_images`
                Long skuId = skuInfoEntity.getSkuId();

                List<SkuImagesEntity> skuImagesEntities = item.getImages().stream().map(img -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    skuImagesEntity.setSkuId(skuId);
                    skuImagesEntity.setImgUrl(img.getImgUrl());
                    skuImagesEntity.setDefaultImg(img.getDefaultImg());
                    return skuImagesEntity;
                }).filter(entity -> {
                    //返回true是需要，返回false是过滤掉
                    return StringUtils.isNotEmpty(entity.getImgUrl());
                }).collect(Collectors.toList());
                skuImagesService.saveBatch(skuImagesEntities);

                //6.3、sku的销售属性信息`pms_sku_sale_attr_value`
                List<com.atguigu.gulimall.product.vo.Attr> attr = item.getAttr();
                List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = attr.stream().map(a -> {
                    SkuSaleAttrValueEntity skuSaleAttrValueEntity = new SkuSaleAttrValueEntity();
                    skuSaleAttrValueEntity.setSkuId(skuId);
                    BeanUtils.copyProperties(a, skuSaleAttrValueEntity);
                    return skuSaleAttrValueEntity;
                }).collect(Collectors.toList());
                skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);


                //6.4、sku的优惠、满减等信息`gulimall_sms`->`sms_sku_ladder`/`sms_sku_full_reduction`/`sms_member_price`
                SkuReductionTo skuReductionTo = new SkuReductionTo();
                BeanUtils.copyProperties(item, skuReductionTo);
                skuReductionTo.setSkuId(skuId);
                if (skuReductionTo.getFullCount() > 0 || skuReductionTo.getFullPrice().compareTo(BigDecimal.ZERO) > 0) {
                    R r1 = couponFeignClient.saveSkuReduction(skuReductionTo);
                    if (r1.getCode() != 0) {
                        log.error("远程保存优惠信息失败");
                    }
                }
            });
        }
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        // status=0, key=6, brandId=1, catelogId=225
        QueryWrapper<SpuInfoEntity> queryWrapper = new QueryWrapper<>();
        String key = (String) params.get("key");
        if (StringUtils.isNotEmpty(key)) {
            queryWrapper.and(w -> w.eq("id", key).or().like("spu_name", key));
        }
        String catelogId = (String) params.get("catelogId");
        if (StringUtils.isNotEmpty(catelogId) && !"0".equalsIgnoreCase(catelogId)) {
            queryWrapper.eq("catelog_id", catelogId);
        }

        String brandId = (String) params.get("brandId");
        if (StringUtils.isNotEmpty(brandId) && !"0".equalsIgnoreCase(brandId)) {
            queryWrapper.eq("brand_id", brandId);
        }

        String status = (String) params.get("status");
        if (StringUtils.isNotEmpty(status)) {
            queryWrapper.eq("publish_status", status);
        }
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                queryWrapper
        );
        return new PageUtils(page);
    }

    /**
     * 商品上架
     *
     * @param spuId
     */
    @Override
    public void up(Long spuId) {

        // 1.查询当前spu对应的所有sku信息
        List<SkuInfoEntity> skuInfoEntityList = skuInfoService.getSkuBySpuId(spuId);

        // 2.1 远程调用ware库存系统，查询是否有库存
        Map<Long, Boolean> hasStock = null;
        try {
            hasStock = wareSkuFeignClient.hasStock(
                    skuInfoEntityList.stream().map(SkuInfoEntity::getSkuId).toList());
        } catch (Exception e) {
            log.error("库存服务查询异常，原因：", e);
        }

        // 2.4 查询当前sku所有可被检索的规格属性
        List<ProductAttrValueEntity> productAttrValueEntityList = productAttrValueMethod.baseAttrListForSpu(spuId);
        List<Long> attrIds = productAttrValueEntityList.stream().map(ProductAttrValueEntity::getAttrId).toList();
        List<Long> searchAttrIds = productAttrValueMethod.selectSearchAttrs(attrIds);
        Set<Long> sIds = new HashSet<>(searchAttrIds);
        List<Attr> searchlist = productAttrValueEntityList.stream()
                .filter(attr -> sIds.contains(attr.getAttrId()))
                .map(item -> {
                    Attr attr = new Attr();
                    BeanUtils.copyProperties(item, attr);
                    return attr;
                })
                .toList();

        // 2.封装sku信息
        Map<Long, Boolean> finalHasStock = hasStock;
        List<SkuEsModel> skuEsModelList = skuInfoEntityList.stream().map(skuInfo -> {
                    SkuEsModel skuEsModel = new SkuEsModel();
                    BeanUtils.copyProperties(skuInfo, skuEsModel);
                    skuEsModel.setSkuPrice(skuInfo.getPrice());
                    skuEsModel.setSkuImg(skuInfo.getSkuDefaultImg());
                    // 2.1 查询失败设置为false没有库存
                    skuEsModel.setHasStock(finalHasStock != null ? finalHasStock.get(skuInfo.getSkuId()) : false);
                    // 2.2 热度评分 默认0
                    skuEsModel.setHotScore(0L);
                    // 2.3 查询品牌和分类的名字信息
                    BrandEntity brandEntity = brandService.getById(skuInfo.getBrandId());
                    skuEsModel.setBrandName(brandEntity.getName());
                    skuEsModel.setBrandImg(brandEntity.getLogo());
                    CategoryEntity categoryEntity = categoryService.getById(skuInfo.getCatelogId());
                    skuEsModel.setCatelogName(categoryEntity.getName());
                    // 2.4
                    skuEsModel.setAttrs(searchlist);

                    return skuEsModel;
                }
        ).toList();

        // 3.数据发送给ES保存
        R r = elasticSaveFeignClient.productStatusUp(skuEsModelList);
        if (r.getCode() == 0) {
            // 成功
            // 4.修改当前spu状态
            baseMapper.updateSpuStatus(spuId, StatusEnum.SPU_UP.getCode());
        } else {
            // 失败
            // TODO 重复调用？接口幂等性；重试机制
            /*
              1、构造请求数据，将对象转为json;
              RequestTemplate template = buildTemplateFromArgs.create(argv);
              2、发送请求进行执行（执行成功会解码响应数据）：
              executeAndDecode（template）;
              3、执行请求会有重试机制
              while(true)(
                try{
                    executeAndDecode（template）;
                } catch() {
                    try{
                        retryer.continueOrPropagate(e);
                    } catch () {
                        throw ex;
                    }
                    continue;
                }
             */
        }

    }

    @Override
    public BigDecimal getSkuWeight(Long skuId) {
        BigDecimal weight = spuInfoDao.getSkuWeight(skuId);
        return weight != null ? weight : BigDecimal.ZERO;
    }

}