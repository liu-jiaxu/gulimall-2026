package com.atguigu.gulimall.product.method;

import com.atguigu.gulimall.product.dao.AttrDao;
import com.atguigu.gulimall.product.dao.ProductAttrValueDao;
import com.atguigu.gulimall.product.entity.AttrEntity;
import com.atguigu.gulimall.product.entity.ProductAttrValueEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @ClassName: ProductAttrValueMethod
 * @Package: com.atguigu.gulimall.product.method
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 10:27
 * @Version: v1.0
 */
@Component
public class ProductAttrValueMethod {

    @Autowired
    private ProductAttrValueDao productAttrValueDao;
    @Autowired
    private AttrDao attrDao;

    public List<ProductAttrValueEntity> baseAttrListForSpu(Long spuId) {
        return productAttrValueDao.selectList(
                new QueryWrapper<ProductAttrValueEntity>().eq("spu_id", spuId));
    }

    /**
     * 根据attrId查询当前attr是否可被检索
     * @param attrIds
     * @return
     */
    public List<Long> selectSearchAttrs(List<Long> attrIds) {
        return attrDao.selectSearchAttrs(attrIds);
    }
}
