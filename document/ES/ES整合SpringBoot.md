# Elasticsearch 整合 Spring Boot

> **本文讲什么**：项目代码是怎么用 ES 的 —— 商品数据怎么进去、页面搜索怎么查出来。
>
> **前置阅读**：ES 本身的概念、DSL 语法、安装运维见同目录的 **`ES.md`**。本文只讲 Java 代码。
>
> **不懂 ES 也能看**：每个概念第一次出现时都会用 MySQL 打个比方。

---

## 〇、先看全局：两条链路

整个项目里 ES 只干两件事 —— **存**（商品上架）和 **查**（商品检索）。先看清它们各自牵扯哪些服务，后面再看代码就不会迷路。

### 链路一：商品上架（存）

```
商家在后台点"上架"
        │
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ①  product 服务：SpuInfoServiceImpl.up(spuId)                       │
│                                                                     │
│   ② 查这个 spu 下面所有的 sku        → 查自己的库(sku_info)          │
│   ③ 问库存：这些 sku 还有货吗        → Feign 调 ware 服务            │
│   ④ 查哪些规格属性可被检索           → 查自己的库(attr)              │
│   ⑤ 把每个 sku 组装成 SkuEsModel     → 一份"准备存进 ES 的数据"       │
│   ⑥ Feign 把这批数据发给 search 服务 →                               │
└──────────────────────────────────────────┬──────────────────────────┘
                                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ⑦  search 服务：ProductSaveServiceImpl.productStatusUp(list)        │
│     bulk 批量写入 ES，文档 id 用 skuId                               │
└──────────────────────────────────────────┬──────────────────────────┘
                                           ▼
                                     ES 索引 product
                                           │
        ┌──────────────────────────────────┘
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ⑧  回到 product：写入成功 → 把 spu 状态改成"已上架"                  │
└─────────────────────────────────────────────────────────────────────┘
```

**为什么绕这么一圈**：ES 里存的是"能直接给搜索用的完整数据"（含品牌名、分类名、库存状态），
这些东西分散在 product、ware 两个库，所以必须在**上架那一刻**先把它们拼齐、再整体推给 ES。

### 链路二：商品检索（查）

```
用户在搜索页输入"华为 Meta 30 Pro"，勾选品牌/分类/属性
        │
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ①  search 服务：SearchController.listPage(SearchParam)              │
│     Spring MVC 自动把 URL 上的查询参数装进 SearchParam              │
│                                                                     │
│ ②  MallSearchServiceImpl.search(param)                             │
│       ├─ 把 SearchParam 翻译成 ES 的 DSL 查询语句                    │
│       ├─ client.search(...) ────────────────────────────────→ 查 ES │
│       │                                                             │
│       ├─ 解析命中结果 → 商品列表                                     │
│       ├─ 解析聚合结果 → 左侧筛选栏（有哪些品牌/分类/属性可选）        │
│       └─ 生成面包屑 → 上面那排"× 青金 ×"                            │
│                                     ┌──────────────────────────────┤
│ ③ 面包屑要显示属性的中文名 ──────────→ Feign 调 product 查 attrName  │
└─────────────────────────────────────┴───────────────────────────────┘
        │
        ▼
   ⑥ 渲染 templates/list.html
```

**关键点**：一次 `_search` 请求同时带回**两样东西** —— 命中的商品，和**聚合**（"这批结果里实际涉及哪些品牌/分类/属性"）。页面左侧的筛选栏就是靠聚合渲染的，不需要再查一次。

---

## 一、准备工作

### 1.1 用哪个客户端

| 方案 | 依赖 | 特点 |
|---|---|---|
| Spring Data Elasticsearch | `spring-boot-starter-data-elasticsearch` | Repository 风格，适合简单 CRUD |
| **Elasticsearch Java API Client**（官方） | `co.elastic.clients:elasticsearch-java` | 能写任意复杂 DSL，**本项目用这个** |

商品检索要写 `nested`、`aggregations`、`highlight` 这些复杂结构，所以选官方客户端。
它底层就是 Spring Data ES 用的那套，两者可以共存。

> **版本不用自己写**：Spring Boot 3.5.16 的 BOM 已经管好 `elasticsearch-java` 8.18.x，
> 和服务端的 8.18.8 配套。**客户端与服务端大版本必须一致**，所以依赖里**不写 version**。

### 1.2 依赖与配置

`gulimall-search/pom.xml`：

```xml
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <!-- 不写 version，由 Spring Boot BOM 管理 -->
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

`config/gulimall-search.yaml`（真实密码放这里，已在 `.gitignore` 中）：

```yaml
ES_URIS: http://<your-vm-host-ip>:9200
ES_USERNAME: elastic
ES_PASSWORD: <密码>
```

`gulimall-search/src/main/resources/application.yaml`：

```yaml
spring:
  elasticsearch:
    uris: ${ES_URIS}
    username: ${ES_USERNAME}
    password: ${ES_PASSWORD}
```

> **别把地址密码硬编码进 Java 代码** —— 换环境就要重新编译，且密码会进 git 历史。

### 1.3 客户端 Bean

ES 开了密码认证，所以建客户端时要带上账号密码。

```java
package com.atguigu.gulimall.search.config;

@Configuration
public class GulimallElasticSearchConfig {

    @Value("${spring.elasticsearch.uris}")     private String uris;
    @Value("${spring.elasticsearch.username}") private String username;
    @Value("${spring.elasticsearch.password}") private String password;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // ① HTTP Basic 认证
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));

        // ② 底层 REST 传输层
        RestClient restClient = RestClient.builder(HttpHost.create(uris))
                .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(credentialsProvider))
                .build();

        // ③ JSON 映射器 + 传输层 → 最终客户端
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        return new ElasticsearchClient(transport);
    }
}
```

三层结构从上到下：**客户端**（写查询用）→ **传输层**（管连接和认证）→ **REST 层**（真正发 HTTP）。

### 1.4 先验证能连上

```java
@SpringBootTest
class GulimallSearchApplicationTests {

    @Autowired
    private ElasticsearchClient client;

    @Test
    void contextLoads() {
        System.out.println(client);   // 打印出对象就说明注入成功
    }
}
```

> Spring Boot 3.x 默认 JUnit 5，`@RunWith(SpringRunner.class)` 已经不需要了。

---

## 二、存入 ES：商品上架

### 2.1 先定数据结构

**存 sku 还是存 spu？**

| | sku | spu |
|---|---|---|
| 是什么 | 具体到"6GB+128GB 亮黑色"这一款 | "华为 Mate 10"这一整个商品 |
| 搜索按什么 | 用户输入的是**具体商品名**，要按 skuTitle 全文检索 | — |
| 规格参数 | 同一个 spu 下所有 sku 的规格都一样 | 规格是 spu 的公共属性 |

**结论：存 sku**。因为用户搜的是具体款，而且按 spu 存的话，检索时还得再展开成 sku 才能展示，多此一举。

代价是规格参数会**冗余存储**（同一 spu 的多个 sku 各存一份同样的 attrs），但换来检索时的简单直接，值得。

### 2.2 建立索引（mapping）

> **索引 ≈ MySQL 的表，文档 ≈ 表里的一行**。ES 8.x 里一个索引就是一张表，没有"库"这一层。

**两条建议**：

1. **索引要在代码跑之前手工建好**，不要靠写入时自动创建 —— 动态映射推断出的类型往往不是你想要的（比如价格会被推断成 `float` 丢精度）。
2. 索引名加业务前缀（本项目用的是 `product`，常量在 `EsConstant.PRODUCT_INDEX`）。

完整的建索引语句在 `gulimall-search/src/main/resources/es/product-mapping.txt`，可直接粘进 Kibana Dev Tools 执行：

```json
PUT product
{
  "mappings": {
    "properties": {
      "skuId":      { "type": "long" },
      "spuId":      { "type": "keyword" },
      "skuTitle":   { "type": "text", "analyzer": "ik_smart" },
      "skuPrice":   { "type": "keyword" },
      "skuImg":     { "type": "keyword" },
      "saleCount":  { "type": "long" },
      "hasStock":   { "type": "boolean" },
      "hotScore":   { "type": "long" },
      "brandId":    { "type": "long" },
      "catelogId":  { "type": "long" },
      "brandName":  { "type": "keyword" },
      "brandImg":   { "type": "keyword" },
      "catelogName":{ "type": "keyword" },
      "attrs": {
        "type": "nested",
        "properties": {
          "attrId":    { "type": "long" },
          "attrName":  { "type": "keyword" },
          "attrValue": { "type": "keyword" }
        }
      }
    }
  }
}
```

**字段类型怎么选**（理解这三条就够用了）：

| 写法 | 含义 | 用在哪 |
|---|---|---|
| `text` | 会分词，能全文检索 | `skuTitle`（要按关键词搜商品名） |
| `keyword` | 不分词，必须整串匹配 | `brandName`、`skuPrice`（要精确匹配 / 聚合 / 排序） |
| `nested` | 数组里的对象，**每个对象独立索引** | `attrs`（见下） |

**为什么 `attrs` 必须用 nested？**

ES 默认会把数组里的对象**拍平**：

```
原始数据                    拍平后（默认行为，会出错）
[{颜色: 白}, {尺寸: 5}]  →  {颜色: [白], 尺寸: [5]}

于是查"颜色=白 且 尺寸=5"时，可能匹配到实际不存在的组合
（比如"颜色=白尺寸=5"这一款并不存在，但因为是分开存的，会被误命中）
```

`nested` 让每个对象**独立成一份隐藏文档**，查的时候整组一起匹配，就不会张冠李戴。

> ⚠️ 代价：普通 `match` 查不到 nested 里的字段，必须专用 `nested` 查询（见 §3.3）。
> 另外 nested 里的字段名查询时要写**全路径** `attrs.attrValue`，只写 `attrValue` 查不到。

### 2.3 传输对象：SkuEsModel

存进 ES 的数据长什么样，由这个类决定。它在 **`gulimall-api` 模块**（`com.atguigu.gulimall.api.search`），
因为 product 和 search 两个服务都要用它 —— 放公共模块才能被双方引用。

```java
@Data
public class SkuEsModel {
    private Long skuId;
    private Long spuId;
    private String skuTitle;
    private BigDecimal skuPrice;
    private String skuImg;
    private Long saleCount;
    private boolean hasStock;      // ← product 查不到库存服务时兜底为 false
    private Long hotScore;         // ← 热度评分，目前固定 0
    private Long brandId;
    private Long catelogId;
    private String brandName;      // ← 冗余字段，为了搜索结果能直接展示
    private String brandImg;
    private String catelogName;
    private List<Attr> attrs;      // ← 对应 ES 里的 nested 字段

    @Data
    public static class Attr {
        private Long attrId;
        private String attrName;
        private String attrValue;
    }
}
```

> 注意 `skuTitle` 等字段名**必须和 ES mapping 里的字段名一致** —— 客户端把对象序列化成 JSON 后直接按字段名写入。

### 2.4 上架主流程

入口在 **product 服务**：`POST /product/spuinfo/{spuId}/up`，给定 spuId，把它下面所有 sku 都上架。

```java
// gulimall-product：SpuInfoServiceImpl
@Override
public void up(Long spuId) {

    // ① 查出这个 spu 下的所有 sku
    List<SkuInfoEntity> skuInfoList = skuInfoService.getSkuBySpuId(spuId);

    // ② 远程问库存：一次把 skuId 全传过去，一次拿回所有库存状态
    Map<Long, Boolean> hasStockMap = null;
    try {
        hasStockMap = wareSkuFeignClient.hasStock(
                skuInfoList.stream().map(SkuInfoEntity::getSkuId).toList());
    } catch (Exception e) {
        // ⚠️ 库存服务挂了不能让人上不了架，这里只记日志，下面兜底为"无货"
        log.error("库存服务查询异常，原因：", e);
    }

    // ③ 查出哪些规格属性可以参与检索
    List<ProductAttrValueEntity> attrValues = productAttrValueMethod.baseAttrListForSpu(spuId);
    List<Long> searchAttrIds = productAttrValueMethod.selectSearchAttrs(
            attrValues.stream().map(ProductAttrValueEntity::getAttrId).toList());
    Set<Long> searchableIds = new HashSet<>(searchAttrIds);
    List<Attr> searchableAttrs = attrValues.stream()
            .filter(a -> searchableIds.contains(a.getAttrId()))   // 过滤掉"不可检索"的属性
            .map(a -> {
                Attr attr = new Attr();
                BeanUtils.copyProperties(a, attr);
                return attr;
            })
            .toList();

    // ④ 组装：每个 sku → 一个 SkuEsModel
    Map<Long, Boolean> finalHasStockMap = hasStockMap;
    List<SkuEsModel> models = skuInfoList.stream().map(sku -> {
        SkuEsModel m = new SkuEsModel();
        BeanUtils.copyProperties(sku, m);
        // 字段名对不上的，手工补
        m.setSkuPrice(sku.getPrice());
        m.setSkuImg(sku.getSkuDefaultImg());

        // 库存：查失败就当作无货
        m.setHasStock(finalHasStockMap != null && finalHasStockMap.get(sku.getSkuId()) != null
                ? finalHasStockMap.get(sku.getSkuId()) : false);
        m.setHotScore(0L);

        // 品牌名/图、分类名：ES 里要冗余存，这样搜索结果不用再回表
        BrandEntity brand = brandService.getById(sku.getBrandId());
        m.setBrandName(brand.getName());
        m.setBrandImg(brand.getLogo());
        CategoryEntity category = categoryService.getById(sku.getCatelogId());
        m.setCatelogName(category.getName());

        m.setAttrs(searchableAttrs);
        return m;
    }).toList();

    // ⑤ 发给 search 服务写进 ES
    R r = elasticSaveFeignClient.productStatusUp(models);

    // ⑥ 写成功才改 spu 状态
    if (r.getCode() == 0) {
        baseMapper.updateSpuStatus(spuId, StatusEnum.SPU_UP.getCode());
    } else {
        // TODO 失败重试？接口幂等性？
    }
}
```

**流程串起来看**：查 sku → 问库存 → 查可检索属性 → 组装 → 推给 ES → 回写状态。

每一步都在为下一步准备数据，最后一步"回写状态"保证**只有真的进了 ES，才在数据库里标记为已上架**。

### 2.5 库存怎么查（ware 服务）

product 通过 Feign 调 ware：

```java
// gulimall-product：WareSkuFeignClient
@FeignClient("gulimall-ware")
public interface WareSkuFeignClient {
    @PostMapping("ware/waresku/hasStock")
    Map<Long, Boolean> hasStock(@RequestBody List<Long> skuIds);
}
```

ware 侧的接口和实现：

```java
// gulimall-ware：WareSkuController
@PostMapping("/hasStock")
public Map<Long, Boolean> hasStock(@RequestBody List<Long> skuIds) {
    return wareSkuService.getSkuHasStock(skuIds);
}

// gulimall-ware：WareSkuServiceImpl
@Override
public Map<Long, Boolean> getSkuHasStock(List<Long> skuIds) {
    return skuIds.stream().collect(Collectors.toMap(
            sku -> sku,
            skuId -> this.baseMapper.exists(new QueryWrapper<WareSkuEntity>()
                    .eq("sku_id", skuId)
                    // 可用库存 = 总库存 - 已被别人锁定的库存，大于 0 才算有货
                    .apply("stock - stock_locked > 0"))));
}
```

> **为什么传 List 而不是一个个查**：一个 spu 可能有几十个 sku，逐个远程调用会产生几十次网络往返。
> 批量传一次拿回一个 `Map<skuId, 有货?>`，一次调用解决。
>
> **为什么用 `stock - stock_locked > 0`**：库存字段是"总入库量"，别人下单后会锁库存，
> 只有扣掉锁定部分还有剩，才算真的能卖。

### 2.6 Feign 把数据发给 search

```java
// gulimall-product：ElasticSaveFeignClient
@FeignClient("gulimall-search")     // 服务名，去 Nacos 上找实例
public interface ElasticSaveFeignClient {
    @PostMapping("search/save/product")
    R productStatusUp(@RequestBody List<SkuEsModel> skuEsModels);
}
```

search 侧的接收接口：

```java
// gulimall-search：ElasticSaveController
@RequestMapping("search/save")
@RestController
public class ElasticSaveController {

    @Autowired
    private ProductSaveService productSaveService;

    @PostMapping("/product")
    public R productStatusUp(@RequestBody List<SkuEsModel> skuEsModels) {
        boolean hasError;
        try {
            hasError = productSaveService.productStatusUp(skuEsModels);
        } catch (Exception e) {
            log.error("ElasticSaveController商品上架错误", e);
            return R.error(BizCodeEnum.PRODUCT_UP_EXCEPTION.getCode(),
                           BizCodeEnum.PRODUCT_UP_EXCEPTION.getMsg());
        }
        // 返回 true 表示有失败
        return hasError
                ? R.error(BizCodeEnum.PRODUCT_UP_EXCEPTION.getCode(),
                          BizCodeEnum.PRODUCT_UP_EXCEPTION.getMsg())
                : R.ok();
    }
}
```

### 2.7 真正写入 ES（bulk 批量）

**这是整个存储流程里唯一直接操作 ES 的地方。**

```java
// gulimall-search：ProductSaveServiceImpl
@Override
public boolean productStatusUp(List<SkuEsModel> skuEsModels) throws IOException {

    // ① 组装批量请求：一个 sku 一条 index 操作
    BulkRequest.Builder br = new BulkRequest.Builder();
    for (SkuEsModel sku : skuEsModels) {
        br.operations(op -> op.index(idx -> idx
                .index(EsConstant.PRODUCT_INDEX)        // 索引名
                .id(sku.getSkuId().toString())          // 文档 id 用 skuId（天然幂等：上架两次不会产生重复文档）
                .document(sku)                          // 直接传对象，Jackson 自动序列化成 JSON
        ));
    }

    // ② 执行
    BulkResponse result = elasticsearchClient.bulk(br.build());

    // ③ 检查结果：bulk 是"逐条独立"的，部分失败不会让整批回滚
    if (result.errors()) {
        log.error("商品上架出错：");
        for (BulkResponseItem item : result.items()) {
            if (item.error() != null) {
                log.error("skuId={} 上架失败，原因：{}", item.id(), item.error().reason());
            }
        }
        return true;    // 有失败
    }

    log.info("商品上架成功:{}", result.items().stream().map(BulkResponseItem::id).toList());
    return false;       // 全部成功
}
```

**三个要点**：

| 要点 | 说明 |
|---|---|
| **用 skuId 当文档 id** | 同一个商品重复上架只会覆盖，不会产生重复文档 —— **天然幂等** |
| **bulk 逐条独立** | 100 条里有 1 条失败，另外 99 条照样写入。所以要遍历 `items()` 逐条检查错误，不能只看整体 |
| **`document(sku)` 直接传对象** | Jackson 自动序列化，不用手工 `JSON.toJSONString()` |

> ⚠️ **ES 是"近实时"，不是实时**：写完 1 秒内用 `_search` 可能查不到（数据还在内存缓冲区没生成可搜索的段）。
> 按 id 取（`GET product/_doc/1`）是实时的，搜索不是。想立刻可见就 `POST product/_refresh`。

---

## 三、从 ES 查出：商品检索

存储是"写一次"，检索是"读无数次"，所以这部分代码更复杂。**核心只有一个方法**：`MallSearchServiceImpl.search()`。

### 3.1 入口与出入参

```java
// gulimall-search：SearchController
@GetMapping({"/", "/list.html"})
public String listPage(SearchParam searchParam, Model model, HttpServletRequest request) {
    // 原始查询串，后面生成面包屑要用（点"×"时要把对应参数从 URL 里摘掉）
    searchParam.set_queryString(request.getQueryString());

    SearchResult result = mallSearchService.search(searchParam);

    model.addAttribute("result", result);
    return "list";      // → templates/list.html
}
```

**入参 `SearchParam`** —— 页面上的查询条件，Spring MVC 自动从 URL 参数装配：

```java
@Data
public class SearchParam {
    private String keyword;         // 全文关键字
    private List<Long> brandId;     // 品牌，可多选
    private Long catelog3Id;        // 三级分类 id
    private String sort;            // 排序：skuPrice_asc / saleCount_desc / hotScore_desc
    private Integer hasStock;       // 1=只看有货
    private String skuPrice;        // 价格区间 "1000_3000"、"_3000"、"1000_"
    private List<String> attrs;     // 规格属性，格式 "属性id_值1:值2"
    private Integer pageNum = 1;
    private String _queryString;    // 原始查询串（面包屑用）
}
```

**出参 `SearchResult`** —— 页面要渲染的全部内容：

```java
@Data
public class SearchResult {
    private List<SkuEsModel> product;   // 商品列表
    private Integer pageNum;            // 当前页
    private Long total;                 // 总条数
    private Integer totalPages;         // 总页数
    private List<Integer> pageNavs;     // 页码导航条
    private List<BrandVo> brands;       // 命中的品牌（左侧筛选栏）
    private List<AttrVo> attrs;         // 命中的规格属性（左侧筛选栏）
    private List<CatelogVo> catelogs;   // 命中的分类（左侧筛选栏）
    private List<NavVo> navs;           // 面包屑
    // ...内部类 BrandVo / AttrVo / CatelogVo / NavVo 略
}
```

> 注意 **`SearchParam` 和 ES 里的字段名不一定一样**：页面传 `catelog3Id`，ES 里叫 `catelogId`。
> 翻译的时候别搞混，写错了查不出东西还不报错。

### 3.2 构造查询：Java 代码 ↔ DSL 的对应关系

Java 客户端用 **Lambda 链式**写查询，写法和 Kibana 里的 JSON **几乎一一对应**。

先看纯 DSL 长什么样（完整版见 `es/product-get.txt`）：

```json
GET product/_search
{
  "query": {
    "bool": {
      "must":   [ { "match_phrase": { "skuTitle": "华为 Meta 30 Pro" } } ],
      "filter": [
        { "term":  { "catelogId": "225" } },
        { "terms": { "brandId": ["1","2","9"] } },
        { "nested": { "path": "attrs", "query": { "bool": { "must": [
              { "match": { "attrs.attrValue": "青金" } } ] } } } },
        { "term":  { "hasStock": "true" } },
        { "range": { "skuPrice": { "gte": 5888, "lte": 7000 } } }
      ]
    }
  }
}
```

换成 Java 代码：

```java
SearchResponse<SkuEsModel> response = client.search(s -> {
    s.index(EsConstant.PRODUCT_INDEX);              // 查哪个索引
    s.from((pageNum - 1) * PAGE_SIZE);              // 分页起点
    s.size(PAGE_SIZE);
    s.sort(so -> so.field(f -> f.field(sortField).order(sortOrder)));

    s.query(q -> q.bool(b -> {
        // ---------- must：全文检索关键词（参与打分） ----------
        if (StringUtils.hasText(searchParam.getKeyword())) {
            b.must(m -> m.matchPhrase(mp -> mp
                    .field("skuTitle")
                    .query(searchParam.getKeyword())));
        }

        // ---------- filter：其余条件（不参与打分，更快） ----------
        // ① 三级分类
        if (searchParam.getCatelog3Id() != null) {
            b.filter(f -> f.term(t -> t.field("catelogId").value(searchParam.getCatelog3Id())));
        }
        // ② 品牌（多选）
        if (searchParam.getBrandId() != null && !searchParam.getBrandId().isEmpty()) {
            List<FieldValue> ids = searchParam.getBrandId().stream().map(FieldValue::of).toList();
            b.filter(f -> f.terms(t -> t.field("brandId").terms(tf -> tf.value(ids))));
        }
        // ③ 规格属性（nested，见下）
        // ④ 是否有货
        // ⑤ 价格区间
        return b;
    }));

    // 聚合（左侧筛选栏）...
    // 高亮（关键词标红）...
    return s;
}, SkuEsModel.class);       // ← 指定结果类型，命中自动反序列化成对象
```

**`must` 和 `filter` 的区别**（影响性能和排序，值得理解）：

| | 参与打分 | 会缓存 | 用在哪 |
|---|---|---|---|
| `must` | ✅ 影响 `_score` 排序 | ❌ | 关键词检索（用户输入的词） |
| `filter` | ❌ 不算分 | ✅ 缓存结果 | 品牌、分类、价格、库存这类"筛选"条件 |

筛选条件不需要算相关性，放 `filter` 里 ES 能缓存、更快。本项目就是**关键词一个 `must`，其余全 `filter`**。

#### 关键词为什么用 `match_phrase` 而不是 `match`

| 写法 | "华为 Meta 30 Pro" 的效果 |
|---|---|
| `match` | 分词后任意命中一个就行 —— 搜出所有含"华为"或"Pro"的商品，太宽 |
| `match_phrase` | 要求**这一整串连续出现**，精确得多 |

#### 规格属性为什么用 nested

页面传参格式是 `attrs=属性id_值1:值2`，比如 `attrs=5_青金&attrs=1_aaa:bbb`。

```java
for (String attr : searchParam.getAttrs()) {
    String[] idAndValues = attr.split("_");
    if (idAndValues.length != 2) continue;              // 脏参数跳过，别把整个检索打挂

    final long attrId = Long.parseLong(idAndValues[0]);
    final List<FieldValue> attrValues = Arrays.stream(idAndValues[1].split(":"))
            .map(FieldValue::of).toList();

    b.filter(f -> f.nested(n -> n
            .path("attrs")                               // 钻进 nested 字段
            .query(nq -> nq.bool(nb -> nb
                    // 同一个 attrs 对象里：attrId 相等 且 attrValue 是其中之一
                    .must(m -> m.term(t -> t.field("attrs.attrId").value(attrId)))
                    .must(m -> m.terms(t -> t.field("attrs.attrValue").terms(tf -> tf.value(attrValues))))
            ))));
}
```

> ⚠️ **每个属性生成一个独立的 nested**，不能把所有属性塞进同一个 nested ——
> 塞一起语义会变成"存在**某个** attrs 对象同时满足所有属性"，和"每个属性各自满足"完全不同。
>
> ⚠️ nested 里的字段名要写**全路径** `attrs.attrId`，只写 `attrId` 一条都查不到。

#### 价格区间为什么用 `untyped`

`skuPrice` 映射是 `keyword`（字符串），而 ES 的 range 查询有 `date`/`number`/`term`/`untyped` 四个分支，
字符串要走 `untyped`：

```java
b.filter(f -> f.range(r -> r.untyped(u -> u
        .field("skuPrice")
        .gte(JsonData.of(gte))
        .lte(JsonData.of(lte)))));
```

> 页面传 `skuPrice=1000_3000`，也允许只写一端：`_3000` 表示 ≤3000，`1000_` 表示 ≥1000。

### 3.3 聚合：左侧筛选栏怎么来的

**聚合 ≈ SQL 的 GROUP BY**。它回答的问题是：**"这批命中结果里，实际涉及哪些品牌/分类/属性？"**

页面左侧的筛选栏就是靠它渲染的 —— 没命中的品牌不该出现在选项里。

```java
// 品牌：先按 brandId 分组，组内再取一次品牌名和图标
s.aggregations(AGG_BRAND, a -> a
        .terms(t -> t.field("brandId").size(10))
        .aggregations(AGG_BRAND_NAME, sa -> sa.terms(t -> t.field("brandName").size(10)))
        .aggregations(AGG_BRAND_IMG,  sa -> sa.terms(t -> t.field("brandImg").size(10))));

// 分类：同理
s.aggregations(AGG_CATELOG, a -> a
        .terms(t -> t.field("catelogId").size(10))
        .aggregations(AGG_CATELOG_NAME, sa -> sa.terms(t -> t.field("catelogName").size(10))));

// 属性：nested 字段聚合前必须先"钻进去"，所以外层是 nested，里面才是 terms
s.aggregations(AGG_ATTRS, a -> a
        .nested(n -> n.path("attrs"))
        .aggregations(AGG_ATTR_ID, sa -> sa
                .terms(t -> t.field("attrs.attrId").size(10))
                .aggregations(AGG_ATTR_NAME,  ssa -> ssa.terms(t -> t.field("attrs.attrName").size(10)))
                .aggregations(AGG_ATTR_VALUE, ssa -> ssa.terms(t -> t.field("attrs.attrValue").size(10)))));
```

> **为什么品牌要"组内再聚合一次"**：`brandId` 是个数字，页面上要显示品牌**名字**和 **logo**。
> 一个 brandId 只对应一个品牌名，所以组内必然只有一个 bucket，取第 0 个就是。

解析聚合结果时有个**最容易卡住的点** —— terms 聚合有三种访问器：

| 被聚合字段类型 | 访问器 | `bucket.key()` 返回 |
|---|---|---|
| `keyword` / `text` | **`.sterms()`** | `FieldValue`（要 `.stringValue()` 转） |
| `byte/short/int/long` | **`.lterms()`** | `long`（**没有** `longValue()` 方法） |
| `float/double` | **`.dterms()`** | `double` |

**选错分支直接抛 `IllegalStateException`。** 原因：ES 响应 JSON 里没有类型标记，
客户端没法从 `buckets` 数组看出 key 是字符串还是数字，只好按推断拆成三个变体。

对应到本项目：`brandId`/`catelogId`/`attrId` 是 `long` → 用 `.lterms()`；
`brandName` 是 `keyword` → 用 `.sterms()`。

```java
// 品牌
for (LongTermsBucket bucket : brandAgg.lterms().buckets().array()) {
    BrandVo vo = new BrandVo();
    vo.setBrandId(bucket.key());
    vo.setBrandName(firstStringKey(bucket.aggregations(), AGG_BRAND_NAME));
    vo.setBrandImg(firstStringKey(bucket.aggregations(), AGG_BRAND_IMG));
    brands.add(vo);
}

// 取子聚合里第一个 bucket 的字符串 key
private static String firstStringKey(Map<String, Aggregate> aggs, String aggName) {
    Aggregate agg = aggs.get(aggName);
    if (agg == null) return null;
    List<StringTermsBucket> buckets = agg.sterms().buckets().array();
    return buckets.isEmpty() ? null : buckets.getFirst().key().stringValue();
}
```

> `avg` / `sum` / `min` / `max` **没有这个问题** —— 数值结果只有一种类型，`.avg()` 永远正确。

### 3.4 高亮：关键词标红

```java
s.highlight(h -> h
        .fields("skuTitle", f -> f)
        .preTags("<b style='color:red'>")
        .postTags("</b>"));
```

命中的话，把带 `<b>` 的片段替换掉原标题：

```java
Map<String, List<String>> highlight = hit.highlight();
if (highlight != null && highlight.containsKey("skuTitle")) {
    sku.setSkuTitle(highlight.get("skuTitle").getFirst());
}
```

模板侧要用 **`th:utext`**（不转义）才能让 `<b>` 生效：

```html
<a th:utext="${product.getSkuTitle()}"></a>
```

> ⚠️ **`postTags` 必须写 `</b>`，不能写 `<b/>`**。HTML 里对 `<b>` 这类非空元素，
> 自闭合语法**无效** —— `<b/>` 会被解析成"又开了一个 `<b>`"，而这个 `<b>` 永远不闭合，
> 按 HTML5 的规则会**泄漏到后面所有兄弟节点**上。
> 后果：CSS 的 `.rig_tab > div` 这类子选择器失效，图片失去尺寸限制被撑成原始大小，整张卡片继承 `color:red` 全变红。

### 3.5 面包屑：点"×"取消某个筛选

用户勾选属性后，页面顶部要显示"颜色: 青金 ×"，点 × 就取消这个条件。

显示**值**很简单（参数里就有），但显示**中文名**得查 —— 所以要 Feign 调 product 服务：

```java
List<NavVo> navVoList = searchParam.getAttrs().stream().map(attr -> {
    NavVo navVo = new NavVo();
    String[] s = attr.split("_");
    navVo.setNavValue(s[1]);                       // 显示值

    // 远程查属性名
    R r = productFeignClient.attrInfo(Long.parseLong(s[0]));
    navVo.setNavName(r.getCode() == 0
            ? ((AttrRespVo) r.getData("attr", new TypeReference<AttrRespVo>() {})).getAttrName()
            : s[0]);                               // 查不到就退回显示 id

    // "×"的链接 = 原查询串里去掉这一个 attr 参数
    String encoded = URLEncoder.encode(attr, StandardCharsets.UTF_8).replace("+", "%20");
    navVo.setLink("http://search.gulimall.vip/list.html?catelog3Id=225&"
            + searchParam.get_queryString().replace("&attrs=" + encoded, ""));
    return navVo;
}).toList();
result.setNavs(navVoList);
```

Feign 接口：

```java
@FeignClient("gulimall-product")
public interface ProductFeignClient {
    @GetMapping("/product/attr/info/{attrId}")
    R attrInfo(@PathVariable("attrId") Long attrId);
}
```

### 3.6 排序参数必须做白名单

`sort` 是**页面传上来的参数**，会**原样当成 ES 字段名**用。传个不存在的字段（或页面拼错传了 `undefined`），
ES 直接抛：

```
No mapping found for [undefined] in order to sort on
→ search_phase_execution_exception: all shards failed
```

**表现为整个检索接口 500、页面全白** —— 一个脏参数就能把列表页打挂。所以要挡在 ES 外面：

```java
private static final Set<String> SORTABLE_FIELDS =
        Set.of("skuId", "skuPrice", "saleCount", "hotScore");

if (sortSplit.length == 2 && SORTABLE_FIELDS.contains(sortSplit[0])) {
    sortField = sortSplit[0];
    sortOrder = "asc".equalsIgnoreCase(sortSplit[1]) ? SortOrder.Asc : SortOrder.Desc;
} else {
    sortField = "skuId";                    // 非法值退回默认排序
    sortOrder = SortOrder.Desc;
}
```

> ⚠️ **只判断 `length == 2` 不够** —— `"undefined_desc"`、`"abc_asc"` 都能通过，然后在 ES 那边炸。
>
> **为什么要给默认排序**：不给的话按 `_score` 排，而没有关键词时所有文档得分都一样，
> 返回顺序由分片决定、每次可能不同 —— **翻页会看到重复或漏掉的商品**。

### 3.7 异常兜底

```java
try {
    // ...整个检索流程
} catch (IOException e) {
    log.error("商品检索失败，检索参数：{}", searchParam, e);
}
return result;      // 返回空结果，页面正常渲染，而不是 500
```

ES 客户端所有请求方法都抛受检 `IOException`。**检索失败不该把整个列表页打挂成 500**，
返回空结果让页面正常渲染更好。

---

## 四、踩坑速查

| 现象 | 原因 | 解法 |
|---|---|---|
| 刚写入用 `_search` 查不到 | ES 是近实时，1 秒后才可搜 | 等 1 秒，或 `POST 索引/_refresh` |
| nested 字段用普通 `match` 查，**一条都查不到**（不是不准，是查不到） | nested 里的对象是独立隐藏文档 | 用 `nested` 查询 + 全路径字段名 `attrs.xxx` |
| 数组对象的查询结果张冠李戴 | 默认会拍平数组 | 数组里是对象时必须用 `nested` |
| 聚合取 key 抛 `IllegalStateException` | 用错了访问器（sterms/lterms/dterms） | 按 mapping 类型选，见 §3.3 表 |
| 聚合文本字段报错 | `text` 类型不能直接聚合 | 用 `.keyword` 子字段 |
| 检索接口 500、页面全白 | `sort` 传了不存在的字段 | 加字段白名单，见 §3.6 |
| 翻页看到重复/漏掉的商品 | 没配默认排序，`_score` 相同时顺序不稳定 | 给个默认排序字段 |
| 关键词标红后整个卡片变红、布局崩 | `postTags` 写成了 `<b/>` | 必须是 `</b>` |
| bulk 部分文档没写进去但接口返回成功 | 只看整体没看逐条 | 遍历 `result.items()` 检查 `error()` |
| 上架重复调用产生重复文档 | — | 用 skuId 当文档 id，天然幂等 |
| 价格精度丢失 | 没预建 mapping，动态映射推成了 float | 索引必须预先手工建好 |

---

## 五、相关文件索引

| 文件 | 作用 |
|---|---|
| `gulimall-search/.../config/GulimallElasticSearchConfig.java` | ES 客户端 Bean |
| `gulimall-search/.../constant/EsConstant.java` | 索引名常量 |
| `gulimall-search/.../controller/ElasticSaveController.java` | 接收上架数据 |
| `gulimall-search/.../service/impl/ProductSaveServiceImpl.java` | **写入 ES**（bulk） |
| `gulimall-search/.../service/impl/MallSearchServiceImpl.java` | **查询 ES**（检索全部逻辑） |
| `gulimall-search/.../vo/SearchParam.java` / `SearchResult.java` | 检索入参 / 出参 |
| `gulimall-search/.../feign/ProductFeignClient.java` | 查属性名（面包屑用） |
| `gulimall-search/src/main/resources/es/product-mapping.txt` | 建索引语句 |
| `gulimall-search/src/main/resources/es/product-get.txt` | 检索 DSL 参考 |
| `gulimall-api/.../search/SkuEsModel.java` | 存进 ES 的数据结构 |
| `gulimall-product/.../service/impl/SpuInfoServiceImpl.java` | `up()` 上架主流程 |
| `gulimall-product/.../feign/ElasticSaveFeignClient.java` | 调 search 存数据 |
| `gulimall-product/.../feign/WareSkuFeignClient.java` | 调 ware 查库存 |
| `gulimall-ware/.../controller/WareSkuController.java` | 库存查询接口 |
