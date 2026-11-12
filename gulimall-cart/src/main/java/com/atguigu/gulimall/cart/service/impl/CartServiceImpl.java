package com.atguigu.gulimall.cart.service.impl;

import com.alibaba.fastjson2.JSON;
import com.atguigu.gulimall.api.cart.CartItemVo;
import com.atguigu.gulimall.api.cart.CartVo;
import com.atguigu.gulimall.api.cart.SkuInfoVo;
import com.atguigu.gulimall.api.cart.UserInfoTo;
import com.atguigu.gulimall.cart.feign.ProductFeignClient;
import com.atguigu.gulimall.cart.feign.WareSkuFeignClient;
import com.atguigu.gulimall.cart.service.CartService;
import com.atguigu.gulimall.cart.thread.UserThreadLocal;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.atguigu.gulimall.common.constant.CartConstant.CART_PREFIX;
import static com.atguigu.gulimall.common.constant.CartConstant.TEMP_USER_COOKIE_TIMEOUT;

/**
 * @ClassName: CartServiceImpl
 * @Package: com.atguigu.gulimall.cart.service.impl
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/7 - 17:03
 * @Version: v1.0
 */
@Service("cartService")
@Slf4j
public class CartServiceImpl implements CartService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ProductFeignClient productFeignClient;
    @Autowired
    private WareSkuFeignClient wareSkuFeignClient;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ThreadPoolTaskExecutor threadPoolExecutor;

    /**
     * 给购物车的【读 → 改 → 写】加一把分布式锁。
     *
     * <p>不加锁会丢更新：加购的"数量累加"在并发下两次都读到同一个旧值、互相覆盖（丢件）；
     * 登录后的临时车合并也可能被两个请求各执行一遍（数量翻倍）。
     *
     * <p>Redisson 的锁是可重入的 —— getCart() 合并时会再调 addToCart()，同一个线程重复拿锁没问题。
     * 锁的粒度是"一个用户的购物车"，不同用户之间互不阻塞。
     *
     * @param cartKey 购物车的 key（实际锁名是 cartKey + ":lock"，见下面的注释）
     * @param action  要串行执行的操作
     */
    private void withCartLock(String cartKey, Runnable action) {

        /*
         ⚠️ 锁名必须和购物车 key 分开！Redisson 的锁自己就是一个 hash（在锁名这个 key 里
            记"谁持有、重入了几次"）。直接拿购物车 key 当锁名的话，锁状态和购物车数据会住同一个 key：
            ① 解锁时 Redisson 会把整个 key 删掉 —— 等于把购物车清空；
            ② action 里 getCartOps() 给游客车设的 30 天过期会覆盖锁的 30 秒租约，
               一旦某次没走到 unlock，锁就挂 30 天，页面一直报"操作太频繁"；
            ③ 锁的字段会混进购物车 hash，被 getCartItems 当成商品 JSON 解析。
        */
        RLock lock = redissonClient.getLock(cartKey + ":lock");
        try {
            // 最多等 3 秒拿锁；拿到后最多持有 10 秒（防止持锁线程挂掉后锁一直不释放）
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                throw new RRException(BizCodeEnum.CART_BUSY_EXCEPTION);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException(BizCodeEnum.CART_BUSY_EXCEPTION);
        }

        try {
            action.run();
        } finally {
            // tryLock 超时那条路根本没拿到锁，不能 unlock（会抛 IllegalMonitorStateException）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void addToCart(Long skuId, Integer num) {

        // 加锁原因见 withCartLock：这里是"读 Redis → 累加数量 → 写回 Redis"，不加锁并发时两次加购会互相覆盖（丢件）
        /*
            实际锁内不推荐远程调用，因为远程调用只是几十ms，会导致锁持有时间变长，一般将远程调用拿到锁外执行，结果拿到锁内使用
            因为这里1s内单个用户不会产生很多请求 + 远程调用就两个（最多几十ms） + common.yaml远程调用设置了超时时间（5s，默认60s）
            因此无需考虑远程调用超时导致占锁 & 为了极致的性能大面积修改代码，保持原样即可
        */
        withCartLock(getCartOps().getKey(), () -> {

            // 0.先校验库存：无货直接抛业务异常（放在item商品页中判断，在这里判断了无法跳转回商品页）

            CartItemVo cartItem;
            BoundHashOperations<String, String, String> boundHashOperations = getCartOps();
            String cartItemVoJson = boundHashOperations.get(skuId.toString());
            // 1.判断当前商品是否已经加入购物车
            if (cartItemVoJson == null) {
                // 2.未加入则查询商品信息加入购物车
                // 2.1.远程查询当前添加商品的信息
                CompletableFuture<SkuInfoVo> getSkuInfo = CompletableFuture.supplyAsync(() -> {
                    R skuInfo = productFeignClient.getSkuInfo(skuId);
                    return skuInfo.getData("skuInfo", new TypeReference<>() {
                    });
                }, threadPoolExecutor);

                // 2.2.远程查询sku的组合信息
                CompletableFuture<List<String>> getSkuSaleAttrValues = CompletableFuture.supplyAsync(
                        () -> productFeignClient.getSkuSaleAttrValues(skuId), threadPoolExecutor);

                // 2.3.带上每个商品的实时库存：购物车页要用它限制「+」和手动输入的数量
                CompletableFuture<Map<Long, Long>> getStockNums = CompletableFuture.supplyAsync(
                        () -> wareSkuFeignClient.getStockNums(List.of(skuId)), threadPoolExecutor);

                // 2.4.远程查询商品重量
                CompletableFuture<BigDecimal> getStockWeight = CompletableFuture.supplyAsync(
                        () -> productFeignClient.getSkuWeight(skuId));

                // 2.5.商品添加到购物车
                cartItem = CompletableFuture.allOf(getSkuInfo, getSkuSaleAttrValues, getStockNums, getStockWeight)
                        .thenApply(
                                vo -> {
                                    CartItemVo cartItemVo = new CartItemVo();
                                    cartItemVo.setCheck(true);
                                    cartItemVo.setCount(num);
                                    cartItemVo.setImage(getSkuInfo.join().getSkuDefaultImg());
                                    cartItemVo.setTitle(getSkuInfo.join().getSkuTitle());
                                    cartItemVo.setSkuId(skuId);
                                    cartItemVo.setPrice(getSkuInfo.join().getPrice());
                                    cartItemVo.setSkuAttrValues(getSkuSaleAttrValues.join());
                                    cartItemVo.setStock(getStockNums.join().get(skuId));
                                    cartItemVo.setWeight(getStockWeight.join());
                                    return cartItemVo;
                                }
                        ).join();
            } else {
                // 3.已加入购物车，累加购物车商品数量
                cartItem = JSON.parseObject(cartItemVoJson, CartItemVo.class);
                cartItem.setCount(cartItem.getCount() + num);
            }

            // 4.redis存储商品信息（StringRedisTemplate 只能存字符串，对象要自己转 JSON）
            boundHashOperations.put(skuId.toString(), JSON.toJSONString(cartItem));

        });

    }

    /**
     * 获取购物车数据
     *
     * @param skuId
     * @return
     */
    @Override
    public CartItemVo getCartItem(Long skuId) {
        BoundHashOperations<String, String, String> boundHashOperations = getCartOps();
        String cartItemVoJson = boundHashOperations.get(skuId.toString());
        return JSON.parseObject(cartItemVoJson, CartItemVo.class);
    }

    /**
     * 获取要操作的购物车
     *
     * @return
     */
    private BoundHashOperations<String, String, String> getCartOps() {
        UserInfoTo userInfoTo = UserThreadLocal.get();
        String userKey;
        if (userInfoTo.getUserId() != null) {
            // 用户登录，用户购物车
            userKey = CART_PREFIX + userInfoTo.getUserId();
        } else {
            // 游客购物车
            userKey = CART_PREFIX + userInfoTo.getUserKey();
        }
        BoundHashOperations<String, String, String> cartOps = stringRedisTemplate.boundHashOps(userKey);
        /*
         游客车给 30 天过期（和 user-key cookie 的 30 天对齐）：不设的话每个新游客都会在 Redis
         留下一个永久 key，只增不减，配合 noeviction 迟早把内存写满；登录用户的购物车不设过期
        */
        if (userInfoTo.getUserId() == null) {
            cartOps.expire(TEMP_USER_COOKIE_TIMEOUT, TimeUnit.SECONDS);
        }
        return cartOps;
    }

    /**
     * 获取用户登录或者未登录购物车里所有的数据
     *
     * @return
     */
    @Override
    public CartVo getCart() {

        CartVo cartVo = new CartVo();
        // 通过线程获取用户信息
        UserInfoTo userInfoTo = UserThreadLocal.get();
        if (userInfoTo.getUserId() != null) {
            //1、登录
            String userKey = CART_PREFIX + userInfoTo.getUserId();
            //临时购物车的键
            String temptUserKey = CART_PREFIX + userInfoTo.getUserKey();

            //2、临时购物车的数据还没合并的话，合并过来
            //   整段（查临时车 → 逐件写进用户车 → 删临时车）都要在锁里，否则两个请求会各读一份临时车、各合并一遍，数量直接翻倍
            withCartLock(userKey, () -> {
                List<CartItemVo> tempCartItems = getCartItems(temptUserKey);
                if (tempCartItems != null) {
                    //临时购物车有数据需要进行合并操作
                    for (CartItemVo item : tempCartItems) {
                        addToCart(item.getSkuId(), item.getCount());
                    }
                    //清除临时购物车的数据
                    clearCartInfo(temptUserKey);
                }
            });

            //3、获取登录后的购物车数据【包含合并过来的临时购物车的数据和登录后购物车的数据】
            List<CartItemVo> cartItems = getCartItems(userKey);
            cartVo.setItems(cartItems);

        } else {
            //没登录
            String userKey = CART_PREFIX + userInfoTo.getUserKey();
            //获取临时购物车里面的所有购物项
            List<CartItemVo> cartItems = getCartItems(userKey);
            cartVo.setItems(cartItems);
        }

        return cartVo;
    }

    /**
     * 获取购物车里面的数据
     *
     * @param userKey
     * @return
     */
    private List<CartItemVo> getCartItems(String userKey) {
        //获取购物车里面的所有商品
        BoundHashOperations<String, String, String> operations = stringRedisTemplate.boundHashOps(userKey);
        List<String> values = operations.values();
        if (values != null && !values.isEmpty()) {
            return values.stream().map(
                    str -> JSON.parseObject(str, CartItemVo.class)).toList();
        }
        return null;
    }

    /**
     * 清空购物车的数据
     *
     * @param userKey
     */
    private void clearCartInfo(String userKey) {
        stringRedisTemplate.delete(userKey);
    }

    /**
     * 修改商品状态
     *
     * @param skuId
     * @param check
     */
    @Override
    public void checkItem(Long skuId, Integer check) {

        // 勾选是单个字段的幂等写（最终值），并发最多是"谁后写谁生效"，不用加锁

        //查询购物车里面的商品
        CartItemVo cartItem = getCartItem(skuId);
        //修改商品状态
        cartItem.setCheck(check == 1);

        //序列化存入redis中
        String redisValue = JSON.toJSONString(cartItem);

        BoundHashOperations<String, String, String> cartOps = getCartOps();
        cartOps.put(skuId.toString(), redisValue);

    }

    /**
     * 全选 / 取消全选
     *
     * <p>逐个改写 hash 里每个购物项的 check 字段。
     * 登录用户的临时购物车在 {@link #getCart()} 里已经合并过了（点全选一定是从购物车页发起的），
     * 所以这里直接操作当前用户的那个 key 就够了
     *
     * @param checked 1 全选，0 取消全选
     */
    @Override
    public void checkAll(Integer checked) {

        // 要先读全部、再逐条写，中间被别人插一脚就乱了，加锁
        withCartLock(getCartOps().getKey(), () -> {

            BoundHashOperations<String, String, String> cartOps = getCartOps();
            // values() 是一次性取回的列表，边遍历边 put 不会再读到刚写进去的数据
            List<String> values = cartOps.values();
            if (values == null || values.isEmpty()) {
                return;
            }

            boolean check = checked == 1;
            for (String value : values) {
                CartItemVo cartItem = JSON.parseObject(value, CartItemVo.class);
                cartItem.setCheck(check);
                cartOps.put(cartItem.getSkuId().toString(), JSON.toJSONString(cartItem));
            }

        });

    }

    /**
     * 修改购物项数量
     *
     * @param skuId
     * @param num
     */
    @Override
    public void changeItemCount(Long skuId, Integer num) {

        // 同样是"读 → 改 → 写"，加锁
        withCartLock(getCartOps().getKey(), () -> {

            //查询购物车里面的商品
            CartItemVo cartItem = getCartItem(skuId);
            cartItem.setCount(num);

            BoundHashOperations<String, String, String> cartOps = getCartOps();
            //序列化存入redis中
            String redisValue = JSON.toJSONString(cartItem);
            cartOps.put(skuId.toString(), redisValue);

        });
    }

    /**
     * 删除购物项
     *
     * @param skuId
     */
    @Override
    public void deleteIdCartInfo(Integer skuId) {

        BoundHashOperations<String, String, String> cartOps = getCartOps();
        cartOps.delete(skuId.toString());
    }

    /**
     * 批量删除购物项（删除选中的商品）
     *
     * @param skuIds 要删除的 skuId 集合
     */
    @Override
    public void deleteCartItems(List<Integer> skuIds) {

        if (skuIds == null || skuIds.isEmpty()) {
            return;
        }

        BoundHashOperations<String, String, String> cartOps = getCartOps();
        skuIds.forEach(skuId -> cartOps.delete(skuId.toString()));
    }

    /**
     * 清除下柜商品：查库存，库存为 0 的购物项直接从购物车里删掉
     */
    @Override
    public void clearNoStockItems() {
        BoundHashOperations<String, String, String> cartOps = getCartOps();
        Set<String> fields = cartOps.keys();
        if (fields != null && !fields.isEmpty()) {
            List<Long> skuIds = fields.stream().map(Long::parseLong).toList();
            Map<Long, Boolean> stockMap = wareSkuFeignClient.hasStock(skuIds);
            stockMap.forEach((skuId, hasStock) -> {
                if (!hasStock) {
                    cartOps.delete(skuId.toString());
                }
            });
        }

    }

    /**
     * BoundHashOperations的get操作
     */
    public void boundHashOperationsTest() {
        BoundHashOperations<String, String, String> cartOps = getCartOps();

        // 返回 Map<String, String>，包含所有 field 和 value
        Map<String, String> entries = cartOps.entries();
        if (entries != null) {
            entries.forEach((field, value) -> {
                System.out.println(field + " = " + value);
            });
        }
        // 所有 field（HKEYS）
        Set<String> fields = cartOps.keys();
        // 所有 value（HVALS），顺序与 keys() 不保证对应
        List<String> values = cartOps.values();
    }

    /**
     * 获取购物车商品类型数量
     *
     * <p>只读当前购物车的 hash：不走临时车合并、也不查库存，不做太重的事
     */
    @Override
    public Long getCartCountType() {
        return getCartOps().size();
    }

    @Override
    public List<CartItemVo> getUserCartItems() {
        UserInfoTo userInfoTo = UserThreadLocal.get();
        if (userInfoTo.getUserId() == null) {
            // 用户未登录无法结算购物车
            return List.of();
        } else {
            List<CartItemVo> cartItemVoList = getCartItems(CART_PREFIX + userInfoTo.getUserId());
            if (cartItemVoList == null || cartItemVoList.isEmpty()) {
                // 购物车无商品
                return List.of();
            } else {
                // 待删除（下架）商品
                List<String> deleteItems = new ArrayList<>();
                // 待更新（价格变动）商品
                List<CartItemVo> updateItems = new ArrayList<>();

                List<Long> skuIds = cartItemVoList.stream().map(CartItemVo::getSkuId).toList();
                // 此处为了简单处理只查询了价格和库存，实际应该更新全部数据！
                // 实时查询最新价格
                Map<Long, BigDecimal> priceMap = productFeignClient.getPriceBatch(skuIds);
                // 实时查询是否有货
                Map<Long, Long> stockMap = wareSkuFeignClient.getStockNums(skuIds);

                List<CartItemVo> cartItemVos = cartItemVoList.stream()
                        // 只返回购物车中选中商品
                        .filter(CartItemVo::getCheck)
                        // 实时查询商品价格（商品价格可能随时变更）
                        .filter(item -> {
                            BigDecimal price = priceMap.get(item.getSkuId());
                            Long stockCount = stockMap.get(item.getSkuId());
                            if (price == null || stockCount == 0) {
                                // 商品下架了或没货了，自动取消该商品结算（可选：从购物车中删除）
                                deleteItems.add(item.getSkuId().toString());
                                return false;
                            } else {
                                // 商品未下架，更新商品价格（可选：购物车更新价格）
                                if (price.compareTo(item.getPrice()) != 0) {
                                    item.setPrice(price);
                                    // 同时设置库存数供页面显示
                                    item.setStock(stockCount);
                                    updateItems.add(item);
                                }
                                return true;
                            }
                        })
                        .toList();

                // 开始更新购物车
                BoundHashOperations<String, String, String> cartOps = getCartOps();
                deleteItems.forEach(skuId -> {
                    Long deleted = cartOps.delete(skuId);
                    if (deleted == null || deleted == 0) {
                        log.warn("购物车里没有 skuId={} 这一项，删除没生效", skuId);
                    } else {
                        log.info("购物车 skuId={} 商品删除成功", skuId);
                    }
                });
                updateItems.forEach(sku -> cartOps.put(sku.getSkuId().toString(), JSON.toJSONString(sku)));

                return cartItemVos;
            }
        }
    }

}
