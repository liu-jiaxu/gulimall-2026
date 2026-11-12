package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.api.thirdparty.DistanceItem;
import com.atguigu.gulimall.api.thirdparty.LogisticsDistanceRequest;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.thirdparty.dto.BaiduGeocodingResponse;
import com.atguigu.gulimall.thirdparty.dto.BaiduRouteMatrixResponse;
import com.atguigu.gulimall.thirdparty.service.BaiduMapInterface;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 距离计算接口的测试
 *
 * <p><b>两类测试，职责完全不同：</b>
 * <ol>
 *   <li><b>mock 测试</b>（前 5 个）：把百度接口顶掉，专测我们自己的逻辑 ——
 *       地址去重、超限拆批、单位换算、结果摊回 skuId、异常兜底。跑得快、不花配额，每次构建都跑</li>
 *   <li><b>真实调用</b>（{@link #realCallAgainstBaidu}）：不 mock，直接打百度。
 *       它要回答的是 mock 回答不了的那个问题：「百度返回的 result 顺序，到底是不是我们传入 origins 的顺序」</li>
 * </ol>
 *
 * <p><b>造数据的思路（mock 部分）：</b>地址里带「仓A」→ 假纬度 41、带「仓B」→ 42、其余（收货地址）→ 40；
 * 算路侧再按纬度换回来：41 → 1000 米 / 600 秒，42 → 2000 米 / 1200 秒。每条结果都能一眼看出对不对。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogisticsDistanceControllerTest {

    private static final String DESTINATION = "北京市朝阳区颐堤港购物中心";

    private static final String WAREHOUSE_A = "北京市仓A";
    private static final String WAREHOUSE_B = "北京市仓B";

    @Mock
    private BaiduMapInterface baiduMapInterface;

    @InjectMocks
    private LogisticsDistanceController controller;

    @BeforeEach
    void setUp() {
        // @Value 注入的字段在单元测试里没人帮你填，手工塞一个假 AK
        ReflectionTestUtils.setField(controller, "ak", "fake-ak");

        // 地理编码：按地址给出假坐标
        when(baiduMapInterface.geocoding(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Mono.just(geocodingResponse(invocation.<String>getArgument(0))));

        // 批量算路：按传进来的 origins 串顺序，造出同样条数的结果
        when(baiduMapInterface.drivingDistance(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Mono.just(routeMatrixResponse(invocation.<String>getArgument(0))));
    }

    // ==================== 一、mock 测试：我们的逻辑 ====================

    @Test
    @DisplayName("同一个发货地址只解析一次，并摊到所有用它的 sku 上")
    void sameOriginSharedBySkus() {
        Map<Long, String> skuOrigins = new LinkedHashMap<>();
        skuOrigins.put(1L, WAREHOUSE_A);
        skuOrigins.put(2L, WAREHOUSE_A);
        skuOrigins.put(3L, WAREHOUSE_A);

        List<DistanceItem> distances = callAndGetDistances(new LogisticsDistanceRequest(DESTINATION, skuOrigins));

        assertThat(distances).hasSize(3);
        assertThat(distances).allSatisfy(item -> {
            assertThat(item.distance()).isEqualByComparingTo("1.00");
            assertThat(item.duration()).isEqualTo(600L);
            assertThat(item.originAddress()).isEqualTo(WAREHOUSE_A);
            assertThat(item.destinationAddress()).isEqualTo(DESTINATION);
        });

        // 收货地址 1 次 + 发货地址 1 次（3 个 sku 去重成 1 个）= 2 次地理编码
        verify(baiduMapInterface, times(2)).geocoding(anyString(), anyString(), anyString());
        // 一趟算路就够
        verify(baiduMapInterface, times(1)).drivingDistance(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("多个不同发货地址按顺序对应到各自的 sku（最容易错的一步）")
    void eachSkuGetsItsOwnOrigin() {
        Map<Long, String> skuOrigins = new LinkedHashMap<>();
        skuOrigins.put(11L, WAREHOUSE_A);   // 纬度 41 → 1.00 公里 / 600 秒
        skuOrigins.put(22L, WAREHOUSE_B);   // 纬度 42 → 2.00 公里 / 1200 秒

        List<DistanceItem> distances = callAndGetDistances(new LogisticsDistanceRequest(DESTINATION, skuOrigins));

        assertThat(distances).hasSize(2);

        DistanceItem first = itemOf(distances, 11L);
        assertThat(first.distance()).isEqualByComparingTo("1.00");
        assertThat(first.duration()).isEqualTo(600L);
        assertThat(first.originAddress()).isEqualTo(WAREHOUSE_A);

        DistanceItem second = itemOf(distances, 22L);
        assertThat(second.distance()).isEqualByComparingTo("2.00");
        assertThat(second.duration()).isEqualTo(1200L);
        assertThat(second.originAddress()).isEqualTo(WAREHOUSE_B);
    }

    @Test
    @DisplayName("发货地址超过 50 个自动拆批：60 个拆成 50 + 10，算路调 2 次")
    void partitionsWhenOriginsExceedLimit() {
        Map<Long, String> skuOrigins = new LinkedHashMap<>();
        for (long skuId = 1; skuId <= 60; skuId++) {
            // 60 个互不相同的发货地址，绕开去重
            skuOrigins.put(skuId, WAREHOUSE_A + skuId);
        }

        List<DistanceItem> distances = callAndGetDistances(new LogisticsDistanceRequest(DESTINATION, skuOrigins));

        assertThat(distances).hasSize(60);

        // 抓住两次请求的 origins 参数，确认是「50 + 10」这么切的
        ArgumentCaptor<String> originsCaptor = ArgumentCaptor.forClass(String.class);
        verify(baiduMapInterface, times(2))
                .drivingDistance(originsCaptor.capture(), anyString(), anyString(), anyString());

        List<String> sentBatches = originsCaptor.getAllValues();
        assertThat(sentBatches.get(0).split("\\|")).hasSize(50);
        assertThat(sentBatches.get(1).split("\\|")).hasSize(10);
    }

    @Test
    @DisplayName("入参不完整 → 返回业务错误码 30001")
    void invalidRequestReturnsBizError() {
        // 收货地址为空
        assertThat(codeOf(controller.distance(new LogisticsDistanceRequest("", Map.of(1L, WAREHOUSE_A))).block()))
                .isEqualTo(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getCode());

        // 某个商品没有发货地址（清洗后为空串）
        assertThat(codeOf(controller.distance(new LogisticsDistanceRequest(DESTINATION, Map.of(1L, "   "))).block()))
                .isEqualTo(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getCode());

        // 商品清单整体为空
        assertThat(codeOf(controller.distance(new LogisticsDistanceRequest(DESTINATION, Map.of())).block()))
                .isEqualTo(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getCode());
    }

    @Test
    @DisplayName("百度返回 status != 0 → 返回业务错误码 30001，不透出百度原始报文")
    void baiduFailureReturnsBizError() {
        // ⚠️ 覆盖已有 stub 必须用 doReturn().when()，不能写 when().thenReturn()：
        //    when(...) 括号里那次 mock 调用会先匹配上 @BeforeEach 注册好的 answer 并【真的执行它】，
        //    而此时参数是 anyString() 的占位值（空串），于是 mock 内部先炸了，根本走不到断言。
        //    doReturn().when() 不走那条路，直接替换 stub，不会触发已有 answer
        doReturn(Mono.just(new BaiduRouteMatrixResponse(3, List.of(), "权限校验失败")))
                .when(baiduMapInterface)
                .drivingDistance(anyString(), anyString(), anyString(), anyString());

        R result = controller.distance(new LogisticsDistanceRequest(DESTINATION, Map.of(1L, WAREHOUSE_A))).block();

        assertThat(codeOf(result)).isEqualTo(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getCode());
    }

    // ==================== 二、真实调用（不 mock，会花配额） ====================

    /**
     * 真实调用百度接口，验证 mock 验证不了的那条前提：<b>返回结果的顺序 = 传入 origins 的顺序</b>。
     *
     * <p>特意挑了一近一远两个仓发往同一个收货地：
     * <pre>
     *   1 号商品 ← 北京市大兴区魏永路（谷粒亚洲一号北京仓）      → 同城，几十公里
     *   2 号商品 ← 上海市嘉定区兴邦路1111号（谷粒亚洲一号上海仓） → 跨省，一千多公里
     *   3 号商品 ← 和 1 号同仓                                   → 验证去重后两个 sku 结果一致
     * </pre>
     * 顺序要真错了，1 号和 2 号的距离就会对调，断言当场就挂 —— 这正是要盯的点。
     *
     * <p><b>默认不参与构建</b>：它要出外网、消耗 AK 配额，而且受本机网络状况影响 ——
     * 常规 {@code mvn test} 不该被这些拖累。想跑就显式带上 {@code -DrealCall=true}：
     * <pre>
     *   mvn -o -f gulimall-third-party/pom.xml test -DrealCall=true -Dtest=LogisticsDistanceControllerTest
     * </pre>
     * AK 会依次从 {@code -Dbaidu.map.ak} → 环境变量 {@code BAIDU_MAP_AK} → {@code config/common.yaml} 里找，
     * 都找不到就自动跳过（不会报错）。
     *
     * <p>⚠️ 前提：控制台给这个 AK 开通了「地理编码」+「批量算路（驾车）」，否则会稳定返回 30001。
     */
    @Test
    @EnabledIfSystemProperty(named = "realCall", matches = "true")
    @DisplayName("真实调用百度（不 mock）—— 默认不跑，加 -DrealCall=true 才执行")
    void realCallAgainstBaidu() {
        String ak = resolveAk();
        assumeTrue(StringUtils.hasText(ak),
                "没找到 AK（-Dbaidu.map.ak / 环境变量 BAIDU_MAP_AK / config/common.yaml 都为空），跳过真实调用");

        // 手工搭一个真实的 controller + 真实的 BaiduMapInterface。
        // 不启动 Spring 容器 —— 为了调个百度把 Nacos、数据源那些也拖起来不划算
        LogisticsDistanceController realController = new LogisticsDistanceController();
        ReflectionTestUtils.setField(realController, "ak", ak);
        ReflectionTestUtils.setField(realController, "baiduMapInterface", realBaiduMapInterface());

        Map<Long, String> skuOrigins = new LinkedHashMap<>();
        skuOrigins.put(1L, "北京市大兴区魏永路");
        skuOrigins.put(2L, "上海市嘉定区兴邦路1111号");
        skuOrigins.put(3L, "北京市大兴区魏永路");

        R result = realController.distance(
                new LogisticsDistanceRequest("北京市朝阳区颐堤港购物中心", skuOrigins)).block();

        System.out.println("==== 真实调用返回 ==== " + result);

        List<DistanceItem> distances = distancesOf(result);
        assertThat(distances).hasSize(3);

        DistanceItem beijing = itemOf(distances, 1L);
        DistanceItem shanghai = itemOf(distances, 2L);
        DistanceItem sameWarehouse = itemOf(distances, 3L);

        // 距离必须是正数，且耗时拿得到
        assertThat(beijing.distance()).isGreaterThan(BigDecimal.ZERO);
        assertThat(beijing.duration()).isNotNull();

        // ⭐ 核心断言：北京仓离收货地(北京)近，上海仓远。
        //    两个距离一旦对调，就说明百度返回的顺序不是我们假设的顺序
        assertThat(beijing.distance()).isLessThan(shanghai.distance());
        // 上海 → 北京，必然上千公里
        assertThat(shanghai.distance()).isGreaterThan(new BigDecimal("1000"));

        // 同仓的两个 sku（1 和 3）结果必须完全一致
        assertThat(sameWarehouse.distance()).isEqualByComparingTo(beijing.distance());
        assertThat(sameWarehouse.duration()).isEqualTo(beijing.duration());

        System.out.printf("北京仓 → 收货地：%s 公里，约 %s 秒%n", beijing.distance(), beijing.duration());
        System.out.printf("上海仓 → 收货地：%s 公里，约 %s 秒%n", shanghai.distance(), shanghai.duration());
    }

    // ==================== 辅助 ====================

    private List<DistanceItem> callAndGetDistances(LogisticsDistanceRequest request) {
        return distancesOf(controller.distance(request).block());
    }

    @SuppressWarnings("unchecked")
    private static List<DistanceItem> distancesOf(R result) {
        assertThat(codeOf(result)).isEqualTo(0);
        return (List<DistanceItem>) result.get("distances");
    }

    private static DistanceItem itemOf(List<DistanceItem> distances, Long skuId) {
        return distances.stream()
                .filter(item -> skuId.equals(item.skuId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有 skuId=" + skuId + " 的项"));
    }

    private static int codeOf(R result) {
        assertThat(result).isNotNull();
        return (Integer) result.get("code");
    }

    /**
     * AK 三级兜底：系统属性 → 环境变量 → 项目根的 config/common.yaml（那儿本来就是放密钥的地方）
     */
    private static String resolveAk() {
        String ak = System.getProperty("baidu.map.ak");
        if (StringUtils.hasText(ak)) {
            return ak;
        }

        ak = System.getenv("BAIDU_MAP_AK");
        if (StringUtils.hasText(ak)) {
            return ak;
        }

        Path config = Path.of("..", "config", "common.yaml");
        if (!Files.exists(config)) {
            return null;
        }

        try {
            String fromConfig = Files.readAllLines(config, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("BAIDU_MAP_AK:"))
                    .map(line -> line.substring("BAIDU_MAP_AK:".length()).trim())
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);

            // 配置里还没填真 AK、放着「待填入我的AK」这类占位符时要当没配处理，
            // 否则这个测试会拿占位符去真调百度，最后以一句看不懂的业务错误码挂掉
            return isPlaceholder(fromConfig) ? null : fromConfig;
        } catch (IOException e) {
            return null;
        }
    }

    /** 含中文的一律当占位符 —— 百度 AK 是纯字母数字 */
    private static boolean isPlaceholder(String ak) {
        return StringUtils.hasText(ak) && !ak.matches("[A-Za-z0-9]+");
    }

    /** 搭一个真的 BaiduMapInterface（手工组装 WebClient + 声明式代理，参数与 BaiduMapConfiguration 保持一致） */
    private static BaiduMapInterface realBaiduMapInterface() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 百度返回里有我们没声明的字段（comprehension 等），必须关掉「遇到未知字段就报错」
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 和生产配置保持一致：百度地理编码回的 Content-Type 是 text/javascript，不放进解码器会解码失败
        Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(
                objectMapper,
                MediaType.APPLICATION_JSON,
                MediaType.TEXT_PLAIN,
                MediaType.valueOf("text/javascript"));

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.map.baidu.com")
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                })
                .build();

        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(BaiduMapInterface.class);
    }

    /** 假坐标：地址带「仓A」纬 41、带「仓B」纬 42、其余（收货地址）纬 40，经度统一 116 */
    private static BaiduGeocodingResponse geocodingResponse(String address) {
        double lat = address.contains("仓A") ? 41 : (address.contains("仓B") ? 42 : 40);
        return new BaiduGeocodingResponse(0,
                new BaiduGeocodingResponse.Result(
                        new BaiduGeocodingResponse.Location(BigDecimal.valueOf(116.0), BigDecimal.valueOf(lat)),
                        1, 90, "门址"),
                "成功");
    }

    /**
     * 把入参的 origins 串（「纬度,经度|纬度,经度」）按顺序翻译成结果：
     * 纬 41 → 1000 米/600 秒，42 → 2000 米/1200 秒
     *
     * <p>空串单独兜一下：Mockito 的 anyString() 占位值就是空串，
     * 覆盖 stub 时可能被喂进来，不兜的话会在这里报一个很难看懂的 NumberFormatException
     */
    private static BaiduRouteMatrixResponse routeMatrixResponse(String originsParam) {
        if (!StringUtils.hasText(originsParam)) {
            return new BaiduRouteMatrixResponse(0, List.of(), "成功");
        }

        List<BaiduRouteMatrixResponse.Item> items = Arrays.stream(originsParam.split("\\|"))
                .map(point -> {
                    int lat = (int) Double.parseDouble(point.split(",")[0]);
                    int meters = (lat - 40) * 1000;
                    int seconds = (lat - 40) * 600;
                    return new BaiduRouteMatrixResponse.Item(
                            new BaiduRouteMatrixResponse.Distance(meters + "米", meters),
                            new BaiduRouteMatrixResponse.Duration(seconds + "秒", seconds));
                })
                .toList();

        return new BaiduRouteMatrixResponse(0, items, "成功");
    }

}
