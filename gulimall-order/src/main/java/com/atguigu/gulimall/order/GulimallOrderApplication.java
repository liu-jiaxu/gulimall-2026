package com.atguigu.gulimall.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <b>使用RabbitMQ</b>
 * <pre>{@code
 * 1、引入amqp场景;RabbitAutoConfiguration就会自动生效
 * 2、给容器中自动配置了
 *      RabbitTemplate、AmqpAdmin、CachingConnectionFactory、RabbitMessagingTemplate
 * 3、收信
 *     `@RabbitListener(queues = MqConstants.STOCK_RELEASE_QUEUE)
 *     `@RabbitHandler匹配不同类对象
 * }</pre>
 *
 * <p><b>Seata 控制分布式事务（Seata 2.x + spring-cloud-starter-alibaba-seata）</b>
 * <pre>{@code
 *  1）、每个参与事务的数据库都要有 undo_log 表，AT 模式靠它记录回滚日志
 *  2）、安装事务协调器 TC：seata-server（见 deploy/docker-compose.yaml）
 *  3）、客户端整合（order / ware 引入 starter 即可）：
 *      1、数据源不用手写 DataSourceProxy，starter 自动代理：
 *          seata.enable-auto-data-source-proxy: true
 *      2、Seata 2.x 已废弃 registry.conf / file.conf，
 *          注册中心、事务分组等全部写在 seata.* 配置里（见 config/common.yaml）
 *      3、客户端 tx-service-group、客户端 vgroup-mapping、
 *          服务端 vgroup-mapping 三处必须一致（当前都是 gulimall_tx_group -> default）
 *  4）、给分布式大事务的入口标注 @GlobalTransactional（如 OrderServiceImpl#submitOrder）
 *  5）、参与方的小事务用 @Transactional；ware 侧不加 @GlobalTransactional，
 *      它的分支事务由数据源代理自动注册
 *  }</pre>
 */
// 开启 Spring Cache 注解驱动（@Cacheable / @CacheEvict），
// 缓存实现由 yml 里的 spring.cache.type=caffeine 决定（本地内存，不进 Redis）
@EnableCaching
@EnableScheduling
@MapperScan("com.atguigu.gulimall.order.dao")
@EnableDiscoveryClient
@EnableFeignClients
@SpringBootApplication
public class GulimallOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(GulimallOrderApplication.class, args);
    }

}
