package com.atguigu.gulimall.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.atguigu.gulimall.api.product.AttrRespVo;
import com.atguigu.gulimall.api.search.SkuEsModel;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.search.constant.EsConstant;
import com.atguigu.gulimall.search.feign.ProductFeignClient;
import com.atguigu.gulimall.search.service.MallSearchService;
import com.atguigu.gulimall.search.vo.SearchParam;
import com.atguigu.gulimall.search.vo.SearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @ClassName: MallSearchServiceImpl
 * @Package: com.atguigu.gulimall.search.service.impl
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/24 - 18:46
 * @Version: v1.0
 */
@Slf4j
@Service("mallSearchService")
public class MallSearchServiceImpl implements MallSearchService {

    /**
     * 每页展示的商品数（对应 product-get.txt 里的 "size": 20）
     */
    private static final int PAGE_SIZE = 20;
    /**
     * 允许排序的字段白名单 —— 值必须与 ES mapping 里的字段名一致。
     * <p>
     * ⚠️ 为什么必须做白名单：sort 是【页面传参】，而下面这段代码会把它原样当成 ES 字段名
     * 去 <code>.field(sortField)</code>。传一个不存在的字段（或页面拼错、属性缺失导致
     * 传了 "undefined"）时，ES 直接抛：
     * <pre>
     *   No mapping found for [undefined] in order to sort on
     *   → search_phase_execution_exception: all shards failed
     * </pre>
     * 表现为整个检索接口 500、页面全白 —— 一个脏参数就把列表页打挂了。
     * 白名单把这类值挡在 ES 外面，退回默认排序（而不是报错）。
     * <p>
     * 对应页面上三个排序链接的 sort 属性（list.html 的 .sort_a）。
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of("skuId", "skuPrice", "saleCount", "hotScore");
    /**
     * 聚合结果里各聚合的名字
     */
    private static final String AGG_BRAND = "brand_agg";
    private static final String AGG_BRAND_NAME = "brand_name_agg";
    private static final String AGG_BRAND_IMG = "brand_img_agg";
    private static final String AGG_CATELOG = "catelog_agg";
    private static final String AGG_CATELOG_NAME = "catelog_name_agg";
    private static final String AGG_ATTRS = "attrs_agg";
    private static final String AGG_ATTR_ID = "attr_id_agg";
    private static final String AGG_ATTR_NAME = "attr_name_agg";
    private static final String AGG_ATTR_VALUE = "attr_value_agg";
    @Autowired
    private ProductFeignClient productFeignClient;
    @Autowired
    private ElasticsearchClient client;

    /**
     * 取 terms 子聚合里【第一个 bucket 的 key】。
     * <p>
     * 用途：brandId 分组后，组内再按 brandName 分组 —— 因为一个 brandId 只会对应一个品牌名，
     * 所以组内必然只有一个 bucket，取第 0 个就是该品牌的名称（catelogName、attrName 同理）。
     * <p>
     * ⚠️ 为什么用 .sterms()：ES 响应里没有类型标记，客户端按推断出的 key 类型拆成三个强类型变体
     * （sterms/lterms/dterms），这里聚合的都是 keyword 字段，所以是 sterms，
     * 取到的 key 是 FieldValue，要 .stringValue() 转成字符串。
     */
    private static String firstStringKey(Map<String, Aggregate> aggs, String aggName) {
        Aggregate agg = aggs.get(aggName);
        if (agg == null) {
            return null;
        }
        List<StringTermsBucket> buckets = agg.sterms().buckets().array();
        return buckets.isEmpty() ? null : buckets.getFirst().key().stringValue();
    }

    /**
     * 将页面全部检索条件封装为对象
     *
     * @param searchParam 检索的所有参数
     * @return 返回检索的结果
     */
    @Override
    public SearchResult search(SearchParam searchParam) {

        SearchResult result = new SearchResult();

        // 页码兜底：SearchParam 里默认是 1，但页面传个负数/0 过来也得能扛住
        final int pageNum = (searchParam.getPageNum() == null || searchParam.getPageNum() < 1)
                ? 1 : searchParam.getPageNum();

        // 解析排序参数（sort=skuPrice_asc / saleCount_desc / hotScore_desc）
        // 解析放在 lambda 外面，是为了让 lambda 里能直接捕获 final 变量
        final String sortField;
        final SortOrder sortOrder;
        String[] sortSplit = StringUtils.hasText(searchParam.getSort())
                ? searchParam.getSort().split("_") : new String[0];
        // ⚠️ 必须校验字段名在【白名单】里（原因见 SORTABLE_FIELDS 注释）：
        //    只判断 length == 2 是不够的 —— "undefined_desc"、"abc_asc" 都会通过，
        //    然后在 ES 那边炸成 all shards failed。
        if (sortSplit.length == 2 && SORTABLE_FIELDS.contains(sortSplit[0])) {
            sortField = sortSplit[0];
            sortOrder = "asc".equalsIgnoreCase(sortSplit[1]) ? SortOrder.Asc : SortOrder.Desc;
        } else {
            // 默认排序：按 skuId 倒序（对应 product-get.txt 里的 "sort": [{"skuId": {"order": "desc"}}]）
            // 给个默认排序的意义：不写的话按 _score 排，而"没有关键词"时所有文档得分都一样，
            // 返回顺序由分片决定、每次可能不同 —— 翻页会看到重复/漏掉的商品
            sortField = "skuId";
            sortOrder = SortOrder.Desc;
        }

        try {
            // ============================================================
            // 一、构造并执行检索请求
            //     s -> {...} 这个 lambda 就是对 SearchRequest.Builder 的配置，
            //     与 product-get.txt 里那段 JSON 的每一行一一对应
            // ============================================================
            SearchResponse<SkuEsModel> response = client.search(s -> {

                // 查哪个索引
                s.index(EsConstant.PRODUCT_INDEX);

                // 分页：from 从 0 开始，第 n 页的起点是 (n-1)*size
                s.from((pageNum - 1) * PAGE_SIZE);
                s.size(PAGE_SIZE);

                // 排序
                s.sort(so -> so.field(f -> f.field(sortField).order(sortOrder)));

                // ---------- 1、query：bool 把 must 和 filter 包在一起 ----------
                s.query(q -> q.bool(b -> {

                    // ---------- must：全文检索关键词（参与打分） ----------
                    if (StringUtils.hasText(searchParam.getKeyword())) {
                        // match_phrase：短语匹配，要求"华为 Meta 30 Pro"这一整个子串出现，
                        // 而不是分成"华为""Meta""30""Pro"任意命中一个
                        b.must(m -> m.matchPhrase(mp -> mp
                                .field("skuTitle")
                                .query(searchParam.getKeyword())));
                    }

                    // ---------- filter ①：三级分类 ----------
                    // 注：SearchParam 里叫 catelog3Id，ES 里的字段叫 catelogId（拼写不一致，别写错）
                    if (searchParam.getCatelog3Id() != null) {
                        final long catelogId = searchParam.getCatelog3Id();
                        b.filter(f -> f.term(t -> t
                                .field("catelogId")
                                .value(catelogId)));
                    }

                    // ---------- filter ②：品牌（多选） ----------
                    if (searchParam.getBrandId() != null && !searchParam.getBrandId().isEmpty()) {
                        final List<FieldValue> brandIds = searchParam.getBrandId().stream()
                                .map(FieldValue::of)
                                .collect(Collectors.toList());
                        b.filter(f -> f.terms(t -> t
                                .field("brandId")
                                .terms(tf -> tf.value(brandIds))));
                    }

                    // ---------- filter ③：规格属性（nested） ----------
                    // 页面传参格式：attrs=<attrId>_<值1>:<值2>&attrs=<attrId>_<值>
                    //   例：attrs=5_青金&attrs=1_aaa:bbb
                    // 每个 attrs 参数要生成【一个独立的 nested 查询】，而不是把所有属性塞进
                    // 同一个 nested —— 塞一起会变成"存在某个对象同时满足所有属性"，语义完全不同
                    if (searchParam.getAttrs() != null && !searchParam.getAttrs().isEmpty()) {
                        for (String attr : searchParam.getAttrs()) {
                            String[] idAndValues = attr.split("_");
                            if (idAndValues.length != 2) {
                                // 参数格式不对就跳过，别让一个脏参数把整个检索打挂
                                continue;
                            }
                            final long attrId = Long.parseLong(idAndValues[0]);
                            final List<FieldValue> attrValues = java.util.Arrays
                                    .stream(idAndValues[1].split(":"))
                                    .map(FieldValue::of)
                                    .collect(Collectors.toList());

                            b.filter(f -> f.nested(n -> n
                                    .path("attrs")
                                    .query(nq -> nq.bool(nb -> nb
                                            // 同一个 attrs 对象里：attrId 相等 且 attrValue 是其中一个
                                            .must(m -> m.term(t -> t
                                                    .field("attrs.attrId")
                                                    .value(attrId)))
                                            .must(m -> m.terms(t -> t
                                                    .field("attrs.attrValue")
                                                    .terms(tf -> tf.value(attrValues))))))));
                        }
                    }

                    // ---------- filter ④：是否有货 ----------
                    // 页面传 hasStock=1 表示"只看有货"；不传则不筛选
                    if (searchParam.getHasStock() != null) {
                        final boolean hasStock = searchParam.getHasStock() == 1;
                        b.filter(f -> f.term(t -> t
                                .field("hasStock")
                                .value(hasStock)));
                    }

                    // ---------- filter ⑤：价格区间 ----------
                    // 页面传参格式：skuPrice=1000_3000，允许只写一端：
                    //   "_3000" → lte 3000     "1000_" → gte 1000
                    if (StringUtils.hasText(searchParam.getSkuPrice())) {
                        final String skuPrice = searchParam.getSkuPrice();
                        final String[] range = skuPrice.split("_");

                        // ⚠️ range 要先 .untyped(...) 再写 field：
                        //    RangeQuery 在客户端里是个 union builder，只能在其一的分支里写条件，
                        //    分支有 date / number / term / untyped 四个。
                        //    skuPrice 映射是 keyword（字符串），所以要选 untyped 分支。
                        if (range.length == 2) {
                            final String gte = range[0];
                            final String lte = range[1];
                            b.filter(f -> f.range(r -> r.untyped(u -> u
                                    .field("skuPrice")
                                    .gte(JsonData.of(gte))
                                    .lte(JsonData.of(lte)))));
                        } else if (range.length == 1) {
                            final String only = range[0];
                            if (skuPrice.startsWith("_")) {
                                b.filter(f -> f.range(r -> r.untyped(u -> u
                                        .field("skuPrice")
                                        .lte(JsonData.of(only)))));
                            } else {
                                b.filter(f -> f.range(r -> r.untyped(u -> u
                                        .field("skuPrice")
                                        .gte(JsonData.of(only)))));
                            }
                        }
                    }

                    return b;
                }));

                // ---------- 2、聚合：品牌 / 分类 / 属性 ----------
                // 聚合的意义："命中结果里实际涉及到了哪些品牌、哪些分类、哪些属性值"，
                // 页面据此渲染左侧筛选条件 —— 没命中的品牌不该出现在筛选项里
                s.aggregations(AGG_BRAND, a -> a
                        .terms(t -> t.field("brandId").size(10))
                        // 子聚合：brandId 是数字，页面上要显示品牌名和 logo，
                        // 所以按 brandId 分组后，在组内再取一次 brandName / brandImg
                        .aggregations(AGG_BRAND_NAME, sa -> sa.terms(t -> t.field("brandName").size(10)))
                        .aggregations(AGG_BRAND_IMG, sa -> sa.terms(t -> t.field("brandImg").size(10))));

                s.aggregations(AGG_CATELOG, a -> a
                        .terms(t -> t.field("catelogId").size(10))
                        .aggregations(AGG_CATELOG_NAME, sa -> sa.terms(t -> t.field("catelogName").size(10))));

                // attrs 是 nested 字段：聚合前必须先"钻进去"，所以最外层是 nested 聚合，
                // 里面才是 terms —— 这一层结构不能省（对应 product-get.txt 的 attrs_agg）
                s.aggregations(AGG_ATTRS, a -> a
                        .nested(n -> n.path("attrs"))
                        .aggregations(AGG_ATTR_ID, sa -> sa
                                .terms(t -> t.field("attrs.attrId").size(10))
                                .aggregations(AGG_ATTR_NAME, ssa -> ssa.terms(t -> t.field("attrs.attrName").size(10)))
                                .aggregations(AGG_ATTR_VALUE, ssa -> ssa.terms(t -> t.field("attrs.attrValue").size(10)))));

                // ---------- 3、高亮：把关键词在 skuTitle 里标红 ----------
                // ⚠️ postTags 必须是 </b>，写成 <b/> 会把整个列表页的布局搞崩：
                //    HTML 的自闭合语法对 <b> 这类非空元素【无效】，<b/> 会被解析成
                //    "又开了一个 <b>"，而这个 <b> 永远不会被关闭 —— 按 HTML5 的
                //    「活动格式化元素」规则，它会泄漏到后面所有兄弟节点上（含下一张卡片的 <div>）。
                //    后果：CSS 里的 .rig_tab > div 等【子选择器】失效（div 不再是直接子元素），
                //          图片失去 205x210 限制被撑成原始大小，且整张卡片继承 color:red 全变红。
                //    模板侧对应 <a th:utext="..."> —— 用 th:text 看不出来，因为标签被转义了。
                s.highlight(h -> h
                        .fields("skuTitle", f -> f)
                        .preTags("<b style='color:red'>")
                        .postTags("</b>"));

                return s;
            }, SkuEsModel.class);

            // ============================================================
            // 二、解析结果
            // ============================================================

            // ---------- 1、商品列表 ----------
            List<SkuEsModel> skuList = new ArrayList<>();
            for (Hit<SkuEsModel> hit : response.hits().hits()) {
                SkuEsModel sku = hit.source();
                if (sku == null) {
                    // source() 可能为 null（比如用了 _source 过滤），防御一下别 NPE
                    continue;
                }
                // 高亮命中的话，用带 <b> 标签的片段替换原标题，页面才能标红；
                // 没命中时 highlight 里根本没有这个 key，保持原标题不变
                Map<String, List<String>> highlight = hit.highlight();
                if (highlight != null && highlight.containsKey("skuTitle")) {
                    sku.setSkuTitle(highlight.get("skuTitle").getFirst());
                }
                skuList.add(sku);
            }
            result.setProduct(skuList);

            // ---------- 2、分页信息 ----------
            long total = response.hits().total() == null ? 0L : response.hits().total().value();
            result.setTotal(total);
            result.setPageNum(pageNum);
            // 总页数 = ceil(total / PAGE_SIZE)，用整除 + 取余避免引入 Math.ceil 的浮点误差
            int totalPages = (int) (total / PAGE_SIZE + (total % PAGE_SIZE == 0 ? 0 : 1));
            result.setTotalPages(totalPages);

            // 页码导航条。⚠️ 这里是全量 1..totalPages，商品量大时前端会渲染出几百上千个页码，
            //    生产上应改成只出当前页附近的窗口（如 pageNum±2 + 首尾页）
            List<Integer> pageNavs = new ArrayList<>();
            for (int i = 1; i <= totalPages; i++) {
                pageNavs.add(i);
            }
            result.setPageNavs(pageNavs);

            // ---------- 3、聚合结果 ----------
            Map<String, Aggregate> aggs = response.aggregations();

            // 品牌：terms(brandId) → 组内子聚合取品牌名/图标
            List<SearchResult.BrandVo> brands = new ArrayList<>();
            Aggregate brandAgg = aggs.get(AGG_BRAND);
            if (brandAgg != null) {
                for (LongTermsBucket bucket : brandAgg.lterms().buckets().array()) {
                    SearchResult.BrandVo vo = new SearchResult.BrandVo();
                    vo.setBrandId(bucket.key());
                    vo.setBrandName(firstStringKey(bucket.aggregations(), AGG_BRAND_NAME));
                    vo.setBrandImg(firstStringKey(bucket.aggregations(), AGG_BRAND_IMG));
                    brands.add(vo);
                }
            }
            result.setBrands(brands);

            // 分类：同上
            List<SearchResult.CatelogVo> catelogs = new ArrayList<>();
            Aggregate catelogAgg = aggs.get(AGG_CATELOG);
            if (catelogAgg != null) {
                for (LongTermsBucket bucket : catelogAgg.lterms().buckets().array()) {
                    SearchResult.CatelogVo vo = new SearchResult.CatelogVo();
                    vo.setCatelogId(bucket.key());
                    vo.setCatelogName(firstStringKey(bucket.aggregations(), AGG_CATELOG_NAME));
                    catelogs.add(vo);
                }
            }
            result.setCatelogs(catelogs);

            // 属性：nested 聚合比上面多一层，取子聚合时要先 .nested() 钻进去
            List<SearchResult.AttrVo> attrVos = new ArrayList<>();
            Aggregate attrsAgg = aggs.get(AGG_ATTRS);
            if (attrsAgg != null) {
                Aggregate attrIdAgg = attrsAgg.nested().aggregations().get(AGG_ATTR_ID);
                if (attrIdAgg != null) {
                    for (LongTermsBucket bucket : attrIdAgg.lterms().buckets().array()) {
                        SearchResult.AttrVo vo = new SearchResult.AttrVo();
                        vo.setAttrId(bucket.key());
                        vo.setAttrName(firstStringKey(bucket.aggregations(), AGG_ATTR_NAME));

                        // 一个属性下有多个可选值，要【全部】取出来 —— 页面上是多选
                        // （比如"颜色"下面有 青金/黑色/白色，不能只取第一个）
                        List<String> values = new ArrayList<>();
                        Aggregate valueAgg = bucket.aggregations().get(AGG_ATTR_VALUE);
                        if (valueAgg != null) {
                            for (StringTermsBucket valueBucket : valueAgg.sterms().buckets().array()) {
                                values.add(valueBucket.key().stringValue());
                            }
                        }
                        vo.setAttrValue(values);
                        attrVos.add(vo);
                    }
                }
            }
            result.setAttrs(attrVos);

            // ---------- 4、面包屑导航 ----------
            if (searchParam.getAttrs() != null && !searchParam.getAttrs().isEmpty()) {
                List<SearchResult.NavVo> navVoList = searchParam.getAttrs().stream().map(attr -> {
                            SearchResult.NavVo navVo = new SearchResult.NavVo();
                            String[] s = attr.split("_");
                            navVo.setNavValue(s[1]);
                            R r = productFeignClient.attrInfo(Long.parseLong(s[0]));
                            if (r.getCode() == 0) {
                                AttrRespVo attrRespVo = r.getData("attr", new TypeReference<AttrRespVo>() {
                                });
                                navVo.setNavName(attrRespVo.getAttrName());
                            } else {
                                navVo.setNavName(s[0]);
                            }

                            String encode;
                            encode = URLEncoder.encode(attr, StandardCharsets.UTF_8);
                            encode = encode.replace("+", "%20");
                            String replace = searchParam.get_queryString().replace("&attrs=" + encode, "");
                            navVo.setLink("http://search.gulimall.com/list.html?catelog3Id=225&" + replace);
                            return navVo;
                        }
                ).toList();
                result.setNavs(navVoList);
            }

        } catch (IOException e) {
            // ES 客户端的所有请求方法都抛受检 IOException（网络/序列化失败）
            // 检索失败不该把整个列表页打挂成 500，返回空结果让页面正常渲染
            log.error("商品检索失败，检索参数：{}", searchParam, e);
        }

        return result;
    }

}
