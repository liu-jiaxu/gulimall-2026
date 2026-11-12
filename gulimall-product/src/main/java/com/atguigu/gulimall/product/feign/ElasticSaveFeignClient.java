package com.atguigu.gulimall.product.feign;

import com.atguigu.gulimall.api.search.SkuEsModel;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * @ClassName: ElasticSaveFeignClient
 * @Package: com.atguigu.gulimall.product.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 13:00
 * @Version: v1.0
 */
@FeignClient("gulimall-search")
public interface ElasticSaveFeignClient {

    @PostMapping("search/save/product")
    R productStatusUp(@RequestBody List<SkuEsModel> skuEsModels);

}
