-- 参考成本价默认仅超级管理员可见，其他角色需在角色管理中手动授权。
INSERT INTO `sys_permission` (`pid`, `type`, `title`, `percode`, `icon`, `href`, `target`, `open`, `ordernum`, `available`)
SELECT 9, 'permission', '查看商品成本价', 'goods:cost:view', NULL, NULL, NULL, 0, 87, 1
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_permission` WHERE `percode` = 'goods:cost:view'
);
