package com.atguigu.gulimall.ware.service.impl;

import com.atguigu.gulimall.api.member.MemberReceiveAddressVo;
import com.atguigu.gulimall.api.thirdparty.DistanceItem;
import com.atguigu.gulimall.api.thirdparty.LogisticsDistanceRequest;
import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.FareResponseVo;
import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.ware.dao.WareInfoDao;
import com.atguigu.gulimall.ware.entity.WareInfoEntity;
import com.atguigu.gulimall.ware.feign.LogisticsDistanceFeignClient;
import com.atguigu.gulimall.ware.feign.MemberReceiveAddressFeignClient;
import com.atguigu.gulimall.ware.service.WareInfoService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service("wareInfoService")
public class WareInfoServiceImpl extends ServiceImpl<WareInfoDao, WareInfoEntity> implements WareInfoService {

    /** 选仓规则：耗时最短优先，没耗时按距离，再按地址/仓库兜底 */
    private static final Comparator<DistanceItem> BEST_DISTANCE = Comparator
            .comparing(DistanceItem::duration, Comparator.nullsLast(Long::compareTo))
            .thenComparing(DistanceItem::distance)
            .thenComparing(DistanceItem::originAddress)
            .thenComparing(DistanceItem::wareId, Comparator.nullsLast(Long::compareTo));

    @Autowired
    private MemberReceiveAddressFeignClient memberReceiveAddressFeignClient;
    @Autowired
    private LogisticsDistanceFeignClient logisticsDistanceFeignClient;
    @Autowired
    private WareInfoDao wareInfoDao;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<WareInfoEntity> page = this.page(
                new Query<WareInfoEntity>().getPage(params),
                new QueryWrapper<WareInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        QueryWrapper<WareInfoEntity> wrapper = new QueryWrapper<>();
        String key = (String) params.get("key");
        if (StringUtils.isNotEmpty(key)) {
            wrapper.eq("id", key)
                    .or().like("name", key)
                    .or().like("address", key)
                    .or().like("areacode", key);
        }

        IPage<WareInfoEntity> page = this.page(
                new Query<WareInfoEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    public List<SkuShippingFromVo> getShippingFrom(List<FareRequestVo.Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return wareInfoDao.getShippingFromList(items).stream()
                .filter(w -> w.getAvailableStock() != null && w.getAvailableStock() > 0)
                .toList();
    }

    @Override
    public FareResponseVo getFare(FareRequestVo fareRequestVo) {
        List<FareRequestVo.Item> items = fareRequestVo.items();
        if (items == null || items.isEmpty()) {
            return new FareResponseVo(List.of(), BigDecimal.ZERO);
        }

        // 同一个 sku 可能有多行，先合并数量
        Map<Long, Integer> countBySku = items.stream()
                .collect(Collectors.toMap(
                        FareRequestVo.Item::skuId,
                        item -> item.count() == null ? 1 : item.count(),
                        Integer::sum));

        // 候选仓：每个 sku 可能有多个仓库
        Map<Long, List<SkuShippingFromVo>> warehousesBySku = wareInfoDao.getShippingFromList(items)
                .stream()
                .filter(w -> w.getAvailableStock() != null
                        && w.getAvailableStock() >= countBySku.getOrDefault(w.getSkuId(), 1))
                .collect(Collectors.groupingBy(SkuShippingFromVo::getSkuId));

        // 用户收货信息
        MemberReceiveAddressVo address = memberReceiveAddressFeignClient.getAddressById(fareRequestVo.addrId());
        if (address == null) {
            throw new RRException(BizCodeEnum.WARE_ADDRESS_NOT_FOUND);
        }
        // 收货地址
        String shippingAddress = address.getProvince() + address.getCity()
                + address.getRegion() + address.getDetailAddress();

        // 一个 sku 的所有候选仓一起算距离
        List<LogisticsDistanceRequest.SkuOrigin> origins = warehousesBySku.values().stream()
                .flatMap(List::stream)
                .map(w -> new LogisticsDistanceRequest.SkuOrigin(w.getSkuId(), w.getWareId(), w.getAddress()))
                .toList();

        // 远程调用百度API查询运输信息
        R result = logisticsDistanceFeignClient.distance(new LogisticsDistanceRequest(shippingAddress, origins));
        if (result == null || !Integer.valueOf(0).equals(result.getCode())) {
            throw new RRException(BizCodeEnum.WARE_FARE_CALC_FAILED);
        }
        List<DistanceItem> distances = result.getData("distances", new TypeReference<>() {
        });
        if (distances == null || distances.isEmpty()) {
            throw new RRException(BizCodeEnum.WARE_FARE_CALC_FAILED);
        }

        // 每个 sku 只选一个仓
        Map<Long, DistanceItem> bestBySku = distances.stream()
                .collect(Collectors.toMap(
                        DistanceItem::skuId,
                        Function.identity(),
                        BinaryOperator.minBy(BEST_DISTANCE)));

        // 结果封装
        // 总运费
        BigDecimal[] totalFare = {BigDecimal.ZERO};
        // 商品运输明细
        List<FareResponseVo.FareItem> fareItems = items.stream()
                .map(item -> {
                    DistanceItem distance = bestBySku.get(item.skuId());
                    // 1.总运费
                    BigDecimal fare = calculateFare(item, distance);
                    totalFare[0] = totalFare[0].add(fare);
                    // 2.运输时间消息
                    String timeInfo = formatTimeInfo(distance.duration());
                    return new FareResponseVo.FareItem(
                            item.skuId(),
                            distance.wareId(),
                            distance.originAddress(),
                            distance.distance(),
                            fare,
                            timeInfo);
                })
                .toList();

        return new FareResponseVo(fareItems, totalFare[0]);
    }

    private BigDecimal calculateFare(FareRequestVo.Item item, DistanceItem distance) {
        /*
            运费计算公式：
                1.总重量<=3kg时0.5元，后续每超过0.5kg额外支付0.05元，封顶20元
                2.运输路程<=10公里时免运费，后续每增加5公里额外支付0.01元，封顶3元
         */
        int count = item.count() == null ? 1 : item.count();
        BigDecimal weight = item.weight() == null ? BigDecimal.ZERO : item.weight();
        BigDecimal totalWeight = BigDecimal.valueOf(count).multiply(weight);

        // 重量费
        BigDecimal weightFare = BigDecimal.valueOf(0.5);
        if (totalWeight.compareTo(BigDecimal.valueOf(3)) > 0) {
            BigDecimal extra = totalWeight.subtract(BigDecimal.valueOf(3))
                    .divide(BigDecimal.valueOf(0.5), 0, RoundingMode.CEILING)
                    .multiply(BigDecimal.valueOf(0.05));
            // 封顶要包含基础运费0.5元
            weightFare = weightFare.add(extra).min(BigDecimal.valueOf(20));
        }

        // 距离费
        BigDecimal distanceFare = BigDecimal.ZERO;
        if (distance.distance().compareTo(BigDecimal.TEN) > 0) {
            distanceFare = distance.distance().subtract(BigDecimal.valueOf(5))
                    .divide(BigDecimal.valueOf(5), 0, RoundingMode.CEILING)
                    .multiply(BigDecimal.valueOf(0.01))
                    .min(BigDecimal.valueOf(3));
        }
        return weightFare.add(distanceFare);
    }

    private String formatTimeInfo(Long durationSeconds) {
        if (durationSeconds == null) {
            return "预计运输时间未知";
        }
        // 2.运输时间消息
        LocalDateTime arrival = LocalDateTime.now().plusSeconds(durationSeconds)
                // 加上拣货出库确认时间（实际应该等商品真正运输再计算，这里模拟加上）
                .plusHours(8);
        LocalDateTime start = arrival.minusHours(2);
        LocalDateTime end = start.plusHours(2);
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        // 前端th不方便处理LocalDateTime，这里直接返回String
        if (start.toLocalDate().equals(end.toLocalDate())) {
            // 同一天：2026年12月16日 15:20-19:40
            return start.format(dateFmt) + " " + start.format(timeFmt) + "-" + end.format(timeFmt);
        }
        // 跨天：2026年12月16日 15:20-次日 19:40
        return start.format(dateFmt) + " " + start.format(timeFmt) + "-次日 " + end.format(timeFmt);
    }

}
