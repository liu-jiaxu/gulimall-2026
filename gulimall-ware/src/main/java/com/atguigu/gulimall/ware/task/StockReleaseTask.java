package com.atguigu.gulimall.ware.task;

import com.atguigu.gulimall.ware.entity.WareOrderTaskEntity;
import com.atguigu.gulimall.ware.service.StockReleaseExecutor;
import com.atguigu.gulimall.ware.service.WareOrderTaskService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

import static com.atguigu.gulimall.ware.constant.WareConstant.BIZ_TYPE_NORMAL;
import static com.atguigu.gulimall.ware.constant.WareConstant.TASK_STATUS_LOCKED;

/**
 * 库存解锁兜底任务：扫「已锁定且超过解锁时间」的工作单。
 *
 * <p>MQ 可能丢消息、消费者可能一直失败，光靠延时消息不保险，所以每分钟再扫一次。
 * 多实例部署时每个实例都会扫到同一批数据，但 {@code releaseStock} 里的条件更新
 * 保证只有一次真正解锁，不需要额外加分布式锁。
 *
 * <p>比 MQ 延时多等 {@code grace-ms}，让正常消息先飞一会儿；
 * 真正兜底的场景晚几分钟解锁没有影响。
 */
@Slf4j
@Component
public class StockReleaseTask {

    /** 每批处理的工作单数量，避免一次扫太多 */
    private static final int BATCH_SIZE = 100;

    @Autowired
    private WareOrderTaskService wareOrderTaskService;

    @Autowired
    private StockReleaseExecutor stockReleaseExecutor;

    /** 下单后多久算超时，必须和发消息用的 delay-ms 一致 */
    @Value("${gulimall.ware.stock-release.delay-ms}")
    private long delayMs;

    /** 兜底宽限期 */
    @Value("${gulimall.ware.stock-release.grace-ms}")
    private long graceMs;

    /** 每分钟跑一次 */
    @Scheduled(cron = "0 * * * * ?")
    public void releaseExpiredTasks() {
        long timeoutMillis = delayMs + graceMs;
        Date deadline = new Date(System.currentTimeMillis() - timeoutMillis);

        List<WareOrderTaskEntity> tasks = wareOrderTaskService.list(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("task_status", TASK_STATUS_LOCKED)
                        // 秒杀工作单由 seckill 对账任务处理，这里只兜底普通订单
                        .eq("biz_type", BIZ_TYPE_NORMAL)
                        .le("create_time", deadline)
                        .orderByAsc("id")
                        .last("LIMIT " + BATCH_SIZE));

        if (tasks.isEmpty()) {
            return;
        }

        log.info("[stock-release-task] 发现 {} 个超时未处理的库存工作单", tasks.size());
        for (WareOrderTaskEntity task : tasks) {
            try {
                stockReleaseExecutor.release(task.getId());
                log.info("[stock-release-task] 兜底解锁工作单 {}（订单 {}）", task.getId(), task.getOrderSn());
            } catch (Exception e) {
                // 单个失败不影响其它工作单，下一分钟还会再扫到
                log.error("[stock-release-task] 兜底解锁工作单 {} 失败", task.getId(), e);
            }
        }
    }

}
