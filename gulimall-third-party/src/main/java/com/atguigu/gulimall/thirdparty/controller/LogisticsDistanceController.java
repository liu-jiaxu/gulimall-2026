package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.api.thirdparty.DistanceItem;
import com.atguigu.gulimall.api.thirdparty.LogisticsDistanceRequest;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.thirdparty.dto.BaiduGeocodingResponse;
import com.atguigu.gulimall.thirdparty.dto.BaiduRouteMatrixResponse;
import com.atguigu.gulimall.thirdparty.service.BaiduMapInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 物流距离计算 —— 百度地图调用出口
 *
 * <p>入参是<b>一个收货地址 + 每个商品各自的发货地址</b>，出参是
 * <b>每个商品的距离(公里) + 耗时(秒)</b> 列表。服务端串起百度的两个接口：
 * <pre>
 *   地址 ──地理编码──▶ 经纬度 ──批量算路──▶ 距离（米）/ 耗时（秒）──▶ 公里
 * </pre>
 *
 * <p><b>四个省调用的设计</b>（都直接关系 AK 配额）：
 * <ol>
 *   <li><b>发货地址去重</b>：一个订单的商品常常来自同几个仓，同一个地址只解析一次</li>
 *   <li><b>所有地理编码并发</b>：收货地址 1 次 + 各发货地址各 1 次，同时发出而不是排队等</li>
 *   <li><b>算路批量算</b>：用百度「多起点 × 单终点」的能力一次算一整批，
 *       而不是每个商品单独调一遍 —— N 个商品从 N 次请求降到 ⌈N/50⌉ 次</li>
 *   <li><b>超限自动拆批</b>：一批最多 50 条路线，地址更多时自动切成几批请求，结果再合并</li>
 * </ol>
 *
 * <p>⚠️ <b>这个接口不要直接暴露给前端</b> —— 没有鉴权、没有限流，而每次调用消耗
 * N+1 次地理编码 + ⌈N/50⌉ 次算路的 AK 配额。正确链路：业务模块算运费 → Feign → 本接口。
 *
 * <p>⚠️ <b>AK 必须先在控制台开通对应服务</b>（「地理编码」+「批量算路（驾车）」），
 * 否则百度一律返回 status=3（权限校验失败），表现为本接口稳定返回 30001，
 * 而代码本身没有任何问题。
 */
@RestController
@RequestMapping("/baidu/map")
@Slf4j
public class LogisticsDistanceController {

    /** 百度的返回格式参数 —— 地理编码默认回的是 XML，必须显式要 json */
    private static final String OUTPUT_JSON = "json";

    /** 距离换算：1 公里 = 1000 米 */
    private static final BigDecimal METERS_PER_KM = BigDecimal.valueOf(1000);

    /** 地理编码可信度低于此值就打告警（60 分对应的误差约 500 米，见百度文档） */
    private static final int MIN_CONFIDENCE = 60;

    /**
     * 百度批量算路一次最多算 50 条<b>路线</b>（上限是「起点数 × 终点数」，不是单边的地址个数）。
     * 我们这里是「多起点 × 1 终点」，所以一批最多 50 个发货地址，超了就拆批。
     */
    private static final int MAX_ORIGIN_PER_BATCH = 50;

    @Value("${baidu.map.ak}")
    private String ak;

    @Autowired
    private BaiduMapInterface baiduMapInterface;

    /**
     * 批量计算每个商品的运输距离和耗时
     *
     * @param request 收货地址 + 每个商品的候选发货仓
     * @return {@code R.ok().put("distances", List<DistanceItem>)} —— 每个 sku 的每个候选仓一项
     */
    @PostMapping("/distance")
    public Mono<R> distance(@RequestBody LogisticsDistanceRequest request) {

        // ⚠️ 整条链必须包在 Mono.defer 里：下面那些方法在【组装阶段】是同步执行的，
        //    若直接写在 defer 外面，request 为 null（调用方发了个空 body）时抛出的 NPE
        //    会在 return 之前就炸掉 —— onErrorResume 根本来不及挂上，调用方收到的是 500。
        return Mono.defer(() -> {

            List<LogisticsDistanceRequest.SkuOrigin> skuOrigins = request == null ? null : request.skuOrigins();
            if (request == null || !StringUtils.hasText(request.destination())
                    || skuOrigins == null || skuOrigins.isEmpty()) {
                log.warn("距离计算入参不完整：{}", request);
                return Mono.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
            }

            String destination = cleanAddress(request.destination());

            boolean hasBlankAddress = skuOrigins.stream()
                    .anyMatch(origin -> origin == null || !StringUtils.hasText(cleanAddress(origin.address())));
            if (hasBlankAddress) {
                log.warn("有商品没给发货地址：{}", skuOrigins);
                return Mono.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
            }

            // 发货地址去重：同一个仓的多个商品只解析一次。
            // 用 LinkedHashSet 是为了保持首次出现的顺序 —— 拆批、摊回 sku 都依赖这个顺序
            Set<String> origins = skuOrigins.stream()
                    .map(origin -> cleanAddress(origin.address()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // 超过一批上限就拆批：宁可多发几次请求，也不要把整单拒掉。
            // 上限是「起点数 × 终点数」≤ 50，跟单边地址总数无关（我们终点固定 1 个）
            List<List<String>> batches = partition(new ArrayList<>(origins), MAX_ORIGIN_PER_BATCH);
            if (batches.size() > 1) {
                log.info("发货地址 {} 个超过一批上限 {}，拆成 {} 批请求百度",
                        origins.size(), MAX_ORIGIN_PER_BATCH, batches.size());
            }

            // 收货地址解析 1 次 + 各发货地址各 1 次，全部并发发出（别写成串行一个个等）
            Mono<BaiduGeocodingResponse.Result> destinationGeo = geocode(destination, "收货地址");

            Mono<Map<String, BaiduGeocodingResponse.Result>> originsGeo =
                    Flux.fromIterable(origins)
                            .flatMap(origin -> geocode(origin, "发货地址")
                                    .map(result -> Map.entry(origin, result)))
                            .collectMap(Map.Entry::getKey, Map.Entry::getValue);

            return Mono.zip(destinationGeo, originsGeo)
                    .flatMap(tuple -> {
                        String destinationLatLng = toLatLng(tuple.getT1());
                        Map<String, BaiduGeocodingResponse.Result> originGeo = tuple.getT2();

                        // 每批算一次路（几批之间是并发的），最后把各批结果合并成「发货地址 → 结果」一份
                        return Flux.fromIterable(batches)
                                .flatMap(batch -> driving(batch, originGeo, destinationLatLng))
                                .collectList()
                                .map(this::mergeBatchResults)
                                .mapNotNull(itemByOrigin -> R.ok().put("distances",
                                        toSkuDistances(itemByOrigin, skuOrigins, destination)));
                    });
        })
        // 任何环节出错（地址解析不出、百度报 status!=0、网络超时）都统一兜成业务错误码，
        // 不把百度的原始报文和堆栈暴露给调用方
        .onErrorResume(e -> {
            log.error("百度地图距离计算失败", e);
            return Mono.just(R.error(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getCode(),
                    BizCodeEnum.MAP_DISTANCE_EXCEPTION.getMsg()));
        })
        // 双保险：万一上游是【空流】（既没数据也没报错），这里也要兜住 ——
        // 否则 Spring MVC 会返回 200 + 空 body，调用方反序列化出 null
        .switchIfEmpty(Mono.just(R.error(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getCode(),
                BizCodeEnum.MAP_DISTANCE_EXCEPTION.getMsg())));
    }

    /**
     * 一批发货地址算一次路
     *
     * @param batch             这一批的发货地址（≤ {@link #MAX_ORIGIN_PER_BATCH} 个）
     * @param originGeo         发货地址 → 坐标（前面已经解析好）
     * @param destinationLatLng 收货地坐标
     * @return 发货地址 → 百度返回的那条结果（含距离和耗时）
     */
    private Mono<Map<String, BaiduRouteMatrixResponse.Item>> driving(
            List<String> batch,
            Map<String, BaiduGeocodingResponse.Result> originGeo,
            String destinationLatLng) {

        // 多起点 × 1 终点：起点之间用 | 分隔（WebClient 负责 URL 编码，不用自己拼 %7C）
        String originsParam = batch.stream()
                .map(origin -> toLatLng(originGeo.get(origin)))
                .collect(Collectors.joining("|"));

        return baiduMapInterface.drivingDistance(originsParam, destinationLatLng, OUTPUT_JSON, ak)
                .map(response -> {
                    if (response == null || response.status() == null || response.status() != 0
                            || response.result() == null || response.result().size() < batch.size()) {
                        log.error("百度算路返回异常：status={}，message={}，期望 {} 条结果，实际 {}",
                                response == null ? null : response.status(),
                                response == null ? null : response.message(),
                                batch.size(),
                                (response == null || response.result() == null) ? 0 : response.result().size());
                        throw new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION);
                    }

                    // ⚠️ 百度返回的结果顺序与传入的 origins 一一对应，所以按下标摊回地址。
                    //    这条「顺序一致」是官方文档没写明的地方，靠实测确认
                    //    （mock 测不出这一点，见 LogisticsDistanceControllerTest 的说明）
                    Map<String, BaiduRouteMatrixResponse.Item> itemByOrigin = new LinkedHashMap<>();
                    for (int i = 0; i < batch.size(); i++) {
                        itemByOrigin.put(batch.get(i), response.result().get(i));
                    }
                    return itemByOrigin;
                });
    }

    /** 多批的结果合成一份（各批的地址互不重复，直接合就行） */
    private Map<String, BaiduRouteMatrixResponse.Item> mergeBatchResults(
            List<Map<String, BaiduRouteMatrixResponse.Item>> batchResults) {

        Map<String, BaiduRouteMatrixResponse.Item> merged = new LinkedHashMap<>();
        batchResults.forEach(merged::putAll);
        return merged;
    }

    /**
     * 把「发货地址 → 结果」摊回到每个 sku 的每个候选仓，顺带做单位换算
     *
     * <p>同一个仓发出的多个商品共用同一条算路结果，所以两边不是一一对应的关系；
     * 一个 sku 有多个候选仓时，这里会为它生成多条结果。
     */
    private List<DistanceItem> toSkuDistances(Map<String, BaiduRouteMatrixResponse.Item> itemByOrigin,
                                              List<LogisticsDistanceRequest.SkuOrigin> skuOrigins,
                                              String destination) {

        List<DistanceItem> distances = new ArrayList<>();

        for (LogisticsDistanceRequest.SkuOrigin skuOrigin : skuOrigins) {
            String origin = cleanAddress(skuOrigin.address());
            BaiduRouteMatrixResponse.Item item = itemByOrigin.get(origin);
            BaiduRouteMatrixResponse.Distance distance = (item == null) ? null : item.distance();

            // ⚠️ value = 0 表示「没算出路」，不是「距离为零」，必须当失败处理
            if (distance == null || distance.value() == null || distance.value() <= 0) {
                log.error("百度算路未返回有效距离：发货地址={}，distance={}", origin, distance);
                throw new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION);
            }

            // 米 → 公里，保留两位小数（HALF_UP = 四舍五入，不是银行家舍入）
            BigDecimal km = BigDecimal.valueOf(distance.value())
                    .divide(METERS_PER_KM, 2, RoundingMode.HALF_UP);

            // 耗时百度给的就是秒，原样透传；偶尔缺这个字段，缺了给 null，不编个 0 出来骗调用方
            Long duration = (item.duration() == null || item.duration().value() == null)
                    ? null
                    : item.duration().value().longValue();

            distances.add(new DistanceItem(skuOrigin.skuId(), skuOrigin.wareId(),
                    origin, destination, km, duration));
        }

        return distances;
    }

    /**
     * 逆地理编码：浏览器定位拿到经纬度后，查当前省/市/区。
     * 前端统一访问同源的 /location/reverse，由 Nginx 转发到这里。
     */
    @GetMapping("/reverse")
    public Mono<R> reverse(@RequestParam("lat") String lat,
                           @RequestParam("lng") String lng) {
        String location = lat + "," + lng;
        // 浏览器 navigator.geolocation 返回 WGS84 坐标，必须告诉百度按 wgs84ll 解析
        return baiduMapInterface.reverseGeocoding(location, OUTPUT_JSON, "wgs84ll", ak)
                .switchIfEmpty(Mono.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION)))
                .<R>handle((response, sink) -> {
                    if (response == null || response.status() == null
                            || response.status() != 0 || response.result() == null
                            || response.result().addressComponent() == null) {
                        sink.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
                        return;
                    }
                    var address = response.result().addressComponent();
                    String city = StringUtils.hasText(address.city())
                            ? address.city() : address.province();
                    sink.next(R.ok()
                            .put("province", address.province())
                            .put("city", city)
                            .put("district", address.district()));
                })
                .onErrorResume(e -> {
                    log.warn("百度逆地理编码失败：lat={}, lng={}", lat, lng, e);
                    return Mono.just(R.error(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getMsg()));
                });
    }

    /**
     * 地理编码：地址 → 经纬度。
     *
     * <p>给 gulimall-agent 的地理位置工具用；只允许服务端内部调用。
     */
    @GetMapping("/geocode")
    public Mono<R> geocode(@RequestParam("address") String address) {
        // 1. 调百度地理编码（地址解析不出来时 result 为 null）
        return baiduMapInterface.geocoding(address, OUTPUT_JSON, ak)
                .switchIfEmpty(Mono.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION)))
                .<R>handle((response, sink) -> {
                    // 2. status != 0 或 result/location 为空都算失败
                    if (response == null || response.status() == null || response.status() != 0
                            || response.result() == null || response.result().location() == null) {
                        sink.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
                        return;
                    }
                    var location = response.result().location();
                    sink.next(R.ok()
                            .put("address", address)
                            .put("lng", location.lng())
                            .put("lat", location.lat())
                            .put("confidence", response.result().confidence())
                            .put("level", response.result().level()));
                })
                .onErrorResume(e -> {
                    // 3. 百度失败时返回统一错误，不把 AK 等信息带出去
                    log.warn("百度地理编码失败：address={}", address, e);
                    return Mono.just(R.error(BizCodeEnum.MAP_DISTANCE_EXCEPTION.getMsg()));
                });
    }

    /** 按固定大小把一个列表切成多批 */
    private static <T> List<List<T>> partition(List<T> source, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            batches.add(source.subList(i, Math.min(i + size, source.size())));
        }
        return batches;
    }

    /**
     * 地址 → 经纬度。失败直接抛 RRException，由 {@link #distance} 的 onErrorResume 统一兜底
     *
     * @param label 只用于日志区分是收货地址还是哪个发货地址出的问题
     */
    private Mono<BaiduGeocodingResponse.Result> geocode(String rawAddress, String label) {
        String address = cleanAddress(rawAddress);
        if (!StringUtils.hasText(address)) {
            log.warn("地址为空（{}）：{}", label, rawAddress);
            return Mono.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
        }

        return baiduMapInterface.geocoding(address, OUTPUT_JSON, ak)
                // ⚠️ 响应体为空时 Mono 是 empty 而【不是】error：上面 zip 只要有一个空源就整体变空，
                //    onErrorResume 也兜不住（空流不算异常）。这里主动转成错误
                .switchIfEmpty(Mono.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION)))
                .handle((response, sink) -> {
                    // 百度成功与否看 status，不看 HTTP 状态码（它恒为 200）
                    if (response == null || response.status() == null || response.status() != 0
                            || response.result() == null) {
                        log.error("百度地理编码失败（{}）：address={}，status={}，message={}",
                                label, address, response == null ? null : response.status(),
                                response == null ? null : response.message());
                        sink.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
                        return;
                    }

                    BaiduGeocodingResponse.Result result = response.result();
                    if (result.location() == null || result.location().lat() == null
                            || result.location().lng() == null) {
                        log.error("百度地理编码未返回坐标（{}）：address={}", label, address);
                        sink.error(new RRException(BizCodeEnum.MAP_DISTANCE_EXCEPTION));
                        return;
                    }

                    // 地址写得不够全时，百度照样返回 status=0，只是 precise=0、confidence 很低，
                    // 打点可能偏出去好几公里 —— 距离照样算得出来，但没人知道它是错的。
                    // 这里不报错（偏远地址本来就可能不精确），只留个痕，方便事后追查
                    if (result.confidence() == null || result.confidence() < MIN_CONFIDENCE) {
                        log.warn("地址解析可信度偏低（{}）：address={}，confidence={}，precise={}，命中级别={}",
                                label, address, result.confidence(), result.precise(), result.level());
                    }

                    sink.next(result);
                });
    }

    /**
     * 清洗调用方传来的地址
     *
     * <p>百度要的是"标准结构化地址"，官方示例里中间不带任何多余符号。调用方习惯写成
     * 「山东省-潍坊市-青州市-青州古城」这种带分隔符的样子 —— 这种写法百度<b>也能解析</b>，
     * 但符号会掺进分词、拉低命中精度，所以统一先清掉再发过去。
     *
     * <p>注意 {@code /} 不在清理范围内：百度支持「XX路与XX路交叉口」这类描述性地址，
     * 斜杠有可能是有效信息。
     */
    private String cleanAddress(String address) {
        if (address == null) {
            return "";
        }
        // 空白、- — － 、· 都清掉；这些都是人工拼接地址时的常见分隔符，不是地址本身的一部分
        return address.replaceAll("[\\s\\-—－、·]+", "");
    }

    /**
     * 地理编码结果 → 算路接口要的坐标串
     *
     * <p>⚠️ <b>这里是整个流程最容易写反的地方</b>：百度算路接口要的顺序是「<b>纬度,经度</b>」，
     * 而地理编码返回的字段是 lng（经度）在前、lat（纬度）在后 —— 顺序颠倒了百度<b>不会报错</b>
     * （中国的经度和纬度都 ≤ 90，它照样当成合法坐标算），只会给你一个完全不相干的距离。
     */
    private String toLatLng(BaiduGeocodingResponse.Result result) {
        return result.location().lat() + "," + result.location().lng();
    }

}
