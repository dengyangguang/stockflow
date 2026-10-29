package com.sunlee.bus.controller;

import com.sunlee.bus.entity.Goods;
import com.sunlee.bus.service.IGoodsService;
import com.sunlee.bus.service.SalesStatsService;
import com.sunlee.bus.service.SalesStatsService.SaleLine;
import com.sunlee.sys.common.DataGridView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 业绩排名
 */
@Slf4j
@RestController
@RequestMapping("/performance")
public class PerformanceController {

    @Autowired
    private SalesStatsService salesStatsService;

    @Autowired
    private IGoodsService goodsService;

    /**
     * 销售员业绩排名
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param period    时间维度: today/week/month/quarter/year
     */
    @RequestMapping("salesRanking")
    public DataGridView salesRanking(
            @DateTimeFormat(pattern = "yyyy-MM-dd") Date startTime,
            @DateTimeFormat(pattern = "yyyy-MM-dd") Date endTime,
            String period) {

        Date[] range = resolveDateRange(period, startTime, endTime);
        List<SaleLine> salesList = salesStatsService.load(range[0], range[1]);

        // 按操作人分组统计
        Map<String, List<SaleLine>> grouped = salesList.stream()
                .collect(Collectors.groupingBy(SaleLine::operator));

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map.Entry<String, List<SaleLine>> entry : grouped.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("operator", entry.getKey());
            List<SaleLine> list = entry.getValue();
            long totalOrders = list.stream().map(SaleLine::orderKey).distinct().count();
            int totalQuantity = list.stream().mapToInt(SaleLine::quantity).sum();
            BigDecimal totalAmount = list.stream()
                    .map(SaleLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            item.put("totalOrders", totalOrders);
            item.put("totalQuantity", totalQuantity);
            item.put("totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP));
            ranking.add(item);
        }

        // 按总金额降序
        ranking.sort((a, b) -> ((BigDecimal) b.get("totalAmount")).compareTo((BigDecimal) a.get("totalAmount")));

        // 添加排名
        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).put("rank", i + 1);
        }

        return new DataGridView((long) ranking.size(), ranking);
    }

    /**
     * 商品销量排名
     */
    @RequestMapping("goodsRanking")
    public DataGridView goodsRanking(
            @DateTimeFormat(pattern = "yyyy-MM-dd") Date startTime,
            @DateTimeFormat(pattern = "yyyy-MM-dd") Date endTime,
            String period) {

        Date[] range = resolveDateRange(period, startTime, endTime);
        List<SaleLine> salesList = salesStatsService.load(range[0], range[1]);

        // 按商品分组
        Map<Integer, List<SaleLine>> grouped = salesList.stream()
                .filter(s -> s.goodsId() != null)
                .collect(Collectors.groupingBy(SaleLine::goodsId));

        Map<Integer, String> goodsNames = new HashMap<>();
        if (!grouped.isEmpty()) {
            for (Goods goods : goodsService.listByIds(grouped.keySet())) {
                goodsNames.put(goods.getId(), goods.getGoodsname());
            }
        }

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map.Entry<Integer, List<SaleLine>> entry : grouped.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("goodsid", entry.getKey());
            List<SaleLine> list = entry.getValue();
            // 取商品名
            String goodsName = goodsNames.getOrDefault(entry.getKey(), "商品#" + entry.getKey());
            item.put("goodsname", goodsName);
            int totalQuantity = list.stream().mapToInt(SaleLine::quantity).sum();
            BigDecimal totalAmount = list.stream()
                    .map(SaleLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            item.put("totalQuantity", totalQuantity);
            item.put("totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP));
            ranking.add(item);
        }

        ranking.sort((a, b) -> ((Integer) b.get("totalQuantity")).compareTo((Integer) a.get("totalQuantity")));

        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).put("rank", i + 1);
        }

        return new DataGridView((long) ranking.size(), ranking);
    }

    private Date[] resolveDateRange(String period, Date startTime, Date endTime) {
        if (startTime != null && endTime != null) {
            Calendar end = Calendar.getInstance();
            end.setTime(endTime);
            end.add(Calendar.DAY_OF_MONTH, 1);
            return new Date[]{startTime, end.getTime()};
        }
        Calendar cal = Calendar.getInstance();
        Date now = new Date();
        Date start = null;
        if (period != null) {
            switch (period) {
                case "today":
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    start = cal.getTime();
                    break;
                case "week":
                    cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    start = cal.getTime();
                    break;
                case "month":
                    cal.set(Calendar.DAY_OF_MONTH, 1);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    start = cal.getTime();
                    break;
                case "quarter":
                    int month = cal.get(Calendar.MONTH);
                    cal.set(Calendar.MONTH, month / 3 * 3);
                    cal.set(Calendar.DAY_OF_MONTH, 1);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    start = cal.getTime();
                    break;
                case "year":
                    cal.set(Calendar.DAY_OF_YEAR, 1);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    start = cal.getTime();
                    break;
            }
        }
        return new Date[]{start, new Date(now.getTime() + 1000)};
    }
}
