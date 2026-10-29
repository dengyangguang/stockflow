-- 已有数据库只需执行一次；不会删除或修改现有商品数据。
ALTER TABLE `bus_goods`
  ADD COLUMN `barcode` varchar(64) DEFAULT NULL COMMENT '商品包装条码',
  ADD UNIQUE KEY `uk_bus_goods_barcode` (`barcode`);
