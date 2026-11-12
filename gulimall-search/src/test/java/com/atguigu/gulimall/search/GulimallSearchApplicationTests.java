package com.atguigu.gulimall.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.SumAggregate;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.atguigu.gulimall.api.search.SkuEsModel;
import com.atguigu.gulimall.search.service.MallSearchService;
import com.atguigu.gulimall.search.vo.SearchParam;
import com.atguigu.gulimall.search.vo.SearchResult;
import lombok.Data;
import lombok.ToString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 不需要 @EnableDiscoveryClient —— 它已加在主启动类上，
// @SpringBootTest 启动的就是那个类，这里再写一遍没有作用
@SpringBootTest
class GulimallSearchApplicationTests {

    @Autowired
    private ElasticsearchClient client;

    @Test
    void contextLoads() {
        System.out.println(client);
    }

    @Autowired
    private MallSearchService mallSearchService;

    /** 与 MallSearchServiceImpl.PAGE_SIZE 保持一致 */
    private static final int PAGE_SIZE = 20;

    // ============================================================
    // 商品检索：参数化测试 MallSearchServiceImpl.search
    // ------------------------------------------------------------
    // 【数据前提】索引 product 里要有 es/product-search.txt 里那 6 条（skuId 5~10）：
    //
    //   skuId  skuTitle                        skuPrice  hasStock  brandId  catelogId
    //     5    华为 Meta 30 Pro 黑色 新机 5888     5888      true       1        225
    //     6    华为 Meta 30 Pro 黑色 新机 6288     6288      false      1        225
    //     7    华为 Meta 30 Pro 白色 新机 5888     5888      true       1        225
    //     8    华为 Meta 30 Pro 白色 新机 6288     6288      false      1        225
    //     9    华为 Meta 30 Pro 绿色 新机 5888     5888      false      1        225
    //    10    华为 Meta 30 Pro 绿色 新机 6288     6288      true       1        225
    //
    //   公共属性 attrs：[{5,主题颜色,青金}, {1,入网型号,aaa}, {2,上市年份,aaa}]
    //
    // ⚠️ 数据没导入时断言会大面积挂（典型报错 "expected 6 but was 0"），
    //    先照 product-search.txt 把数据灌进去再跑。
    // ⚠️ 关键词用例的命中数依赖 ik_smart 的分词结果（"黑色" 要能被切成一个词）。
    // ============================================================

    /**
     * 用 lambda 设置字段，避免每个用例都写一长串 setter：
     * <pre>sp(p -> p.setKeyword("华为"))</pre>
     */
    private static SearchParam sp(Consumer<SearchParam> setter) {
        SearchParam param = new SearchParam();
        setter.accept(param);
        return param;
    }

    /**
     * 测试数据源：每组 = {用例名（占位符 {0}）, 检索参数, 期望命中总数}
     */
    static Stream<Arguments> param() {
        return Stream.of(
                // ---------- 基础 / 分页 ----------
                Arguments.of("01 无条件：查全部",
                        sp(p -> {
                        }), 6L),
                Arguments.of("02 分页：第 2 页（总 6 条 < 每页 20，本页应空但 total 仍是 6）",
                        sp(p -> p.setPageNum(2)), 6L),

                // ---------- 关键词（must + match_phrase，参与打分） ----------
                Arguments.of("03 关键词：华为（全部 6 条）",
                        sp(p -> p.setKeyword("华为")), 6L),
                Arguments.of("04 关键词：黑色（只 5、6）",
                        sp(p -> p.setKeyword("黑色")), 2L),
                Arguments.of("05 关键词：不存在的词",
                        sp(p -> p.setKeyword("爱疯十五")), 0L),

                // ---------- 分类（filter + term） ----------
                Arguments.of("06 分类：catelogId=225",
                        sp(p -> p.setCatelog3Id(225L)), 6L),
                Arguments.of("07 分类：不存在的 catelogId",
                        sp(p -> p.setCatelog3Id(999L)), 0L),

                // ---------- 品牌（filter + terms） ----------
                Arguments.of("08 品牌：brandId=[1]（Huawei）",
                        sp(p -> p.setBrandId(List.of(1L))), 6L),
                Arguments.of("09 品牌：brandId=[2,9]（都不存在）",
                        sp(p -> p.setBrandId(List.of(2L, 9L))), 0L),

                // ---------- 库存（filter + term，1=只看有货） ----------
                Arguments.of("10 库存：只看有货（5、7、10）",
                        sp(p -> p.setHasStock(1)), 3L),
                Arguments.of("11 库存：只看无货（6、8、9）",
                        sp(p -> p.setHasStock(0)), 3L),

                // ---------- 价格（filter + range） ----------
                // ⚠️⚠️ 这三组的期望值是【当前 mapping 下的实际行为】，不是「对」的行为，别被 3/0 绕晕：
                //
                //     skuPrice 映射成了 keyword，而写入的是数字，ES 建索引时转成字符串 ——
                //     实测索引里的 term 是 "5888.0000" / "6288.0000"（带 4 位小数！）。
                //     keyword 的 range 走【字典序】比较，于是：
                //
                //         gte "5888"                  → 6 条   （"5888.0000" > "5888"）
                //         lte "5888"                  → 0 条   （"5888.0000" > "5888"，一条都不 ≤）
                //         gte "5888" + lte "6288"     → 3 条   （"6288.0000" > "6288"，被上界挡掉）
                //
                //     后果：价格位数不一致时彻底错乱（"10000.0000" < "5888.0000"）。
                //     修法：给 skuPrice 加数值子字段（"skuPriceNum": {"type":"double"}），
                //           range 和 sort 都改用它；keyword 那个保留给精确匹配/展示。
                //           改完记得把这里的期望值改回「正常人的预期」，见 document/ES.md「迁移收尾」。
                Arguments.of("12 价格：区间 5888_6288（⚠️ keyword 字典序，实际只 3 条）",
                        sp(p -> p.setSkuPrice("5888_6288")), 3L),
                Arguments.of("13 价格：只给下界 5888_（6 条，碰巧正确）",
                        sp(p -> p.setSkuPrice("5888_")), 6L),
                Arguments.of("14 价格：只给上界 _5888（⚠️ 实际 0 条）",
                        sp(p -> p.setSkuPrice("_5888")), 0L),

                // ---------- 属性（filter + nested） ----------
                Arguments.of("15 属性：5_青金（全部命中）",
                        sp(p -> p.setAttrs(List.of("5_青金"))), 6L),
                Arguments.of("16 属性：值不存在（0 条）",
                        sp(p -> p.setAttrs(List.of("5_钛银色"))), 0L),

                // ---------- 组合条件 ----------
                // 单看：华为=6 条、有货=3 条(5/7/10)、价格≤5888=0 条（见上面 14），交集自然为 0
                Arguments.of("17 组合：华为 + 有货 + 价格≤5888（⚠️ 价格那条是 0 条，所以整体 0）",
                        sp(p -> {
                            p.setKeyword("华为");
                            p.setHasStock(1);
                            p.setSkuPrice("_5888");
                        }), 0L)
        );
    }

    /**
     * 逐个用例跑 {@link MallSearchService#search}，校验总数、本页条数、聚合与高亮。
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("param")
    void search(String name, SearchParam searchParam, long expectedTotal) {

        SearchResult result = mallSearchService.search(searchParam);

        // 打印明细，方便肉眼看结果对不对
        StringBuilder detail = new StringBuilder();
        for (SkuEsModel sku : result.getProduct()) {
            detail.append("\n         skuId=").append(sku.getSkuId())
                    .append(" | ").append(sku.getSkuTitle())
                    .append(" | ¥").append(sku.getSkuPrice())
                    .append(" | 有货=").append(sku.isHasStock());
        }
        System.out.printf("""

                        ============== %s ==============
                          参数      : %s
                          命中总数  : %d    当前页 %d/%d    本页 %d 条
                          品牌聚合  : %s
                          分类聚合  : %s
                          属性聚合  : %s
                          商品明细  : %s
                        """,
                name, searchParam, result.getTotal(), result.getPageNum(), result.getTotalPages(),
                result.getProduct().size(), result.getBrands(), result.getCatelogs(), result.getAttrs(),
                detail);

        // 1、命中总数
        assertThat(result.getTotal()).as("命中总数").isEqualTo(expectedTotal);

        // 2、本页条数：ES 的 from/size 只影响 hits，不影响 total
        //    （第 2 页时 from=20 > 总数 6，所以本页应为 0 条，但 total 依然是 6）
        int from = (result.getPageNum() - 1) * PAGE_SIZE;
        int expectedOnPage = Math.clamp(expectedTotal - from, 0, PAGE_SIZE);
        assertThat(result.getProduct()).as("本页商品数").hasSize(expectedOnPage);

        // 3、聚合：有命中就该有筛选项。
        //    注意聚合是在【整个查询结果集】上算的，不受 from/size 分页影响 ——
        //    所以第 2 页即使没有 hits，聚合依然有值
        if (expectedTotal > 0) {
            assertThat(result.getBrands()).as("品牌聚合").isNotEmpty();
            assertThat(result.getCatelogs()).as("分类聚合").isNotEmpty();
            assertThat(result.getAttrs()).as("属性聚合(nested)").isNotEmpty();
        }

        // 4、高亮：有关键词且有命中时，标题应被 pre_tags 包起来
        if (StringUtils.hasText(searchParam.getKeyword()) && expectedTotal > 0) {
            String title = result.getProduct().getFirst().getSkuTitle();
            assertThat(title).as("高亮后的标题").contains("<b style='color:red'>");

            // ⚠️ 闭合标签必须是 </b>，不能是 <b/> —— 回归防护，踩过一次：
            //    HTML 的自闭合语法对 <b> 这类非空元素无效，<b/> 会被浏览器当成"又开了一个 <b>"，
            //    而这个 <b> 永远不会被关闭，按 HTML5「活动格式化元素」规则会泄漏到后面所有兄弟节点，
            //    把下一张卡片的 <div> 也包进去 —— 于是 CSS 的 .rig_tab > div 子选择器失效，
            //    图片失去 205x210 限制被撑成原始尺寸，整张卡片还继承 color:red 全变红。
            assertThat(title).as("高亮闭合标签").contains("</b>").doesNotContain("<b/>");
        }
    }

    // GET users/_doc/1
    // DELETE users
    @Test
    void indexData() throws IOException {
        // 要保存的数据
        User user = new User();
        user.setUsername("张三");
        user.setAge(18);
        user.setGender("男");

        // 一行搞定：新客户端用 Lambda 式构建器，且自动把对象序列化成 JSON
        IndexResponse response = client.index(i -> i
                .index("users")     // 索引名
                .id("1")            // 文档 id
                .document(user)     // 文档内容，Jackson 自动序列化
        );

        System.out.println("result = " + response.result());
    }

    // 必须声明为 static：非静态内部类持有外部类引用，
    // Jackson 能序列化但无法反序列化（搜用 User.class 时会报 no default constructor）
    @Data
    static class User {
        private String username;
        private String gender;
        private Integer age;
    }

    /*
        PUT bank/_doc/99
        {"account_number":99,"balance":47159,"firstname":"Reyna","lastname":"Wallace","age":28,"gender":"M","address":"990 Mill Road","employer":"Rice","email":"reynawallace@rice.com","city":"Trona","state":"CA"}
        PUT bank/_doc/101
        {"account_number":101,"balance":15000,"firstname":"Ilene","lastname":"Vinson","age":35,"gender":"M","address":"210 Mill Street","employer":"Tersa","email":"ilenevinson@tersa.com","city":"Kirk","state":"MD"}
        DELETE bank
     */
    @Test
    void searchData() throws IOException {
        // 1、构造检索请求：查询 address 包含 mill 的，并做两个聚合
        //    直接在 search 的 Lambda 里链式写 DSL，与 Kibana 里写的 JSON 一一对应
        SearchResponse<Account> response = client.search(s -> s
                        .index("bank")
                        .query(q -> q.match(m -> m.field("address").query("mill")))
                        // 按照年龄的值分布进行聚合
                        .aggregations("ageAgg", a -> a.terms(t -> t.field("age").size(10)))
                        // 计算平均薪资
                        .aggregations("balanceAvg", a -> a.avg(avg -> avg.field("balance")))
                        // 计算总薪资
                        .aggregations("balanceSum", a -> a.sum(sum -> sum.field("balance"))),
                Account.class);   // 指定结果映射的实体类，新客户端自动反序列化

        // 2、获取所有查到的数据
        for (Hit<Account> hit : response.hits().hits()) {
            // hit.index() / hit.id() 可以拿基本属性
            System.out.println("account = " + hit.source());   // source() 直接就是对象
        }

        // 3、获取聚合信息
        //    新客户端的聚合结果是【强类型】的，访问器由【被聚合字段的类型】决定：
        //      keyword/text 字段 → .sterms()  key() 返回 FieldValue
        //      整数 字段        → .lterms()  key() 直接是 long
        //      小数 字段        → .dterms()  key() 直接是 double
        //    age 映射为 long，所以用 lterms()（用 sterms() 会抛 IllegalStateException）
        //    另外 key() 的类型随访问器变化，lterms 下没有 longValue() 方法
        var ageAgg = response.aggregations().get("ageAgg").lterms();
        for (var bucket : ageAgg.buckets().array()) {
            System.out.println("年龄为 " + bucket.key() + " 岁的人有 " + bucket.docCount() + " 人");
        }

        var balanceAvg = response.aggregations().get("balanceAvg").avg();
        System.out.println("平均薪资为：" + balanceAvg.value());
        SumAggregate balanceSum = response.aggregations().get("balanceSum").sum();
        System.out.println("总薪资为：" + balanceSum.value());
    }

    /*
        对应 Kibana 里的：
        GET bank/_search
        {
          "query": { "match_all": {} },
          "aggs": {
            "ageAgg": {
              "terms": { "field": "age", "size": 100 },
              "aggs": {
                "ageBalanceAvg": { "avg": { "field": "balance" } }
              }
            }
          },
          "size": 0
        }
     */
    @Test
    void searchSubAggData() throws IOException {
        SearchResponse<Account> response = client.search(s -> s
                        .index("bank")
                        .query(q -> q.matchAll(m -> m))
                        .size(0)                      // 不看文档明细，只要聚合结果
                        // 外层聚合：按年龄分组
                        .aggregations("ageAgg", a -> a
                                .terms(t -> t.field("age").size(100))
                                // 子聚合：写在 terms 构建器的【里面】，
                                // 表示"在每个分组内部再算"（对应 JSON 里 aggs 与 terms 并列）
                                .aggregations("ageBalanceAvg", sub -> sub
                                        .avg(avg -> avg.field("balance")))),
                Account.class);

        // 遍历每个年龄分组
        var ageAgg = response.aggregations().get("ageAgg").lterms();
        for (var bucket : ageAgg.buckets().array()) {
            System.out.println("年龄 " + bucket.key() + " 共 " + bucket.docCount() + " 人");

            // ⚠️ 子聚合的结果挂在【每个桶】上，不是挂在顶层 ——
            //    这是与普通聚合取值最大的区别（普通聚合走 response.aggregations().get(...)）
            var ageBalanceAvg = bucket.aggregations().get("ageBalanceAvg").avg();
            System.out.println("  该年龄平均薪资：" + ageBalanceAvg.value());
        }
    }

    @Data
    @ToString
    public static class Account {
        private int account_number;
        private int balance;
        private String firstname;
        private String lastname;
        private int age;
        private String gender;
        private String address;
        private String employer;
        private String email;
        private String city;
        private String state;
    }

}
