ALTER TABLE bus_retail
  ADD COLUMN member_id int DEFAULT NULL COMMENT '余额支付会员ID' AFTER location_id,
  ADD INDEX idx_retail_member_id (member_id);
