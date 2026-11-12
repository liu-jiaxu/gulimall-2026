package com.atguigu.gulimall.search.service;

import com.atguigu.gulimall.api.search.SkuEsModel;

import java.io.IOException;
import java.util.List;

/**
 * @ClassName: ProductSaveService
 * @Package: com.atguigu.gulimall.search.service
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 12:43
 * @Version: v1.0
 */

public interface ProductSaveService {

    boolean productStatusUp(List<SkuEsModel> skuEsModels) throws IOException;

}
