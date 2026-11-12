-- ============================================================
-- 管理端 AI 助手菜单
-- 执行：mysql -uroot -p < sql/gulimall_ai_menu.sql
-- 说明：即使不执行，也可通过 http://admin.gulimall.vip/#/ai/chat 直接访问；
--       执行后左侧菜单会出现「AI 助手 -> AI 聊天」。
-- ============================================================

USE `gulimall_admin`;

-- 1. 顶级目录：AI 助手
--    icon 必须对应 src/icons/svg/icon-ai.svg，写 el-icon-xxx 侧边栏是渲染不出来的
INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT 0, 'AI 助手', NULL, NULL, 0, 'ai', 9
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` WHERE `parent_id` = 0 AND `name` = 'AI 助手'
);

-- 1.1 已经插入过的旧菜单：把不存在的 el-icon-xxx 改成 SVG 图标名
UPDATE `sys_menu` SET `icon` = 'ai'
WHERE `parent_id` = 0 AND `name` = 'AI 助手';

-- 2. 子菜单：AI 聊天
SET @ai_parent = (SELECT `menu_id` FROM `sys_menu` WHERE `parent_id` = 0 AND `name` = 'AI 助手' LIMIT 1);

INSERT INTO `sys_menu` (`parent_id`, `name`, `url`, `perms`, `type`, `icon`, `order_num`)
SELECT @ai_parent, 'AI 聊天', 'ai/chat', NULL, 1, 'ai-chat', 1
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` WHERE `parent_id` = @ai_parent AND `url` = 'ai/chat'
);

-- 2.1 已经插入过的旧子菜单：同样改成 SVG 图标名
UPDATE `sys_menu` SET `icon` = 'ai-chat'
WHERE `parent_id` = @ai_parent AND `url` = 'ai/chat';

-- 3. 给现有角色授权（表里没有角色时这条不插入任何行）
SET @ai_child = (SELECT `menu_id` FROM `sys_menu` WHERE `parent_id` = @ai_parent AND `url` = 'ai/chat' LIMIT 1);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT r.`role_id`, @ai_child
FROM `sys_role` r
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_role_menu` rm WHERE rm.`role_id` = r.`role_id` AND rm.`menu_id` = @ai_child
);
