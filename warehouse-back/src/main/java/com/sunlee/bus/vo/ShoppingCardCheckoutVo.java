package com.sunlee.bus.vo;

import com.sunlee.bus.entity.Retail;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ShoppingCardCheckoutVo {
    private BigDecimal expectedRate;
    private List<Retail> items;
}
