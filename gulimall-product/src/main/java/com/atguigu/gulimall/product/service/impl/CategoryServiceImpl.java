package com.atguigu.gulimall.product.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.product.dao.CategoryBrandRelationDao;
import com.atguigu.gulimall.product.dao.CategoryDao;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.method.CategoryMethod;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.utils.GzipUtils;
import com.atguigu.gulimall.product.vo.Catelog2Vo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service("categoryService")
public class CategoryServiceImpl extends ServiceImpl<CategoryDao, CategoryEntity> implements CategoryService {

    private final CategoryDao categoryDao;
    private final CategoryBrandRelationDao categoryBrandRelationDao;
    private final CategoryMethod categoryMethod;

    /** 分类树在 Redis(L2) 里的 key */
    private static final String CATELOG_CACHE_KEY = "catelogJSON";

    /** Spring Cache 的缓存名，必须与 @Cacheable(cacheNames = ...) 里写的完全一致 */
    private static final String CATELOG_CACHE_NAME = "catelogJson";

    /** 分类树更新时互斥用的分布式锁名，必须与 getCatelogJson 里用的是同一把 */
    private static final String CATELOG_LOCK_NAME = "catelog-lock";

    /*
    ===== 已弃用 StringRedisTemplate，改用 byteRedisTemplate（见 MyRedisConfig）=====

    原因：缓存的 JSON 经 GZIP 压缩后是二进制数据，而 StringRedisTemplate 的 value
    序列化器 StringRedisSerializer 只认 UTF-8 文本。二进制必须先 Base64 编码才能塞进去，
    而 Base64 会让体积膨胀约 33% —— 实测 14.7KB 的 GZIP 结果被撑到 19.6KB。
    换成 RedisTemplate<String, byte[]> 后二进制可以原样写入，省掉这层损耗。

    */
    // @Autowired
    // private StringRedisTemplate stringRedisTemplate;

    /** 缓存值走 byte[] 序列化器，配合 GzipUtils 直接存 GZIP 二进制 */
    private final RedisTemplate<String, byte[]> byteRedisTemplate;

    private final RedissonClient redissonClient;

    /** Spring Cache 管理器（本项目里就是 Caffeine，即 L1）。用于在事务提交后手动清 L1 */
    private final CacheManager cacheManager;

    @Autowired
    public CategoryServiceImpl(RedisTemplate<String, byte[]> byteRedisTemplate, CacheManager cacheManager,
                               CategoryBrandRelationDao categoryBrandRelationDao, CategoryDao categoryDao,
                               CategoryMethod categoryMethod, RedissonClient redissonClient) {
        this.byteRedisTemplate = byteRedisTemplate;
        this.cacheManager = cacheManager;
        this.categoryBrandRelationDao = categoryBrandRelationDao;
        this.categoryDao = categoryDao;
        this.categoryMethod = categoryMethod;
        this.redissonClient = redissonClient;
    }

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<CategoryEntity> page = this.page(
                new Query<CategoryEntity>().getPage(params),
                new QueryWrapper<>()
        );

        return new PageUtils(page);
    }

    /**
     * 查询所有分类以及子分类，以树形结构组装起来
     *
     * @return 树形结构的分类列表
     */
    @Override
    public List<CategoryEntity> listWithTree() {
        // 1.查询所有分类
        List<CategoryEntity> allCategory = getAllCategory();
        // 2.组装成父子的树形结构
        // 2.1 找到所有的一级分类
        return allCategory.stream()
                .filter(category -> category.getParentCid() == 0L)
                // 2.2 递归查找子分类
                .peek(category -> category.setChildren(getChildren(category, allCategory)))
                // 2.3 按照sort字段进行排序
                .sorted(Comparator.comparingInt((CategoryEntity c) -> c.getSort() == null ? 0 : c.getSort()).reversed())
                .toList();
    }

    /**
     * 删除分类
     *
     * @param longs 要删除的分类ID列表
     */
    @Override
    public void removeMenusByIds(List<Long> longs) {
        // 2.1 抢与查询路径同一把锁，保证「删DB+清缓存」与「查DB+写缓存」互斥，避免查询线程在删除后把旧数据重新写回缓存
        RLock lock = redissonClient.getLock(CATELOG_LOCK_NAME);
        lock.lock(30, TimeUnit.SECONDS);
        try {
            // 1.1 检查当前删除的菜单是否被别的地方引用（TODO）

            // 1.2 如果未被引用，则执行逻辑删除操作
            categoryDao.deleteByIds(longs);

            // 2.2 清掉 L1(Caffeine) + L2(Redis) 缓存
            evictCacheAfterCommit();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 找到catelogId的完整路径 [父/子/孙]
     *
     * @param catelogId catelogId
     * @return catelogId的完整路径
     */
    @Override
    public Long[] findCatelogPath(Long catelogId) {
        return categoryMethod.findCatelogPath(catelogId);
    }

    /**
     * 修改功能<br>
     * 因为存在品牌分类关联表，所以修改品牌信息需要级联更新<br>
     *
     * @param category 分类实体
     */
    /*
    这里不再用 @CacheEvict，改为在事务提交后手动清 L1+L2，原因有两个：
      1. @CacheEvict 在方法返回时触发，而事务的 COMMIT 发生在那之后 —— 中间这段窗口里，
         读请求会因「缓存已清但DB还没提交」而读到旧值，并把它写回缓存
      2. @CacheEvict 只能清 Spring Cache（本项目即 L1 Caffeine），清不到 Redis（L2）。
         而 L1 与 L2 必须同时清 —— 否则读请求会从没被清的那一层把旧值捞回来。
    具体实现见 evictCacheAfterCommit()
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateDetails(CategoryEntity category) {

        /*
        两种模式：
            双写模式：更新/删除操作后同时修改DB与redis缓存
            失效模式：更新/删除操作后删除redis缓存，下次查询后自动缓存新数据

        并发场景脏数据问题补充
            两个线程（更新、删除）同时进行，更新线程结束后，用户进行了查询操作，此时恰好redis中没有缓存，则将此处查询数据缓存进redis
            同时删除操作也结束了，因为redis有缓存因此本次数据不进缓存，出现了DB与redis数据库不一致问题（脏数据）
        解决方案：主要根据实际业务场景考虑
            方案1：不关心并发问题
                对于并发量很低的系统，脏数据问题基本不出现，无需考虑，直接用双写或失效模式即可
            方案2：考虑小并发场景
                使用失效模式 + 延迟双删，更新/删除后首次删除redis后，一定间隔内再次删除redis缓存
            方案3：大并发/复杂微服务场景
                使用MQ消息队列订阅修改 / 从数据库Canal的Binlog日志保证数据一致性
            方案4：对数据一致性要求非常高
                使用分布式锁协调缓存读取和数据库更新（数据修改时查询等待） / 直接不使用缓存，所有请求查询DB
            兜底方案：四个方案选择 + 设置缓存TTL过期时间
                前三种方案无法完全避免脏数据问题，可以设置TTL过期时间（10-30min）主动更新数据，达到清除脏数据的目的
         */

        // 使用方案4优化，因为分类列表修改操作很少，因此加锁不会导致性能问题
        // 2.1 抢与查询路径同一把锁：保证「更新DB+清缓存」与「查DB+写缓存」互斥，否则查询线程可能把更新前的旧值重新写回缓存
        RLock lock = redissonClient.getLock(CATELOG_LOCK_NAME);
        lock.lock(30, TimeUnit.SECONDS);
        try {
            // 1.1 更新当前分类。
            //   走 super 而不是 this：本类重写了 updateById（会注册一次缓存清理），
            //   这里自己已经要清一次，走 this 会重复注册
            super.updateById(category);
            // 1.2 级联更新品牌分类关联表中的分类名称
            if (StringUtils.isNotEmpty(category.getName())) {
                categoryBrandRelationDao.updateCatelogName(category.getCatId(), category.getName());
            }
            // 2.2 失效模式：事务提交后清掉 L1(Caffeine) + L2(Redis)
            evictCacheAfterCommit();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查找所有一级分类
     *
     * @return 所有一级分类
     */
    @Override
    public List<CategoryEntity> getLevel1CategoryList() {
        return baseMapper.selectList(
                new QueryWrapper<CategoryEntity>().eq("parent_cid", 0).eq("show_status", 1L));
    }

    /**
     * 查询1级分类的分类列表。三级缓存：Caffeine(L1) -> Redis(L2) -> MySQL(L3)
     * <p>
     * sync = true 是关键：L1 里的条目是同一时刻写入、TTL 相同，因此会在同一毫秒集体过期。
     * sync=true 让 Caffeine 对同一个 key 只放一个线程进去加载，其余线程阻塞等待并复用结果，
     * 避免"L1 过期那一瞬间 200 个线程同时去压 Redis"。
     * <p>
     * 注意 @Cacheable 靠代理生效：同类内部 this.getCatelogJson() 自调用不走缓存，
     * 必须由外部（如 IndexController）注入后调用。
     * <p>
     * 其它@Cache注解及属性见 document/Cache
     *
     * @return 1级分类的分类列表
     */
    @Cacheable(cacheNames = "catelogJson", key = "'all'", sync = true)
    @Override
    public Map<String, List<Catelog2Vo>> getCatelogJson() {

        /*
        三大缓存问题
            见document/缓存失效.png
            1.缓存穿透：频繁查询null结果，导致一直未进行redis缓存频繁访问DB
                将null也缓存进redis，设置默认值和过期时间
            2.缓存雪崩：大量redis缓存同时过期
                每个redis设置随机过期时间或永久缓存
            3.缓存击穿：大量线程同时访问redis中缓存刚过期/未缓存的数据，导致频繁访问DB
                加锁 10000DB查询 -> 1DB查询 + 9999redis查询
         */

        /*
         整体优化方案：预热 + cache + redis + redis分布式锁 + config参数设置 + JSON压缩 + DB查询 + 修改redis失效模式
         后续问题优化：
             事务提交早于缓存清理问题 -> 更新数据时cache改为evictCacheAfterCommit
             脏数据读取问题 -> 更新数据时加锁
             service接口数据更新方法未清理缓存问题 -> 重写接口加缓存清理
         仍存在的问题：
             分布式下多个服务会有多个cache实例对象，修改数据后多个服务拿到的缓存可能不一致
             因为分类数据很少修改且cache5min刷新一次，因此暂时允许出现这个问题
        */

        // 1.添加方法预热 见CacheWarmUpRunner
        // 方法首次启动时如果多个线程同时访问，其它线程会等待一个线程查询Redis + DB，预热可以取消这部分时间

        // 2.添加cache缓存
        // @Cacheable是本地缓存，yaml中设置了缓存时间（5min），对于不常修改的数据，缓存时间可以很长，使得访问一直走cache本地缓存
        // 缺点：数据修改后，5min内短暂出现多个服务查询数据不一致的情况（若需要数据精确可以降低cache缓存时长）
        // 能进到方法体，说明 L1(Caffeine) 已未命中
        log.info("[catelog] L1 未命中，转查 L2(Redis)");

        // 3.第一次读 Redis（不加锁）
        try {
            // 4.JSON数据量优化 见 GzipUtils + MyRedisConfig
            // 某些JSON数据量可能很多，多线程同时访问时整体网络IO较高（线程数 * JSON大小），易引发redis访问超时重试
            // 因此可以将JSON数据压缩为byte存储进redis（数据量可减少4-5倍左右）
            String catelogJSON = GzipUtils.decompress(
                    byteRedisTemplate.opsForValue().get(CATELOG_CACHE_KEY));
            if (StringUtils.isNotEmpty(catelogJSON)) {
                log.info("[catelog] L2 命中，直接返回");
                return JSON.parseObject(catelogJSON, new TypeReference<>() {
                });
            }
            log.info("[catelog] L2 未命中，准备抢分布式锁");
        } catch (QueryTimeoutException e) {
            // 5.5 Redis 访问超时降级：直接查 DB 返回。
            // 这里必须 return —— 否则 catch 执行完会继续往下再走一遍完整的分布式锁流程，
            // 白白多等一次锁（实测 620ms~2.9s），这正是 P99 被顶到 9s 的直接原因。
            log.warn("[catelog] L2 读取超时，降级直查 DB", e);
            Map<String, List<Catelog2Vo>> catelogJsonFromDB = getCatelogJsonFromDB();
            writeBackToRedis(catelogJsonFromDB);
            return catelogJsonFromDB;
        }

        // 5.分布式锁应用：缓存击穿时只放一个线程去查 DB
        RLock catelogLock = redissonClient.getLock("catelog-lock");
        log.info("[catelog] 开始抢分布式锁");
        // 5.1 设置锁时长30s后自动unlock，不触发看门狗检测自动续期锁，因为该业务不会超过30s
        catelogLock.lock(30, TimeUnit.SECONDS);
        log.info("[catelog] 已获取分布式锁");
        Map<String, List<Catelog2Vo>> catelogJsonFromDB;
        try {
            // 5.2 二次检查Redis缓存：可能有别的线程刚写过
            String catelogJSON2 = GzipUtils.decompress(
                    byteRedisTemplate.opsForValue().get(CATELOG_CACHE_KEY));
            if (StringUtils.isNotEmpty(catelogJSON2)) {
                log.info("[catelog] 锁内二次检查命中 L2，直接返回");
                return JSON.parseObject(catelogJSON2, new TypeReference<>() {
                });
            }

            // 5.3 缓存中没有，DB查询 + Redis缓存
            // 因为这里的Map不可能为null，因此不考虑缓存穿透，
            // 实际需要将null变成空字符串存储进redis，设置5min过期时间，从redis中读取出来也要加额外的""->null判断
            log.info("[catelog] 锁内仍未命中，查 DB");
            catelogJsonFromDB = getCatelogJsonFromDB();
            // 5.4 Map对象转JSON，GZIP 压缩后再存储到Redis
            // 5.7 脏数据问题 见更新和删除操作
            writeBackToRedis(catelogJsonFromDB);
            log.info("[catelog] DB 查询完成，已回写 Redis");
        } catch (QueryTimeoutException e) {
            // 5.5 Redis访问超时降级，仍从DB查询数据返回。
            // Redisson的RedissonConnectionFactory替换了默认的Lettuce，自带访问超时（3s）+超时等待（1.5s）+重试机制（4次）；
            // 线程数多 + 缓存数据量大时较易触发多次超时重试，因此已在 MyRedissonConfig 收紧超时与重试参数并扩大线程池
            log.warn("[catelog] 锁内 Redis 超时，降级直查 DB", e);
            catelogJsonFromDB = getCatelogJsonFromDB();
            writeBackToRedis(catelogJsonFromDB);
        } finally {
            // 5.6 redis访问结束后手动unlock防止30s锁才释放
            catelogLock.unlock();
            log.info("[catelog] 已释放分布式锁");
        }
        return catelogJsonFromDB;

//        // 演示本地锁，实际分布式多个服务同时运行，要使用分布式锁
//        // 1.1 查询redis是否有缓存，有缓存直接走缓存
//        String catelogJSON = stringRedisTemplate.opsForValue().get("catelogJSON");
//        if (StringUtils.isNotEmpty(catelogJSON2)) {
//            return JSON.parseObject(catelogJSON2, new TypeReference<>() {
//            });
//        }
//        synchronized (this) {
//
//            // 1.2 必须再次查询redis是否有缓存，否则可能每个线程都会执行DB查询
//            // 注意不要复用catelogJSON对象，因为那是已经查询完的旧数据！
//            String catelogJSON2 = stringRedisTemplate.opsForValue().get("catelogJSON");
//            if (StringUtils.isNotEmpty(catelogJSON2)) {
//                return JSON.parseObject(catelogJSON2, new TypeReference<>() {
//                });
//            }
//
//            // 1.3 必须保证DB查询和redis缓存是原子操作，否则redis可能还没有正常缓存，锁释放下一次线程就进来了，此时redis还没缓存仍然走DB
//            // 缓存中没有，DB查询 + Redis缓存
//            // 因为这里的Map不可能为null，因此不考虑缓存穿透，
//            // 实际需要将null变成空字符串存储进redis，设置5min过期时间，从redis中读取出来也要加额外的""->null判断
//            Map<String, List<Catelog2Vo>> catelogJsonFromDB = getCatelogJsonFromDB();
//            // Map对象转JSON存储到Redis
//            String result = JSON.toJSONString(catelogJsonFromDB);
//            // 设置过期时间
//            stringRedisTemplate.opsForValue().set("catelogJSON", result, 1, TimeUnit.DAYS);
//            return catelogJsonFromDB;
//        }

    }

    /**
     * 把查库结果压缩后写回 Redis。
     * 回写失败只记日志、不影响主流程 —— 数据已经查到了，缓存写不进去不该让请求失败。
     *
     * @param data 从 DB 查出的分类数据
     */
    private void writeBackToRedis(Map<String, List<Catelog2Vo>> data) {
        try {
            byteRedisTemplate.opsForValue()
                    .set(CATELOG_CACHE_KEY, GzipUtils.compress(JSON.toJSONString(data)),
                            1, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[catelog] 回写 Redis 失败，忽略", e);
        }
    }

    /**
     * 删除 L2(Redis) 里的分类树缓存。
     * <p>
     * 为什么必须单独调：方法上的 @CacheEvict 只作用于 Spring Cache 抽象 ——
     * 本项目里 Spring Cache 就是 L1(Caffeine)，<b>清不到 Redis</b>。
     * 只清 L1 的话，下一次查询会顺着 L2 命中旧数据；而 Redis 的 TTL 是 1 天，
     * 等于改了分类要等一整天才能生效（实测已验证：改名后立即查询仍返回旧名字）。
     * <p>
     * 所以两级缓存的失效必须一起做：@CacheEvict 负责 L1，这里负责 L2。
     * 删除失败只记日志，不阻断业务。
     */
    private void clearRedisCache() {
        try {
            Boolean deleted = byteRedisTemplate.delete(CATELOG_CACHE_KEY);
            log.info("[catelog] 已清除 L2(Redis) 缓存 key={}，结果={}", CATELOG_CACHE_KEY, deleted);
        } catch (Exception e) {
            log.warn("[catelog] 删除 L2(Redis) 缓存失败，忽略", e);
        }
    }

    /**
     * 事务提交成功之后再清缓存；不在事务中时立即清。
     * <p>
     * <b>为什么必须等提交之后：</b>@Transactional 的方法体执行完只是「标记待提交」，
     * 真正的 COMMIT 发生在方法返回、代理退出时。如果此时就把缓存清了，
     * 在「缓存已清」到「提交完成」之间的窗口里，读请求会查 DB 读到<b>旧值</b>
     * （事务尚未提交，其它连接看不到新值），并把这个旧值写回缓存，形成脏数据。
     */
    private void evictCacheAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictAllCacheLevels();
                }
            });
        } else {
            evictAllCacheLevels();
        }
    }

    /**
     * 同时清掉 L1(Caffeine) 与 L2(Redis)。
     * <p>
     * <b>两层必须一起清。</b>假如只清 L1：读请求 L1 未命中后会顺着 L2 命中原值，
     * 并把旧值回填进 L1 —— 等于白清。反过来只清 L2 也一样。
     * 这正是不能再用 @CacheEvict 的原因：它只作用于 Spring Cache，够不到 Redis。
     */
    private void evictAllCacheLevels() {
        // 底层使用ConcurrentMap存储cache数据
        Cache cache = cacheManager.getCache(CATELOG_CACHE_NAME);
        if (cache != null) {
            cache.clear();
            log.info("[catelog] 已清除 L1(Caffeine) 缓存 name={}", CATELOG_CACHE_NAME);
        }
        clearRedisCache();
    }

    /*
    ==================== IService 写入口收口 ====================

    CategoryService 继承自 MyBatis-Plus 的 IService，save / updateById / removeById 这些方法
    由父接口提供默认实现。项目里任何地方直接调用它们都会绕过上面的缓存清理逻辑，
    导致 Redis 里的分类树长期是旧数据、只能靠 TTL 兜底
    （实测 CategoryController.save() 就是这么调的）。

    所以在实现类里统一重写这些写入口，每一处都做三件事：
      1. 抢 CATELOG_LOCK_NAME 锁 —— 与查询路径互斥，避免「查DB+写缓存」把更新前的旧值写回来
      2. 执行写操作
      3. evictCacheAfterCommit() —— 清掉 L1 + L2

    这里同样不能用 @CacheEvict —— 它清不到 Redis，且时机早于事务提交。

    为什么不用一个私有方法把这三步抽出来：Java 不允许在 lambda 里写 super，
    而这里的写操作必须调 super.xxx()（调 this.xxx() 会无限递归）。
    所以只能逐个内联，看着啰嗦但没办法。

    注意：这只是"补漏"，不是万能的。以后新增写接口时，仍应在本 service 里实现业务方法、
    走 updateDetails/removeMenusByIds 那套，不要绕过 service 直接调 baseMapper。
     */

    @Override
    public boolean save(CategoryEntity entity) {
        RLock lock = redissonClient.getLock(CATELOG_LOCK_NAME);
        lock.lock(30, TimeUnit.SECONDS);
        try {
            boolean result = super.save(entity);
            evictCacheAfterCommit();
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean updateById(CategoryEntity entity) {
        RLock lock = redissonClient.getLock(CATELOG_LOCK_NAME);
        lock.lock(30, TimeUnit.SECONDS);
        try {
            boolean result = super.updateById(entity);
            evictCacheAfterCommit();
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeById(Serializable id) {
        RLock lock = redissonClient.getLock(CATELOG_LOCK_NAME);
        lock.lock(30, TimeUnit.SECONDS);
        try {
            boolean result = super.removeById(id);
            evictCacheAfterCommit();
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeByIds(Collection<?> list) {
        RLock lock = redissonClient.getLock(CATELOG_LOCK_NAME);
        lock.lock(30, TimeUnit.SECONDS);
        try {
            boolean result = super.removeByIds(list);
            evictCacheAfterCommit();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /*
     * TODO 【缓存收口待补】以下 IService 写方法还没重写，被调用时会绕过缓存清理：
     *          saveBatch / saveOrUpdate / saveOrUpdateBatch
     *          update(Wrapper) / updateBatchById
     *          remove(Wrapper) / removeByMap
     *
     *      本项目当前用不到，先不补。等真要用到时，照着上面 save() 的写法补三件事：
     *          1) 抢 CATELOG_LOCK_NAME 锁
     *          2) 调 super.xxx(...) 执行写操作
     *          3) 成功后 evictCacheAfterCommit()，finally 里解锁
     */

    /*
     * ===== 已整体注释：手写 Redis 分布式锁的演示代码 =====
     * 注释原因：它依赖 StringRedisTemplate 存 UUID 字符串并执行 Lua 脚本，
     * 而 stringRedisTemplate 字段已随缓存值改用 byte[] 一并弃用（原因见类顶部字段说明）。
     * 该方法是演示性质，实际业务已改用 Redisson 分布式锁，故保留备查。
     *
    // 自定义分布式锁演示，实际会用分布式锁Redisson
    public Map<String, List<Catelog2Vo>> getCatelogJsonFromDbWithRedisLock() {
        //1、占分布式锁。去redis占坑
        String uuid = UUID.randomUUID().toString();
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent("lock", uuid, 300, TimeUnit.SECONDS);
        if (lock) {
            System.out.println("获取分布式锁成功...");
            //加锁成功... 执行业务
            //2、设置过期时间，必须和加锁是同步的，原子的
            //stringRedisTemplate.expire("lock",30,TimeUnit.SECONDS);
            Map<String, List<Catelog2Vo>> dataFromDb;
            try {
                dataFromDb = getCatelogJsonFromDB();
            } finally {
                //获取值对比+对比成功删除=原子操作lua 脚本解锁
                // String lockValue = stringRedisTemplate.opsForValue().get("lock");
                // if(uuid.equals(lockValue)){
                // //删除我自己的锁
                // stringRedisTemplate.delete("lock");//删除锁
                // }
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end ";
                //删除锁
                Long lock1 = stringRedisTemplate.execute(
                        new DefaultRedisScript<>(script, Long.class) , List.of("lock"), uuid);
            }
            return dataFromDb;
        } else {
            //加锁失败...重试
            //休眠100ms 重试
            System.out.println("获取分布式锁失败...等待重试");
            try {
                Thread.sleep(100);
            } catch (Exception e) {
            }
            return getCatelogJsonFromDbWithRedisLock();//自旋的方式
        }
    }
    */

    /**
     * 从DB中查询1级分类的分类列表
     *
     * @return DB中查询1级分类的分类列表
     */
    private Map<String, List<Catelog2Vo>> getCatelogJsonFromDB() {

        // 这么写数据库炸了
        // 查询全部1级分类
//        List<CategoryEntity> level1CategoryList = getLevel1CategoryList();
//        return level1CategoryList.stream().collect(Collectors.toMap(
//                k -> k.getCatId().toString(),
//                v -> {
//                    // 查询2级分类
//                    List<CategoryEntity> level2CategoryList = baseMapper.selectList(
//                            new QueryWrapper<CategoryEntity>().eq("parent_cid", v.getCatId()));
//                    List<Catelog2Vo> catelog2Vos = new ArrayList<>();
//                    if (level2CategoryList != null) {
//                        catelog2Vos = level2CategoryList.stream().map(
//                                category2 -> {
//                                    Catelog2Vo catelog2Vo = new Catelog2Vo();
//                                    catelog2Vo.setCatelog1Id(v.getCatId().toString());
//                                    // 查询3级分类
//                                    List<CategoryEntity> level3CategoryList = baseMapper.selectList(
//                                            new QueryWrapper<CategoryEntity>().eq("parent_cid", category2.getCatId()));
//                                    List<Catelog2Vo.Catelog3Vo> catelog3Vos = null;
//                                    if (level3CategoryList != null) {
//                                        catelog3Vos = level3CategoryList.stream().map(
//                                                category3 -> {
//                                                    Catelog2Vo.Catelog3Vo catelog3Vo = new Catelog2Vo.Catelog3Vo();
//                                                    catelog3Vo.setCatelog2Id(category2.getCatId().toString());
//                                                    catelog3Vo.setId(category3.getCatId().toString());
//                                                    catelog3Vo.setName(category3.getName());
//                                                    return catelog3Vo;
//                                                }
//                                        ).toList();
//                                    }
//                                    catelog2Vo.setCatelog3List(catelog3Vos);
//                                    catelog2Vo.setId(category2.getCatId().toString());
//                                    catelog2Vo.setName(category2.getName());
//                                    return catelog2Vo;
//                                }
//                        ).toList();
//                    }
//                    return catelog2Vos;
//                }
//        ));

        // 1.查询全部数据
        List<CategoryEntity> allCategory = getAllCategory();
        if (allCategory == null || allCategory.isEmpty()) {
            return new HashMap<>();
        }

        // 2.数据封装
        return allCategory.stream()
                // 过滤出全部1级分类
                .filter(category -> category.getParentCid().equals(0L))
                // 数据封装
                .collect(Collectors.toMap(
                        k -> k.getCatId().toString(),
                        v -> {
                            // 无需额外考虑集合为null，toList流式操作没有匹配元素自动返回空集合
                            // 当前1级分类的全部2级分类
                            /*
                                getCategoryAllChildren还可以继续优化，当前子节点检索每次都是全表数据查询，
                            可以将全部数据按parent_cid分组存储到Map<String, List<CategoryEntity>>中，
                            这样每次直接检索map集合即可。此处数据量少可无需优化。
                             */
                            List<CategoryEntity> categorylevel2List = getCategoryAllChildren(v, allCategory);
                            return categorylevel2List.stream().map(
                                    categorylevel2 -> {
                                        Catelog2Vo catelog2Vo = new Catelog2Vo();
                                        catelog2Vo.setCatelog1Id(v.getCatId().toString());
                                        // 当前2级分类的全部3级分类
                                        List<CategoryEntity> categorylevel3List = getCategoryAllChildren(categorylevel2, allCategory);
                                        List<Catelog2Vo.Catelog3Vo> catelog3Vos = categorylevel3List.stream().map(
                                                categorylevel3 -> new Catelog2Vo.Catelog3Vo(
                                                        categorylevel2.getCatId().toString(),
                                                        categorylevel3.getCatId().toString(),
                                                        categorylevel3.getName())
                                        ).toList();
                                        catelog2Vo.setCatelog3List(catelog3Vos);
                                        catelog2Vo.setId(categorylevel2.getCatId().toString());
                                        catelog2Vo.setName(categorylevel2.getName());
                                        return catelog2Vo;
                                    }
                            ).toList();
                        }
                ));
    }

    /**
     * 查询所有分类
     *
     * @return 所有分类
     */
    private List<CategoryEntity> getAllCategory() {
        return categoryDao.selectList(
                new LambdaQueryWrapper<CategoryEntity>().eq(CategoryEntity::getShowStatus, 1L));
    }

    /**
     * 查询当前分类的全部子分类
     *
     * @param rootCategory 当前分类
     * @param allCategory  所有分类
     * @return 当前分类的全部子分类
     */
    private List<CategoryEntity> getCategoryAllChildren(CategoryEntity rootCategory, List<CategoryEntity> allCategory) {
        if (allCategory == null) {
            return new ArrayList<>();
        }
        return allCategory.stream()
                .filter(category -> Objects.equals(category.getParentCid(), rootCategory.getCatId()))
                .sorted(Comparator.comparingInt((CategoryEntity c) -> c.getSort() == null ? 0 : c.getSort()).reversed())
                .toList();
    }

    /**
     * 递归查找子分类
     *
     * @param rootCategory 子分类的根节点
     * @param allCategory  所有分类
     * @return 子分类列表
     */
    private List<CategoryEntity> getChildren(CategoryEntity rootCategory, List<CategoryEntity> allCategory) {
        if (allCategory == null) {
            return new ArrayList<>();
        }
        return allCategory.stream()
                .filter(category -> category.getParentCid().equals(rootCategory.getCatId()))
                .peek(category -> category.setChildren(getChildren(category, allCategory)))
                .sorted(Comparator.comparingInt((CategoryEntity c) -> c.getSort() == null ? 0 : c.getSort()).reversed())
                .toList();
    }

}