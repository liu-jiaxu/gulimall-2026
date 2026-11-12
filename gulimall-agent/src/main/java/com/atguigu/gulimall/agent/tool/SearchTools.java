package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.SearchAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * ES 全文搜索工具：比数据库 LIKE 更接近用户搜索页的效果。
 */
public class SearchTools {

    private final SearchAiClient client;
    private final ObjectMapper mapper;

    /**
     * 构造 ES 全文搜索工具实例。
     *
     * @param client 搜索模块的 Feign 只读客户端
     * @param mapper JSON 序列化与反序列化组件
     */
    public SearchTools(SearchAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 在商品搜索索引中按关键字检索商品，支持品牌、库存、价格区间和排序条件。
     *
     * @param keyword  搜索关键字，例如 小米手机
     * @param brandId  品牌 id，多个用逗号分隔，可为空
     * @param skuPrice 价格区间，格式为 0-3000，可为空
     * @param hasStock 是否只看有货：1 表示是，0 表示否，可为空
     * @param sort     排序方式：skuPrice_asc、saleCount_desc、hotScore_desc，可为空
     * @param page     页码，从 1 开始
     * @return 商品搜索结果 JSON 字符串
     */
    @Tool("在商品搜索索引中按关键字检索商品，支持品牌、价格区间、排序")
    public String searchByKeyword(@P("搜索关键字，例如 小米手机") String keyword,
                                  @P("品牌 id，多个用逗号分隔，可为空") String brandId,
                                  @P("价格区间，格式 0-3000，可为空") String skuPrice,
                                  @P("是否只看有货：1 是，0 否，可为空") Integer hasStock,
                                  @P("排序：skuPrice_asc、saleCount_desc、hotScore_desc，可为空") String sort,
                                  @P("页码，从 1 开始") Integer page) {
        // 1. 调搜索模块的只读接口
        Map<String, Object> response = client.search(
                keyword, null, brandId, hasStock, skuPrice, sort,
                page == null || page < 1 ? 1 : page);
        return ToolResult.from(response, mapper);
    }
}
