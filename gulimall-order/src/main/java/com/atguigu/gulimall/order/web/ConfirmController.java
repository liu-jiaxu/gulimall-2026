package com.atguigu.gulimall.order.web;

import com.atguigu.gulimall.api.member.MemberReceiveAddressVo;
import com.atguigu.gulimall.api.order.vo.OrderConfirmVo;
import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import com.atguigu.gulimall.api.order.vo.OrderSubmitVo;
import com.atguigu.gulimall.api.order.vo.OrderVo;
import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.common.constant.WareConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.feign.MemberReceiveAddressFeignClient;
import com.atguigu.gulimall.order.feign.WareFeignClient;
import com.atguigu.gulimall.order.service.OrderService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>订单确认页</b>
 * <p><b>补充：订单数据一致性保证</b>
 * <pre>{@code
 *   1.购物车商品进订单页面时，必须全量查询每一件商品的全部信息供用户确认
 *   2.提交订单到支付页面时，必须二次查询关键数据（价格，库存等）
 *     并且要看看用户是否修改了购物车商品勾选，最终要按照购物车勾选商品为依据，订单页面的商品数据只是参考
 *     实际做法是校验购物车和订单页面商品最终价格，不一致就跳转到订单页面再提示用户
 *   3.订单超时自动取消 & 数据库库存预扣减 设置订单支付时间并防止超卖
 *     3.1 例如1万件商品，1s内1千万用户同时下单1件：
 *       UPDATE stock SET count = count - 1 WHERE sku_id = 1 AND count >= 1
 *       必须加库存数量判断count >= 1防止超卖
 *     3.2 提交订单后的操作：
 *       3.2.1 redis
 *         因为订单高峰期高并发可能打崩数据库，因此这个预扣减要放到redis里面做
 *         redis获取数据库实际库存（这一步商品上架时就做），只要下单就预扣减redis库存，
 *         用lua脚本会拦截很多无效订单（库存不足的990万订单都会拦截）
 *       3.2.2 数据库
 *         写订单表 + 远程锁库存用 Seata @GlobalTransactional 包住；本地多表仍用 @Transactional
 *         ware 锁库存时只把解锁消息写进本地消息表 mq_message，不在全局事务里发送，
 *         避免 confirm 回调提前 UPDATE message_status 导致 undo_log 变脏
 *       3.2.3 用户
 *         数据库订单记录和扣库存成功后，通知用户下单成功，跳转支付页面
 *         redis/数据库扣库存失败后（DB失败redis还要回滚扣库存操作），提示用户哪些库存不足，是否删除
 *     3.3 等待用户支付，因为所有用户不可能同1s完成支付，因此这里不用考虑高并发
 *       3.3.1 用户支付成功：
 *             支付宝 notify / return -> 订单 status 0 -> 1 -> 写 oms_payment_info
 *             -> 调 ware.markPaid 把锁定库存转真实扣减 -> 删除购物车已下单商品
 *       3.3.2 用户主动取消：
 *             订单 status 0 -> 4 + 回滚库存，同时调 alipay.trade.close 关闭支付宝交易
 *       3.3.3 支付超时：
 *             OrderTimeoutTask 定时把 status 0 -> 4，并调 alipay.trade.close；
 *             库存由 ware 的延时消息释放，失败由 StockReleaseTask 兜底
 *       3.3.4 支付超时后又付款：
 *             支付宝 notify 发现订单已是 status=4，
 *             调 alipay.trade.refund 按订单实付金额全额原路退款，
 *             写 oms_refund_info；退款失败由 RefundRetryTask 定时重试
 *   4.支付成功瞬间，可再次查询商品价格
 *     4.1 若商品降价，可以给用户 48h退款选择 或 差额优惠券 优化用户体验，
 *     4.2 若商品涨价，必须静默处理，不提示用户，只提示商家亏损情况
 *   5.幂等问题
 *     下单、支付操作必须幂等，用防重令牌实现 见confirmOrder实现
 *   6.MQ消息队列
 *     6.1 下单成功后其它异步操作：加积分、发提示消息、记日志等可以走 MQ
 *     6.2 库存解锁延时消息
 *       6.2.1 ware 锁库存时，在本地事务里 INSERT mq_message（和库存工作单同库同事务），
 *             但【不立即发送】；全局事务回滚时消息行随事务一起删除
 *       6.2.2 全局事务提交后1分钟再向延时队列送信，最终延时队列在 30 - 1 min后收信处理
 *       6.2.3 MQ收信消费：
 *             工作单仍是“已锁定” -> 解锁库存
 *             工作单已“已扣减”（支付成功）或“已解锁”（取消/超时处理过）-> 跳过
 *     6.3 订单超时关闭由 order 的定时任务负责，MQ 消费者只负责释放库存
 *     6.4 用户主动取消：
 *           订单改状态+回滚库存 库存回滚失败由库存延时消息兜底
 *     6.5 清除 redis 已下单购物车商品等操作支付成功后立即做，不进 MQ 异步
 *   7.服务宕机问题 见StockReconcileTask定时任务
 *     7.1 为防止redis或数据库扣库存时突然宕机导致库存不一致，可在每天凌晨开启定时任务判断库存，不一致就改redis
 *     7.2 为了防止下单redis扣库存，数据库正在扣库存却突然开启了定时任务
 *       7.2.1 宁可少卖绝不超卖：redis的库存可以比DB少
 *         比如DB剩5件，redis剩0件。不能把redis改成5，因为可能这5件就是用户刚刚redis预扣，还没同步到DB的。
 *       7.2.2 分片对账 + 分布式锁：开多次定时任务每分钟只修改部分商品库存 + 下单定时任务加分布式锁
 *         可以快速修改，并保证下单和改库存不能同时进行
 * }</pre>
 */
@Controller
public class ConfirmController {

    @Autowired
    OrderService orderService;
    @Autowired
    WareFeignClient wareFeignClient;
    @Autowired
    MemberReceiveAddressFeignClient memberReceiveAddressFeignClient;
    @Autowired
    RedissonClient redissonClient;

    @GetMapping("/confirm.html")
    public String confirm(Model model) {
        OrderConfirmVo orderConfirmVo = orderService.confirmOrder();
        model.addAttribute("orderConfirmData", orderConfirmVo);

        // 页面算运费只用得上「商品id + 件数 + 重量」，这里先挑成一份精简数据交给页面。
        // 好处：页面上内联的 JSON 小，前端回传时也不会把图片、标题、销售属性一起带上来。
        List<OrderItemVo> items = orderConfirmVo.getItems();
        model.addAttribute("fareItems", items == null ? List.of() : items.stream()
                .map(item -> new FareRequestVo.Item(item.getSkuId(), item.getCount(), item.getWeight()))
                .toList());

        return "confirm";
    }

    /**
     * 切换收货地址后重新计算运费与时间（由确认页的 ajax 调用）
     *
     * <p><b>加了本地缓存</b>：同一「收货地址 + 商品清单」算出来的运费是固定的，
     * 没必要每次都 order → ware → 百度 走一遍。key 就是整个请求对象 ——
     * 它是 record，equals/hashCode 按字段生成，所以地址或商品一变 key 自然就变了，
     * 只有新增/修改地址这种"查询条件真的变了"的情况才会重新查询。
     *
     * <p>{@code sync = true}：缓存未命中时只放一个线程进去加载，其余请求等它的结果，
     * 避免同一时刻多个请求一起打到 ware（和 product 的分类缓存一个套路）。
     *
     * <p>
     * <b>TODO ⚠️ 重要前提：当前是单机部署，用 Caffeine 本地缓存没有任何问题；一旦切换为分布式（多实例）部署，必须把缓存改为 Redis。</b>
     * 因为本地缓存是每个实例各存一份 —— A 实例提交订单时清掉的只是它自己的缓存，B 实例还留着旧数据，用户会被路由到 B 时看到过期的运费。
     *
     * <p>JMeter缓存测试：1次请求后，1s200请求测试（全部缓存）
     * <p><img src="doc-files/运输信息查询.png" alt="运输信息查询" width="720">
     */
    @PostMapping("/fare")
    // @ResponseBody 是给 AJAX 请求用的：它告诉 Spring MVC “把方法返回值直接写进 HTTP 响应体”，而不是把返回值当成 Thymeleaf 视图名去渲染页面。
    @ResponseBody
    @Cacheable(cacheNames = "fare", key = "#vo", sync = true)
    public R fare(@RequestBody FareRequestVo vo) {
        return wareFeignClient.getFare(vo);
    }

    /** 提交订单：加库存读锁（多个下单可并发，只与管理员改库存互斥） */
    @PostMapping("/submitOrder")
    @ResponseBody
    @CacheEvict(cacheNames = "fare", allEntries = true)
    public String submitOrder(OrderSubmitVo orderSubmitVo) {
        RLock lock = redissonClient.getReadWriteLock(WareConstant.STOCK_LOCK).readLock();
        boolean locked = false;
        try {
            // 最多等 3 秒；拿到后最多持有 30 秒，防止持锁线程异常退出后锁不释放
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (!locked) {
                return toTradeWithError(BizCodeEnum.ORDER_STOCK_BUSY.getMsg());
            }

            // 真正的下单逻辑（第 2、3 步见 OrderServiceImpl#submitOrder）
            try {
                OrderVo orderVo = orderService.submitOrder(orderSubmitVo);
                // 正常下单返回支付页地址，前端拿到后 window.location.href 跳过去
                return "/pay.html?orderSn=" + orderVo.getOrderSn();
            } catch (RRException e) {
                // 业务异常的文案都是给人看的（库存不足、价格变动……），直接带给前端弹窗
                return toTradeWithError(e.getMsg());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return toTradeWithError(BizCodeEnum.ORDER_SUBMIT_INTERRUPTED.getMsg());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 拼一个带错误信息的跳转地址，前端 ajax 拿到后弹窗提示
     *
     * <p>用 {@link URLEncoder} 编码：错误文案里可能有中文、空格、&amp; 等字符，
     * 不编码拼进 query 会把 URL 结构撑坏。
     */
    private String toTradeWithError(String message) {
        return "toTrade?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    /**
     * 新增收货地址（确认页弹窗提交）
     *
     * <p>前端只跟 order 打交道，这里 Feign 转给 member。
     * memberId 由 member 从会话里取，前端传了也不作数。
     * <p>同时清掉运费缓存：地址变了，之前算出来的运费就不作数了。
     */
    @PostMapping("/address/save")
    @ResponseBody
    @CacheEvict(cacheNames = "fare", allEntries = true)
    public R saveAddress(@RequestBody MemberReceiveAddressVo address) {
        return memberReceiveAddressFeignClient.saveAddress(address);
    }

    /**
     * 修改收货地址（确认页弹窗提交，带 id）
     *
     * <p>归属校验在 member 侧做：它会先按 id 查出这条地址、核对是不是当前登录用户的。
     * <p>⚠️ 这里必须清运费缓存：缓存 key 是 {@code FareRequestVo}（里面只有 addrId），
     * <b>改地址时 addrId 不变</b>，不清缓存就会一直命中改之前算出来的运费。
     */
    @PostMapping("/address/update")
    @ResponseBody
    @CacheEvict(cacheNames = "fare", allEntries = true)
    public R updateAddress(@RequestBody MemberReceiveAddressVo address) {
        return memberReceiveAddressFeignClient.updateAddress(address);
    }

}
