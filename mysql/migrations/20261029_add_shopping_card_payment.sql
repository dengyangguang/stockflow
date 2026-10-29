CREATE TABLE IF NOT EXISTS bus_retail_card_config (
  id tinyint NOT NULL PRIMARY KEY,
  rate_percent decimal(5,2) NOT NULL COMMENT '超市购物卡扣点百分比',
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bus_retail_card_settlement (
  order_no varchar(50) NOT NULL PRIMARY KEY,
  rate_percent decimal(5,2) NOT NULL,
  gross_amount decimal(12,2) NOT NULL,
  fee_amount decimal(12,2) NOT NULL,
  net_amount decimal(12,2) NOT NULL,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
