drop table if exists sms_coupon;

drop table if exists sms_coupon_history;

drop table if exists sms_coupon_spu_category_relation;

drop table if exists sms_coupon_spu_relation;

drop table if exists sms_home_adv;

drop table if exists sms_home_subject;

drop table if exists sms_home_subject_spu;

drop table if exists sms_member_price;

drop table if exists sms_seckill_promotion;

drop table if exists sms_seckill_session;

drop table if exists sms_seckill_sku_notice;

drop table if exists sms_seckill_sku_relation;

drop table if exists sms_sku_full_reduction;

drop table if exists sms_sku_ladder;

drop table if exists sms_spu_bounds;

/*==============================================================*/
/* Table: sms_coupon                                            */
/*==============================================================*/
create table sms_coupon
(
   id                   bigint not null auto_increment comment 'id',
   coupon_type          tinyint(1) comment '优惠卷类型[0->全场赠券；1->会员赠券；2->购物赠券；3->注册赠券]',
   coupon_img           varchar(2000) comment '优惠券图片',
   coupon_name          varchar(100) comment '优惠卷名字',
   num                  int comment '数量',
   amount               decimal(18,4) comment '金额',
   per_limit            int comment '每人限领张数',
   min_point            decimal(18,4) comment '使用门槛',
   start_time           datetime comment '开始时间',
   end_time             datetime comment '结束时间',
   use_type             tinyint(1) comment '使用类型[0->全场通用；1->指定分类；2->指定商品]',
   note                 varchar(200) comment '备注',
   publish_count        int(11) comment '发行数量',
   use_count            int(11) comment '已使用数量',
   receive_count        int(11) comment '领取数量',
   enable_start_time    datetime comment '可以领取的开始日期',
   enable_end_time      datetime comment '可以领取的结束日期',
   code                 varchar(64) comment '优惠码',
   member_level         tinyint(1) comment '可以领取的会员等级[0->不限等级，其他-对应等级]',
   publish              tinyint(1) comment '发布状态[0-未发布，1-已发布]',
   primary key (id)
);

alter table sms_coupon comment '优惠券信息';

/*==============================================================*/
/* Table: sms_coupon_history                                    */
/*==============================================================*/
create table sms_coupon_history
(
   id                   bigint not null auto_increment comment 'id',
   coupon_id            bigint comment '优惠券id',
   member_id            bigint comment '会员id',
   member_nick_name     varchar(64) comment '会员名字',
   get_type             tinyint(1) comment '获取方式[0->后台赠送；1->主动领取]',
   create_time          datetime comment '创建时间',
   use_type             tinyint(1) comment '使用状态[0->未使用；1->已使用；2->已过期]',
   use_time             datetime comment '使用时间',
   order_id             bigint comment '订单id',
   order_sn             bigint comment '订单号',
   primary key (id)
);

alter table sms_coupon_history comment '优惠券领取历史记录';

/*==============================================================*/
/* Table: sms_coupon_spu_category_relation                      */
/*==============================================================*/
create table sms_coupon_spu_category_relation
(
   id                   bigint not null auto_increment comment 'id',
   coupon_id            bigint comment '优惠券id',
   category_id          bigint comment '产品分类id',
   category_name        varchar(64) comment '产品分类名称',
   primary key (id)
);

alter table sms_coupon_spu_category_relation comment '优惠券分类关联';

/*==============================================================*/
/* Table: sms_coupon_spu_relation                               */
/*==============================================================*/
create table sms_coupon_spu_relation
(
   id                   bigint not null auto_increment comment 'id',
   coupon_id            bigint comment '优惠券id',
   spu_id               bigint comment 'spu_id',
   spu_name             varchar(255) comment 'spu_name',
   primary key (id)
);

alter table sms_coupon_spu_relation comment '优惠券与产品关联';

/*==============================================================*/
/* Table: sms_home_adv                                          */
/*==============================================================*/
create table sms_home_adv
(
   id                   bigint not null auto_increment comment 'id',
   name                 varchar(100) comment '名字',
   pic                  varchar(500) comment '图片地址',
   start_time           datetime comment '开始时间',
   end_time             datetime comment '结束时间',
   status               tinyint(1) comment '状态',
   click_count          int comment '点击数',
   url                  varchar(500) comment '广告详情连接地址',
   note                 varchar(500) comment '备注',
   sort                 int comment '排序',
   publisher_id         bigint comment '发布者',
   auth_id              bigint comment '审核者',
   primary key (id)
);

alter table sms_home_adv comment '首页轮播广告';

/*==============================================================*/
/* Table: sms_home_subject                                      */
/*==============================================================*/
create table sms_home_subject
(
   id                   bigint not null auto_increment comment 'id',
   name                 varchar(200) comment '专题名字',
   title                varchar(255) comment '专题标题',
   sub_title            varchar(255) comment '专题副标题',
   status               tinyint(1) comment '显示状态',
   url                  varchar(500) comment '详情连接',
   sort                 int comment '排序',
   img                  varchar(500) comment '专题图片地址',
   primary key (id)
);

alter table sms_home_subject comment '首页专题表【jd首页下面很多专题，每个专题链接新的页面，展示专题商品信息】';

/*==============================================================*/
/* Table: sms_home_subject_spu                                  */
/*==============================================================*/
create table sms_home_subject_spu
(
   id                   bigint not null auto_increment comment 'id',
   name                 varchar(200) comment '专题名字',
   subject_id           bigint comment '专题id',
   spu_id               bigint comment 'spu_id',
   sort                 int comment '排序',
   primary key (id)
);

alter table sms_home_subject_spu comment '专题商品';

/*==============================================================*/
/* Table: sms_member_price                                      */
/*==============================================================*/
create table sms_member_price
(
   id                   bigint not null auto_increment comment 'id',
   sku_id               bigint comment 'sku_id',
   member_level_id      bigint comment '会员等级id',
   member_level_name    varchar(100) comment '会员等级名',
   member_price         decimal(18,4) comment '会员对应价格',
   add_other            tinyint(1) comment '可否叠加其他优惠[0-不可叠加优惠，1-可叠加]',
   primary key (id)
);

alter table sms_member_price comment '商品会员价格';

/*==============================================================*/
/* Table: sms_seckill_promotion                                 */
/*==============================================================*/
create table sms_seckill_promotion
(
   id                   bigint not null auto_increment comment 'id',
   title                varchar(255) comment '活动标题',
   start_time           datetime comment '开始日期',
   end_time             datetime comment '结束日期',
   status               tinyint comment '上下线状态',
   create_time          datetime comment '创建时间',
   user_id              bigint comment '创建人',
   primary key (id)
);

alter table sms_seckill_promotion comment '秒杀活动';

/*==============================================================*/
/* Table: sms_seckill_session                                   */
/*==============================================================*/
create table sms_seckill_session
(
   id                   bigint not null auto_increment comment 'id',
   promotion_id         bigint comment '秒杀活动id',
   name                 varchar(200) comment '场次名称',
   start_time           datetime comment '开始时间',
   end_time             datetime comment '结束时间',
   status               tinyint(1) comment '启用状态',
   seckill_state        tinyint not null default 0 comment '0未开始 1进行中 2停止中 3已结束 4已取消',
   stop_time            datetime comment '提前下架时间',
   settle_type          tinyint comment '停止后最终状态：1已结束 2已取消',
   create_time          datetime comment '创建时间',
   primary key (id)
);

alter table sms_seckill_session comment '秒杀活动场次';

/*==============================================================*/
/* Table: sms_seckill_sku_notice                                */
/*==============================================================*/
create table sms_seckill_sku_notice
(
   id                   bigint not null auto_increment comment 'id',
   member_id            bigint comment 'member_id',
   sku_id               bigint comment 'sku_id',
   session_id           bigint comment '活动场次id',
   subcribe_time        datetime comment '订阅时间',
   send_time            datetime comment '发送时间',
   notice_type          tinyint(1) comment '通知方式[0-短信，1-邮件]',
   primary key (id)
);

alter table sms_seckill_sku_notice comment '秒杀商品通知订阅';

/*==============================================================*/
/* Table: sms_seckill_sku_relation                              */
/*==============================================================*/
create table sms_seckill_sku_relation
(
   id                   bigint not null auto_increment comment 'id',
   promotion_id         bigint comment '活动id',
   promotion_session_id bigint comment '活动场次id',
   sku_id               bigint comment '商品id',
   ware_id              bigint not null comment '关联时实际锁定的仓库id',
   seckill_price        decimal(10,2) comment '秒杀价格',
   seckill_count        int comment '秒杀总量',
   seckill_limit        int comment '每人限购数量',
   seckill_sort         int comment '排序',
   version              int not null default 0 comment '乐观锁版本',
   primary key (id),
   unique key uk_session_sku (promotion_session_id, sku_id)
);

alter table sms_seckill_sku_relation comment '秒杀活动商品关联';

/*==============================================================*/
/* Table: sms_seckill_order                                     */
/*==============================================================*/
create table sms_seckill_order
(
   id                   bigint not null auto_increment comment 'id',
   order_sn             char(32) not null comment '秒杀订单号，全链路幂等键',
   request_id           varchar(64) not null comment '客户端请求id',
   order_id             bigint comment 'oms_order.id',
   member_id            bigint not null comment '会员id',
   member_username      varchar(200) comment '会员用户名',
   session_id           bigint not null comment '秒杀场次id',
   sku_id               bigint not null comment '商品id',
   sku_name             varchar(255) comment '商品名称快照',
   sku_pic              varchar(500) comment '商品图片快照',
   ware_id              bigint not null comment '关联时锁定的仓库id',
   quantity             int not null comment '购买数量',
   seckill_price        decimal(10,2) not null comment '秒杀单价',
   pay_amount           decimal(18,4) comment '应付金额',
   addr_id              bigint not null comment '收货地址id',
   pay_type             tinyint not null default 1 comment '支付方式',
   status               tinyint not null default 0 comment '0待建单 1已建单 2已释放 3建单失败 4已支付',
   release_mode         tinyint comment '释放方式[1->回秒杀池；2->回普通池]',
   release_reason       varchar(64) comment '释放原因',
   fail_reason          varchar(255) comment '失败原因',
   build_retry_count    int not null default 0 comment '建单补偿次数',
   create_time          datetime not null default current_timestamp comment '创建时间',
   update_time          datetime not null default current_timestamp on update current_timestamp comment '更新时间',
   primary key (id),
   unique key uk_order_sn (order_sn),
   unique key uk_request_id (request_id),
   key idx_status_update (status, update_time),
   key idx_session_sku_status (session_id, sku_id, status),
   key idx_member_status (member_id, status)
);

alter table sms_seckill_order comment '秒杀订单预扣与生命周期';

/*==============================================================*/
/* Table: sms_sku_full_reduction                                */
/*==============================================================*/
create table sms_sku_full_reduction
(
   id                   bigint not null auto_increment comment 'id',
   sku_id               bigint comment 'spu_id',
   full_price           decimal(18,4) comment '满多少',
   reduce_price         decimal(18,4) comment '减多少',
   add_other            tinyint(1) comment '是否参与其他优惠',
   primary key (id)
);

alter table sms_sku_full_reduction comment '商品满减信息';

/*==============================================================*/
/* Table: sms_sku_ladder                                        */
/*==============================================================*/
create table sms_sku_ladder
(
   id                   bigint not null auto_increment comment 'id',
   sku_id               bigint comment 'spu_id',
   full_count           int comment '满几件',
   discount             decimal(4,2) comment '打几折',
   price                decimal(18,4) comment '折后价',
   add_other            tinyint(1) comment '是否叠加其他优惠[0-不可叠加，1-可叠加]',
   primary key (id)
);

alter table sms_sku_ladder comment '商品阶梯价格';

/*==============================================================*/
/* Table: sms_spu_bounds                                        */
/*==============================================================*/
create table sms_spu_bounds
(
   id                   bigint not null auto_increment comment 'id',
   spu_id               bigint,
   grow_bounds          decimal(18,4) comment '成长积分',
   buy_bounds           decimal(18,4) comment '购物积分',
   work                 tinyint(1) comment '优惠生效情况[1111（四个状态位，从右到左）;0 - 无优惠，成长积分是否赠送;1 - 无优惠，购物积分是否赠送;2 - 有优惠，成长积分是否赠送;3 - 有优惠，购物积分是否赠送【状态位0：不赠送，1：赠送】]',
   primary key (id)
);

alter table sms_spu_bounds comment '商品spu积分设置';

/*==============================================================*/
/* RabbitMQ 共通模块：本地消息表（秒杀结算延时消息用）            */
/*==============================================================*/

CREATE TABLE IF NOT EXISTS `mq_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `message_id` char(32) NOT NULL COMMENT '消息唯一ID（UUID去掉横线）',
  `content` text COMMENT '消息体JSON字符串',
  `to_exchange` varchar(255) DEFAULT NULL COMMENT '目标交换机',
  `routing_key` varchar(255) DEFAULT NULL COMMENT '路由键',
  `class_type` varchar(255) DEFAULT NULL COMMENT '消息体全限定类名',
  `message_status` tinyint NOT NULL DEFAULT '0' COMMENT '0-新建待确认 1-已抵达Broker 2-投递失败待补偿',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重发次数',
  `next_retry_time` datetime DEFAULT NULL COMMENT '下次可重发时间',
  `delay_millis` bigint NOT NULL DEFAULT '0' COMMENT '延时毫秒数（0=不延时）',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_status_retry` (`message_status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RabbitMQ本地消息表';

CREATE TABLE IF NOT EXISTS `mq_dead_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `message_id` char(32) DEFAULT NULL COMMENT '关联的原始消息ID',
  `from_exchange` varchar(255) DEFAULT NULL COMMENT '原交换机，人工重放时按这个投回去',
  `routing_key` varchar(255) DEFAULT NULL COMMENT '原路由键',
  `class_type` varchar(255) DEFAULT NULL COMMENT '消息体全限定类名',
  `content` text COMMENT '完整消息体JSON',
  `headers` text COMMENT '完整消息头JSON',
  `fail_reason` varchar(1024) DEFAULT NULL COMMENT '最后一次失败原因',
  `retry_count` int DEFAULT NULL COMMENT '总共重试了几次',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_message_id` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RabbitMQ消费端失败归档表';
