-- 已有数据库只需执行一次；保留现有商品和进货记录。
ALTER TABLE `bus_goods`
  ADD COLUMN `cost_price` decimal(12,2) DEFAULT NULL COMMENT '商品参考进货成本价；实际成本保存在进货单';
