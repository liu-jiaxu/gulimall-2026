package com.atguigu.gulimall.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.atguigu.gulimall.api.search.SkuEsModel;
import com.atguigu.gulimall.search.constant.EsConstant;
import com.atguigu.gulimall.search.service.ProductSaveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @ClassName: ProductSaveServiceImpl
 * @Package: com.atguigu.gulimall.search.service.impl
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 12:46
 * @Version: v1.0
 */
@Service("productSaveService")
@Slf4j
public class ProductSaveServiceImpl implements ProductSaveService {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    // GET product/_search
    @Override
    public boolean productStatusUp(List<SkuEsModel> skuEsModels) throws IOException {

        // 1、索引 mapping 要【预先建好】（见 product-mapping.txt），不要在代码里动态建
        //    —— 动态映射推断出的字段类型往往不是你想要的，比如价格会被推断成 float 丢失精度

        // 2、批量保存：新客户端用 BulkRequest.Builder + 一组 operation
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (SkuEsModel skuEsModel : skuEsModels) {
            br.operations(op -> op.index(idx -> idx
                    .index(EsConstant.PRODUCT_INDEX)          // 索引名
                    .id(skuEsModel.getSkuId().toString())     // 用 skuId 作为文档 id
                    .document(skuEsModel)                     // 直接传对象，Jackson 自动序列化
            ));
        }

        // 3、执行
        BulkResponse result = elasticsearchClient.bulk(br.build());

        // 4、检查结果
        if (result.errors()) {
            log.error("商品上架出错：");
            for (BulkResponseItem item : result.items()) {
                if (item.error() != null) {
                    log.error("skuId={} 上架失败，原因：{}", item.id(), item.error().reason());
                }
            }
            return true;    // 有失败
        }

        List<String> ids = result.items().stream()
                .map(BulkResponseItem::id)
                .collect(Collectors.toList());
        log.info("商品上架成功:{}", ids);
        return false;       // 全部成功
    }
}
