1.应用启动加载配置类（gulimall-rabbitmq 共通模块，ware 引用后自动生效）
  1.RabbitMqAutoConfiguration通过META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports自动装配
  2.RabbitConverterConfig配置消息转换器Class->JSON + 反序列化白名单
  3.RabbitListenerContainerConfig配置手动收信模式（manual ack）
  4.RabbitQueueConfig加载重试交换机、死信交换机/队列、库存业务交换机/队列
    ·gulimall-rabbitmq-retry-exchange：x-delayed-message延时交换机
    ·gulimall-rabbitmq-dead-exchange / gulimall-rabbitmq-dead-queue：死信归档
    ·gulimall-stock-exchange / gulimall-stock-release-queue：库存解锁
    ·每个业务队列绑两次：业务交换机（正常入口）+ 重试交换机（延时/重试回路）
  5.RabbitCallbackConfig.initRabbitTemplate配置发送端confirm/return检查
  6.RabbitMqAutoConfiguration上的@MapperScan注册MqMessageDao / MqDeadMessageDao
  7.RabbitMqAutoConfiguration上的@EnableScheduling启动补偿定时任务

2.发送流程（MqMessageServiceImpl.sendReliable）
  1.sendReliable(exchange, routingKey, payload[, delayMillis])
  2.消息落库MqMessageEntity（messageId + retryCount=0 + status=0新建 + delayMillis）
  3.当前在业务事务中时，事务提交后才doSend；业务回滚则消息记录一起回滚
  4.doSend向MQ送信（消息头带mq_message_id；delayMillis>0时带x-delay）
  5.RabbitCallbackConfig.initRabbitTemplate检查送信结果
    5.1.送信成功
      MqMessageServiceImpl.markConfirmed：status=1已送信
    5.2.送信失败
      5.2.1.MqMessageServiceImpl.markFailed：status=2送信失败
      5.2.2.MqMessageCompensateTask.resend加锁每分钟扫一次，最多重发3次
      5.2.3.MqMessageServiceImpl.resendFailedMessages遍历需要重试的消息，重新送信（保留delayMillis）
        5.2.3.1.重发成功 -> 回到5.1等confirm回调
        5.2.3.2.累计重发满3次仍失败
          MqDeadMessageService.archiveFromSendFailure挪进死信表mq_dead_message
          + MqMessageServiceImpl.removeById从本地消息表删除（程序不再管，等人工重放）

3.库存解锁业务（gulimall-ware）
  1.WareSkuServiceImpl.lockStock：同一个本地事务
    1.1.写工作单：wms_ware_order_task（task_status=1已锁定）
    1.2.逐个sku锁库存：wms_ware_sku.stock_locked + n（按仓库逐个锁）
    1.3.写明细：wms_ware_order_task_detail（sku + 仓库 + 数量，lock_status=1）
    1.4.sendReliable写mq_message（to_exchange=重试交换机，routing_key=stock.release，delay_millis=30分钟）
  2.事务提交后，MqMessageServiceImpl把「库存解锁」延时消息投到gulimall-rabbitmq-retry-exchange，x-delay=30分钟
  3.30分钟后消息进入gulimall-stock-release-queue
  4.StockReleaseListener收信
    4.1.StockReleaseExecutor.release：先拿库存读锁，再调releaseStock
    4.2.releaseStock：工作单从「已锁定」条件更新成「已解锁」，影响行数0说明已处理过
    4.3.按明细解锁：DB stock_locked - n；明细lock_status=2
    4.4.事务提交后Redis INCRBY n
    4.5.成功 -> channel.basicAck
    4.6.失败 -> MqRetryServiceImpl.handleFailure（见第4节）
  5.StockReleaseTask兜底：每分钟扫「task_status=1且超过30分钟 + 5分钟宽限」的工作单，调StockReleaseExecutor.release
  6.StockReleaseExecutor解锁时持有和下单同一把库存读锁，避免和对账写锁交错

4.消费流程（StockReleaseListener / MqRetryServiceImpl）
  1.收信成功
    ack + 业务处理，mq队列删除该消息，流程结束
  2.收信失败（比如库存解锁失败）
    MqRetryServiceImpl.handleFailure开始重试
    2.1.还能重试（消息头没有mq_retry_count时按0算，0/1/2算还能重试）
      delayFor算延迟 + republishWithDelay向重试交换机送信
      带x-delay，10s/30s/60s延迟到期后回到业务队列重新收信
    2.2.重试次数用尽（已重试3次）
      2.2.1.publishToDeadLetter向死信交换机送信
      2.2.2.MqDeadMessageListener.onDeadMessage死信队列收信
      2.2.3.MqDeadMessageServiceImpl.archive落库死信表mq_dead_message
      2.2.4.MqDeadMessageListener将死信从死信队列删除，流程结束
    2.3.向交换机送信本身失败（Broker连不上、延迟插件没装、basicAck抛异常）
      nackToDeadLetter -> channel.basicNack(tag, false, false)
      消息不回原队列，走业务队列配的x-dead-letter-exchange直接进死信队列
    2.4.兜底里的兜底（死信归档失败：数据库挂了）
      MqDeadMessageListener不签收 -> 消息留在死信队列

5.支付成功（gulimall-ware）
  1.order支付成功调用POST /ware/waresku/markPaid?orderSn=xxx
  2.WareSkuServiceImpl.deductLockedStock
    2.1.工作单task_status从1已锁定 -> 3已扣减
    2.2.明细lock_status从1 -> 3
    2.3.DB stock - n、stock_locked - n（可用库存不变）
    2.4.Redis下单时已预扣，不再变更

6.死信重发流程（人工处理死信）
  1.MqDeadMessageServiceImpl.replayAll遍历死信表，逐条重放
    1.1.replay把记录还原成消息
        清掉mq_retry_count / mq_fail_reason / mq_origin_* / x-delay
        补回contentType
        用rabbitTemplate.send发原始消息，不走convertAndSend
    1.2.投回原始交换机/路由键 -> 回到业务队列被StockReleaseListener重新消费
    1.3.重放成功 -> removeById从死信表删除
  （单条重放失败只记日志、记录保留，不影响整批）
