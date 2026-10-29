-- 隐藏“销售管理”导航；不删除商品销售订单或角色权限。
UPDATE `sys_permission` SET `available` = 0 WHERE `id` = 4 AND `type` = 'menu';
