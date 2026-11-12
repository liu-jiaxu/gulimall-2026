-- ============================================================
-- Seata AT 模式 undo_log 表
-- ------------------------------------------------------------
-- 在【每一个】参与全局事务的数据库执行：
--   gulimall_oms（order）
--   gulimall_wms（ware）
-- 以后 member / coupon 等模块参与全局事务时，各自的库也要建。
--
-- 已有旧版 undo_log 时 CREATE IF NOT EXISTS 不会改表；
-- 旧表只要字段兼容就可以继续用。
-- ============================================================

CREATE TABLE IF NOT EXISTS `undo_log` (
  `branch_id` bigint NOT NULL COMMENT 'branch transaction id',
  `xid` varchar(128) NOT NULL COMMENT 'global transaction id',
  `context` varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob NOT NULL COMMENT 'rollback info',
  `log_status` int NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created` datetime(6) NOT NULL COMMENT 'create datetime',
  `log_modified` datetime(6) NOT NULL COMMENT 'modify datetime',
  `ext` varchar(100) DEFAULT NULL COMMENT 'ext info',
  `id` bigint NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AT transaction mode undo table';
