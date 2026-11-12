package com.atguigu.gulimall.agent.tool;

import com.atguigu.gulimall.agent.feign.ProductAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

/**
 * 商品查询工具：搜索、详情、分类、品牌、SPU/SKU。
 *
 * <p>数据来自 gulimall-product 的真实数据库，不是模型编造。
 */
public class ProductTools {

    private final ToolContext context;
    private final ProductAiClient client;
    private final ObjectMapper mapper;

    /**
     * 构造商品查询工具实例。
     *
     * @param context 工具上下文，提供当前请求的身份信息
     * @param client  商品模块的 Feign 只读客户端
     * @param mapper  JSON 序列化与反序列化组件
     */
    public ProductTools(ToolContext context, ProductAiClient client, ObjectMapper mapper) {
        this.context = context;
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 按关键字、分类、品牌和价格区间分页搜索商品 SKU。
     *
     * @param keyword    搜索关键字，可为空
     * @param categoryId 三级分类 id，可为空
     * @param brandId    品牌 id，可为空
     * @param minPrice   最低价格，可为空
     * @param maxPrice   最高价格，可为空
     * @param page       页码，从 1 开始
     * @param size       每页数量，最多 20
     * @return 商品 SKU 搜索结果的 JSON 字符串
     */
    @Tool("按关键字、分类、品牌、价格区间搜索商品，返回 SKU 列表")
    public String searchProducts(@P("搜索关键字，例如 手机、小米；可为空") String keyword,
                                 @P("三级分类 id，可为空") Long categoryId,
                                 @P("品牌 id，可为空") Long brandId,
                                 @P("最低价格，可为空") Double minPrice,
                                 @P("最高价格，可为空") Double maxPrice,
                                 @P("页码，从 1 开始") Integer page,
                                 @P("每页数量，最多 20") Integer size) {
        // 1. 参数兜底，避免模型传 null 导致查询异常
        String pageStr = String.valueOf(page == null || page < 1 ? 1 : page);
        String sizeStr = String.valueOf(size == null ? 10 : Math.min(size, 20));

        // 2. 调商品模块的只读接口
        Map<String, Object> response = client.search(
                keyword,
                categoryId == null ? null : String.valueOf(categoryId),
                brandId == null ? null : String.valueOf(brandId),
                minPrice == null ? null : String.valueOf(minPrice),
                maxPrice == null ? null : String.valueOf(maxPrice),
                pageStr,
                sizeStr
        );
        return ToolResult.from(response, mapper);
    }

    /**
     * 查询指定 SKU 的详细信息，包括名称、价格、图片、规格、库存和分类路径。
     *
     * @param skuId SKU id
     * @return SKU 详情的 JSON 字符串；skuId 为空时返回失败提示
     */
    @Tool("查询某个 SKU 的详细信息：名称、价格、图片、规格、库存、分类路径")
    public String getSkuDetail(@P("SKU id") Long skuId) {
        // 1. skuId 必填
        if (skuId == null) {
            return "查询失败：skuId 不能为空";
        }
        return ToolResult.from(client.sku(skuId), mapper);
    }

    /**
     * 查询商品一级、二级分类树，用于回答系统有哪些商品分类。
     *
     * @return 商品分类树的 JSON 字符串
     */
    @Tool("查询商品一级/二级分类树，用于回答有哪些商品分类；三级分类可按关键字搜索")
    public String listCategories() {
        return ToolResult.from(client.categories(), mapper);
    }

    /**
     * 查询指定分类下的品牌，不传分类时返回常用品牌。
     *
     * @param categoryId 分类 id，可为空
     * @return 品牌列表的 JSON 字符串
     */
    @Tool("查询某个分类下的品牌；不传分类时返回常用品牌")
    public String listBrands(@P("分类 id，可为空") Long categoryId) {
        return ToolResult.from(client.brands(categoryId), mapper);
    }

    /**
     * 查询指定 SPU 下的所有 SKU，即同一商品的不同规格。
     *
     * @param spuId SPU id
     * @return SPU 下 SKU 列表的 JSON 字符串；spuId 为空时返回失败提示
     */
    @Tool("查询某个 SPU 下的所有 SKU（同一商品的不同规格）")
    public String listSpuSkus(@P("SPU id") Long spuId) {
        if (spuId == null) {
            return "查询失败：spuId 不能为空";
        }
        return ToolResult.from(client.spuSkus(spuId), mapper);
    }

    /**
     * 查询指定 SPU 的基本信息，包括名称、描述、分类、品牌和上架状态。
     *
     * @param spuId SPU id
     * @return SPU 基本信息的 JSON 字符串；spuId 为空时返回失败提示
     */
    @Tool("查询某个 SPU 的基本信息：名称、描述、分类、品牌、上架状态")
    public String getSpuInfo(@P("SPU id") Long spuId) {
        if (spuId == null) {
            return "查询失败：spuId 不能为空";
        }
        return ToolResult.from(client.spu(spuId), mapper);
    }
}
