package com.atguigu.gulimall.seckill.service.impl;

import com.atguigu.gulimall.api.seckill.vo.SeckillHomeVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuInfoVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuVo;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.seckill.dao.SeckillSessionDao;
import com.atguigu.gulimall.seckill.dao.SeckillSkuRelationDao;
import com.atguigu.gulimall.seckill.entity.SeckillSessionEntity;
import com.atguigu.gulimall.seckill.entity.SeckillSkuRelationEntity;
import com.atguigu.gulimall.seckill.feign.ProductFeignClient;
import com.atguigu.gulimall.seckill.service.SeckillSkuService;
import com.atguigu.gulimall.seckill.vo.ProductSkuVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀商品上架与查询
 */
@Slf4j
@Service("seckillSkuService")
public class SeckillSkuServiceImpl implements SeckillSkuService {

    private static final long THREE_DAYS_MILLIS = 3L * 24 * 60 * 60 * 1000;
    private static final long ONE_DAY_MILLIS = 24L * 60 * 60 * 1000;

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Autowired
    private SeckillSkuRelationDao seckillSkuRelationDao;

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void uploadSeckillSkuLatest3Days() {
        // 1. 扫未来 3 天 + 进行中的启用场次
        Date now = new Date();
        Date future = new Date(now.getTime() + THREE_DAYS_MILLIS);
        List<SeckillSessionEntity> sessions = seckillSessionDao.selectList(
                new QueryWrapper<SeckillSessionEntity>()
                        .eq("status", 1)
                        .in("seckill_state", 0, 1)
                        .ge("end_time", now)
                        .le("start_time", future));

        int uploaded = 0;
        for (SeckillSessionEntity session : sessions) {
            // 2. 过期场次跳过
            if (session.getEndTime() == null || session.getStartTime() == null) {
                continue;
            }
            long ttlMillis = session.getEndTime().getTime() + ONE_DAY_MILLIS - System.currentTimeMillis();
            if (ttlMillis <= 0) {
                continue;
            }
            Duration ttl = Duration.ofMillis(ttlMillis);

            // 3. 查这场的关联商品
            List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                    new QueryWrapper<SeckillSkuRelationEntity>()
                            .eq("promotion_session_id", session.getId()));

            String infoKey = SeckillConstant.sessionSkusKey(session.getId());
            Map<String, String> skuJsonMap = new HashMap<>();
            for (SeckillSkuRelationEntity relation : relations) {
                // 4. 查商品名称/价格/图片
                ProductSkuVo sku = getProductSku(relation.getSkuId());
                if (sku == null) {
                    log.warn("[seckill-upload] sku 信息查不到，跳过 sessionId={} skuId={}",
                            session.getId(), relation.getSkuId());
                    continue;
                }

                // 5. 旧 hash 里的 randomCode 要沿用，避免每次上架把已发出的码换掉
                Object oldJson = stringRedisTemplate.opsForHash()
                        .get(infoKey, relation.getSkuId().toString());
                SeckillSkuVo vo = buildSkuVo(session, relation, sku,
                        oldJson == null ? null : String.valueOf(oldJson));

                try {
                    skuJsonMap.put(relation.getSkuId().toString(),
                            objectMapper.writeValueAsString(vo));
                } catch (Exception e) {
                    log.error("[seckill-upload] 秒杀商品 JSON 序列化失败 sessionId={} skuId={}",
                            session.getId(), relation.getSkuId(), e);
                    continue;
                }

                // 6. 库存 key 只补不覆盖：已有 key 说明活动已开始扣减
                int count = relation.getSeckillCount() == null ? 0 : relation.getSeckillCount();
                if (count > 0) {
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            SeckillConstant.stockKey(session.getId(), relation.getSkuId()),
                            String.valueOf(count), ttl);
                }
                uploaded++;
            }

            // 7. 整场商品信息写一个 hash，刷新过期时间
            if (!skuJsonMap.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(infoKey, skuJsonMap);
                stringRedisTemplate.expire(infoKey, ttl);
            }
        }

        log.info("[seckill-upload] 上架完成：场次={} 商品={}", sessions.size(), uploaded);
    }

    @Override
    public List<SeckillSessionEntity> listActiveSessions() {
        // 1. 和上架范围一致：未来 3 天 + 进行中
        Date now = new Date();
        Date future = new Date(now.getTime() + THREE_DAYS_MILLIS);
        return seckillSessionDao.selectList(
                new QueryWrapper<SeckillSessionEntity>()
                        .eq("status", 1)
                        .in("seckill_state", 0, 1)
                        .ge("end_time", now)
                        .le("start_time", future)
                        .orderByAsc("start_time"));
    }

    @Override
    public List<SeckillSkuVo> getSessionSkus(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        // 1. 从 hash 里读所有商品 JSON；Redis 异常或为空时回源数据库
        Map<Object, Object> entries;
        try {
            entries = stringRedisTemplate.opsForHash()
                    .entries(SeckillConstant.sessionSkusKey(sessionId));
        } catch (Exception e) {
            log.error("[ALERT][seckill-query] Redis 读取商品 hash 失败，回源数据库 sessionId={}",
                    sessionId, e);
            return loadSessionSkusFromDb(sessionId);
        }
        if (entries.isEmpty()) {
            log.error("[ALERT][seckill-query] 秒杀商品 hash 缺失，回源数据库 sessionId={}", sessionId);
            return loadSessionSkusFromDb(sessionId);
        }

        // 2. 反序列化并按排序字段排序
        List<SeckillSkuVo> result = new ArrayList<>();
        for (Object value : entries.values()) {
            try {
                result.add(objectMapper.readValue(String.valueOf(value), SeckillSkuVo.class));
            } catch (Exception e) {
                log.error("[seckill-query] 秒杀商品 JSON 解析失败 sessionId={} value={}",
                        sessionId, value, e);
            }
        }
        if (result.isEmpty()) {
            return loadSessionSkusFromDb(sessionId);
        }
        result.sort(Comparator.comparing(
                SeckillSkuVo::getSeckillSort, Comparator.nullsLast(Integer::compareTo)));
        return result;
    }

    /**
     * Redis 商品 hash 丢失时的降级：关联表 + product 拼出展示数据，并尽量把 hash 补回去。
     * 库存 key 不在降级里凭空创建，抢购仍以 Redis 库存为准。
     */
    private List<SeckillSkuVo> loadSessionSkusFromDb(Long sessionId) {
        // 1. 先查场次，场次不存在或时间不完整就算不出秒杀信息，直接返回空列表
        SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
        if (session == null || session.getStartTime() == null || session.getEndTime() == null) {
            return List.of();
        }
        // 2. 按排序列出这场秒杀配置的所有商品关联，没关联就没东西可展示
        List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .eq("promotion_session_id", sessionId)
                        .orderByAsc("seckill_sort"));
        if (relations.isEmpty()) {
            return List.of();
        }

        // 3. 逐个关联去商品服务补名称/图片/价格，拼成 VO 并顺手写回 Redis
        List<SeckillSkuVo> result = new ArrayList<>();
        for (SeckillSkuRelationEntity relation : relations) {
            ProductSkuVo product = getProductSku(relation.getSkuId());
            if (product == null) {
                continue;
            }
            SeckillSkuVo vo = buildSkuVo(session, relation, product, null);
            result.add(vo);
            cacheSku(session, relation, vo);
        }
        // 4. 按排序字段升序返回，没有排序值的排到最后
        result.sort(Comparator.comparing(
                SeckillSkuVo::getSeckillSort, Comparator.nullsLast(Integer::compareTo)));
        return result;
    }

    /**
     * 把单个秒杀商品写回 hash，TTL 和库存 key 保持一致（场次结束 + 1 天）
     */
    private void cacheSku(SeckillSessionEntity session, SeckillSkuRelationEntity relation,
                          SeckillSkuVo vo) {
        try {
            // 1. 没有结束时间就算不出 TTL，直接跳过不写缓存
            if (session.getEndTime() == null) {
                return;
            }
            // 2. 算出场次结束 + 1 天还剩多久，已经过期就不用再写
            long ttlMillis = session.getEndTime().getTime() + ONE_DAY_MILLIS
                    - System.currentTimeMillis();
            if (ttlMillis <= 0) {
                return;
            }
            // 3. 把商品 JSON 写进整场 hash，并把整个 hash 的过期时间刷成同样的 TTL
            String key = SeckillConstant.sessionSkusKey(session.getId());
            stringRedisTemplate.opsForHash().put(key, relation.getSkuId().toString(),
                    objectMapper.writeValueAsString(vo));
            stringRedisTemplate.expire(key, Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.warn("[seckill-query] 回填商品 hash 失败 sessionId={} skuId={}",
                    session.getId(), relation.getSkuId(), e);
        }
    }

    @Override
    public void refreshSessionSku(Long sessionId, Long skuId) {
        // 1. 参数校验
        if (sessionId == null || skuId == null) {
            return;
        }
        // 2. 场次和关联都必须存在，时间也要完整
        SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
        if (session == null || session.getStartTime() == null || session.getEndTime() == null) {
            log.warn("[seckill-refresh] 场次不存在或时间不完整 sessionId={} skuId={}", sessionId, skuId);
            return;
        }
        SeckillSkuRelationEntity relation = seckillSkuRelationDao.selectOne(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .eq("promotion_session_id", sessionId)
                        .eq("sku_id", skuId));
        if (relation == null) {
            log.warn("[seckill-refresh] 关联不存在 sessionId={} skuId={}", sessionId, skuId);
            return;
        }
        // 3. 已经超过结束时间 + 1 天，不用再写
        long ttlMillis = session.getEndTime().getTime() + ONE_DAY_MILLIS - System.currentTimeMillis();
        if (ttlMillis <= 0) {
            return;
        }
        // 4. 查商品；product 暂时不可用时保留旧 hash，等定时任务补
        ProductSkuVo sku = getProductSku(skuId);
        if (sku == null) {
            log.warn("[seckill-refresh] 商品信息查不到，保留旧缓存 sessionId={} skuId={}", sessionId, skuId);
            return;
        }
        // 5. 沿用旧 randomCode，覆盖这个 sku 的 hash 字段
        String infoKey = SeckillConstant.sessionSkusKey(sessionId);
        Object oldJson = stringRedisTemplate.opsForHash().get(infoKey, skuId.toString());
        SeckillSkuVo vo = buildSkuVo(session, relation, sku,
                oldJson == null ? null : String.valueOf(oldJson));
        try {
            stringRedisTemplate.opsForHash().put(infoKey, skuId.toString(),
                    objectMapper.writeValueAsString(vo));
            stringRedisTemplate.expire(infoKey, Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.error("[seckill-refresh] 刷新商品信息失败 sessionId={} skuId={}", sessionId, skuId, e);
        }
    }

    @Override
    public List<SeckillHomeVo> listSessionsWithSkus() {
        // 1. 和上架范围一致：未来 3 天 + 进行中
        List<SeckillSessionEntity> sessions = listActiveSessions();
        List<SeckillHomeVo> result = new ArrayList<>();
        for (SeckillSessionEntity session : sessions) {
            SeckillHomeVo home = buildHomeVo(session);
            // 2. 没有关联商品（或还没上架）的场次不展示，否则页面只剩空壳
            if (home.getSkus() != null && !home.getSkus().isEmpty()) {
                result.add(home);
            }
        }
        return result;
    }

    @Override
    public SeckillHomeVo getHome() {
        List<SeckillHomeVo> homes = listSessionsWithSkus();
        if (homes.isEmpty()) {
            return null;
        }
        // 1. 多个进行中时，固定选最近开始的一场，避免首页在几个场次之间跳来跳去
        SeckillHomeVo latestRunning = null;
        for (SeckillHomeVo home : homes) {
            if (!Integer.valueOf(SeckillSessionState.RUNNING).equals(home.getState())) {
                continue;
            }
            if (latestRunning == null
                    || (home.getStartTime() != null && (latestRunning.getStartTime() == null
                    || home.getStartTime() > latestRunning.getStartTime()))) {
                latestRunning = home;
            }
        }
        if (latestRunning != null) {
            return latestRunning;
        }
        // 2. 没有进行中，展示最近一场
        return homes.get(0);
    }

    @Override
    public SeckillSkuInfoVo getSkuInfo(Long sessionId, Long skuId, Long memberId) {
        // 1. 参数
        if (sessionId == null || skuId == null) {
            return null;
        }
        // 2. 商品信息以 Redis hash 为准；hash 缺失时回源数据库/商品服务
        Object json = null;
        try {
            json = stringRedisTemplate.opsForHash()
                    .get(SeckillConstant.sessionSkusKey(sessionId), skuId.toString());
        } catch (Exception e) {
            log.error("[ALERT][seckill-sku-info] Redis 读取商品失败 sessionId={} skuId={}",
                    sessionId, skuId, e);
        }
        SeckillSkuVo sku = null;
        if (json != null) {
            try {
                sku = objectMapper.readValue(String.valueOf(json), SeckillSkuVo.class);
            } catch (Exception e) {
                log.error("[seckill-sku-info] 商品信息解析失败 sessionId={} skuId={}", sessionId, skuId, e);
            }
        }
        if (sku == null) {
            sku = loadSkuFromDb(sessionId, skuId);
        }
        if (sku == null) {
            return null;
        }

        // 3. 剩余库存
        SeckillSkuInfoVo info = new SeckillSkuInfoVo();
        info.setSku(sku);
        int stock = 0;
        try {
            String value = stringRedisTemplate.opsForValue()
                    .get(SeckillConstant.stockKey(sessionId, skuId));
            if (value != null) {
                stock = Integer.parseInt(value);
            } else {
                log.error("[ALERT][seckill-sku-info] 秒杀库存 key 缺失 sessionId={} skuId={}",
                        sessionId, skuId);
            }
        } catch (Exception e) {
            log.error("[ALERT][seckill-sku-info] Redis 读取库存失败 sessionId={} skuId={}",
                    sessionId, skuId, e);
        }
        info.setStock(stock);

        // 4. 当前会员已购数量，游客按 0
        int bought = 0;
        if (memberId != null) {
            try {
                Object value = stringRedisTemplate.opsForHash()
                        .get(SeckillConstant.userCountKey(sessionId, skuId), memberId.toString());
                if (value != null) {
                    bought = Integer.parseInt(String.valueOf(value));
                }
            } catch (Exception e) {
                log.error("[ALERT][seckill-sku-info] Redis 读取限购失败 sessionId={} skuId={} memberId={}",
                        sessionId, skuId, memberId, e);
            }
        }
        info.setBought(bought);
        info.setLimit(sku.getSeckillLimit() == null ? 1 : sku.getSeckillLimit());
        return info;
    }

    /**
     * 单个 sku 的 Redis hash 缺失时，回源关联表 + product 拼出展示数据。
     */
    private SeckillSkuVo loadSkuFromDb(Long sessionId, Long skuId) {
        // 1. 查场次，场次不存在或时间不完整就返回 null
        SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
        if (session == null || session.getStartTime() == null || session.getEndTime() == null) {
            return null;
        }
        // 2. 查这个场次下这个 sku 的秒杀配置，没配置说明它根本不是秒杀商品
        SeckillSkuRelationEntity relation = seckillSkuRelationDao.selectOne(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .eq("promotion_session_id", sessionId)
                        .eq("sku_id", skuId)
                        .last("LIMIT 1"));
        if (relation == null) {
            return null;
        }
        // 3. 调商品服务补商品名称/图片/价格，查不到就返回 null
        ProductSkuVo product = getProductSku(skuId);
        if (product == null) {
            return null;
        }
        // 4. 拼成 VO 后顺手写回 Redis，下次查询就能直接命中缓存
        SeckillSkuVo vo = buildSkuVo(session, relation, product, null);
        cacheSku(session, relation, vo);
        return vo;
    }

    private SeckillHomeVo buildHomeVo(SeckillSessionEntity session) {
        // 1. 拷贝场次的基础信息：id、名称、起止时间
        SeckillHomeVo home = new SeckillHomeVo();
        home.setSessionId(session.getId());
        home.setSessionName(session.getName());
        home.setStartTime(session.getStartTime() == null ? null : session.getStartTime().getTime());
        home.setEndTime(session.getEndTime() == null ? null : session.getEndTime().getTime());
        // 2. 用库里的状态和当前时间算出页面真正要显示的状态（未开始/进行中/已结束）
        home.setState(SeckillSessionState.effectiveState(session.getSeckillState(),
                session.getStartTime(), session.getEndTime(), new Date()));
        // 3. 查这场秒杀的商品列表，组装成首页展示的场次卡片
        home.setSkus(getSessionSkus(session.getId()));
        return home;
    }

    /**
     * 用场次、关联、商品信息组装秒杀商品 VO
     */
    private SeckillSkuVo buildSkuVo(SeckillSessionEntity session, SeckillSkuRelationEntity relation,
                                    ProductSkuVo sku, String oldJson) {
        SeckillSkuVo vo = new SeckillSkuVo();
        vo.setPromotionId(relation.getPromotionId());
        vo.setPromotionSessionId(session.getId());
        vo.setSkuId(relation.getSkuId());
        vo.setWareId(relation.getWareId());
        vo.setSeckillPrice(relation.getSeckillPrice());
        vo.setSeckillCount(relation.getSeckillCount());
        vo.setSeckillLimit(relation.getSeckillLimit());
        vo.setSeckillSort(relation.getSeckillSort());
        vo.setStartTime(session.getStartTime().getTime());
        vo.setEndTime(session.getEndTime().getTime());
        // 1. 旧 randomCode 存在就沿用，否则新生成
        vo.setRandomCode(resolveRandomCode(oldJson));
        // 2. 补上商品服务里的名称、默认图片和原价，供页面展示
        vo.setSkuName(sku.getSkuName());
        vo.setSkuImg(sku.getSkuDefaultImg());
        vo.setSkuPrice(sku.getPrice());
        return vo;
    }

    /**
     * randomCode 一旦发出去就不再变，避免页面上的码因为上架任务而失效
     */
    private String resolveRandomCode(String oldJson) {
        // 1. 有旧缓存就解析，能拿到非空 randomCode 就继续沿用，保证已经发出去的码不失效
        if (oldJson != null) {
            try {
                SeckillSkuVo old = objectMapper.readValue(oldJson, SeckillSkuVo.class);
                if (old.getRandomCode() != null && !old.getRandomCode().isBlank()) {
                    return old.getRandomCode();
                }
            } catch (Exception e) {
                log.warn("[seckill-upload] 旧商品信息解析失败，重新生成 randomCode", e);
            }
        }
        // 2. 没有旧码或旧码解析失败，就新生成一个去掉横线的 UUID 作为随机码
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 调 product 查单个 sku
     */
    private ProductSkuVo getProductSku(Long skuId) {
        try {
            // 1. 调商品服务查询这个 sku 的完整信息
            R result = productFeignClient.getSkuInfo(skuId);
            // 2. 返回为空、返回码不是 0 或没带 skuInfo，都认为查不到，返回 null 让上层降级
            if (result == null || result.getCode() == null || result.getCode() != 0) {
                return null;
            }
            Object data = result.get("skuInfo");
            if (data == null) {
                return null;
            }
            // 3. 把返回的 Map 转成 ProductSkuVo，字段对不上会抛异常走下面的兜底
            return objectMapper.convertValue(data, ProductSkuVo.class);
        } catch (Exception e) {
            log.error("[seckill-upload] 查询商品失败 skuId={}", skuId, e);
            return null;
        }
    }
}
