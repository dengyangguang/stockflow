package com.sunlee.bus.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sunlee.bus.entity.Retail;
import com.sunlee.bus.entity.Sales;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 统一统计商品销售与散客零售的有效（未退）数量。 */
@Service
public class SalesStatsService {
    @Autowired
    private ISalesService salesService;

    @Autowired
    private IRetailService retailService;

    public record SaleLine(String operator, Integer goodsId, String orderKey, int quantity, BigDecimal amount) {}

    public List<SaleLine> load(Date startInclusive, Date endExclusive) {
        List<SaleLine> result = new ArrayList<>();
        QueryWrapper<Sales> salesQuery = new QueryWrapper<>();
        salesQuery.ge(startInclusive != null, "salestime", startInclusive);
        salesQuery.lt(endExclusive != null, "salestime", endExclusive);
        salesQuery.gt("number", 0);
        for (Sales sale : salesService.list(salesQuery)) {
            if (sale.getOperateperson() == null || sale.getOperateperson().isBlank()) continue;
            int quantity = sale.getNumber();
            BigDecimal unitPrice = sale.getSaleprice() == null ? BigDecimal.ZERO : sale.getSaleprice();
            String order = sale.getOrderno() == null || sale.getOrderno().isBlank() ? "id:" + sale.getId() : sale.getOrderno();
            result.add(new SaleLine(sale.getOperateperson(), sale.getGoodsid(), "S:" + order,
                quantity, unitPrice.multiply(BigDecimal.valueOf(quantity))));
        }

        QueryWrapper<Retail> retailQuery = new QueryWrapper<>();
        retailQuery.ge(startInclusive != null, "retailtime", startInclusive);
        retailQuery.lt(endExclusive != null, "retailtime", endExclusive);
        retailQuery.gt("number", 0);
        for (Retail retail : retailService.list(retailQuery)) {
            if (retail.getOperateperson() == null || retail.getOperateperson().isBlank()) continue;
            int quantity = retail.getNumber();
            BigDecimal unitPrice = retail.getRetailprice() == null ? BigDecimal.ZERO : BigDecimal.valueOf(retail.getRetailprice());
            String order = retail.getOrderno() == null || retail.getOrderno().isBlank() ? "id:" + retail.getId() : retail.getOrderno();
            result.add(new SaleLine(retail.getOperateperson(), retail.getGoodsid(), "R:" + order,
                quantity, unitPrice.multiply(BigDecimal.valueOf(quantity))));
        }
        return result;
    }
}
