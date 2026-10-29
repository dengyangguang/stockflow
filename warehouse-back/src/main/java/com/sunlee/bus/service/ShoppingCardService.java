package com.sunlee.bus.service;

import com.sunlee.bus.entity.Retail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class ShoppingCardService {
    @Autowired
    private JdbcTemplate jdbc;

    public BigDecimal getRate() {
        try {
            return jdbc.queryForObject("SELECT rate_percent FROM bus_retail_card_config WHERE id = 1", BigDecimal.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public void saveRate(BigDecimal rate) {
        if (rate == null || rate.scale() > 2 || rate.compareTo(BigDecimal.ZERO) < 0
                || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("扣点率必须在0至100之间，最多保留两位小数");
        }
        jdbc.update("INSERT INTO bus_retail_card_config (id, rate_percent) VALUES (1, ?) "
                + "ON DUPLICATE KEY UPDATE rate_percent = VALUES(rate_percent)", rate);
    }

    private BigDecimal gross(List<Retail> items) {
        return items.stream()
                .filter(item -> item.getNumber() != null && item.getNumber() > 0)
                .map(item -> BigDecimal.valueOf(item.getRetailprice()).multiply(BigDecimal.valueOf(item.getNumber())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal fee(BigDecimal gross, BigDecimal rate) {
        return gross.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    public void record(String orderNo, BigDecimal rate, List<Retail> items) {
        BigDecimal gross = gross(items);
        BigDecimal fee = fee(gross, rate);
        jdbc.update("INSERT INTO bus_retail_card_settlement "
                        + "(order_no, rate_percent, gross_amount, fee_amount, net_amount) VALUES (?, ?, ?, ?, ?)",
                orderNo, rate, gross, fee, gross.subtract(fee));
    }

    public void recalculate(String orderNo, List<Retail> remainingItems) {
        BigDecimal rate;
        try {
            rate = jdbc.queryForObject("SELECT rate_percent FROM bus_retail_card_settlement WHERE order_no = ?",
                    BigDecimal.class, orderNo);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("购物卡订单缺少扣点记录: " + orderNo);
        }
        BigDecimal gross = gross(remainingItems);
        BigDecimal fee = fee(gross, rate);
        jdbc.update("UPDATE bus_retail_card_settlement SET gross_amount = ?, fee_amount = ?, net_amount = ? "
                + "WHERE order_no = ?", gross, fee, gross.subtract(fee), orderNo);
    }
}
