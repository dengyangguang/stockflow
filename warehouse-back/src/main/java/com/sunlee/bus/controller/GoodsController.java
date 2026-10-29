package com.sunlee.bus.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sunlee.bus.entity.*;
import com.sunlee.bus.service.*;
import com.sunlee.bus.vo.GoodsVo;
import com.sunlee.sys.annotation.OperationLog;
import com.sunlee.sys.common.AppFileUtils;
import com.sunlee.sys.common.Constast;
import com.sunlee.sys.common.CostPricePermission;
import com.sunlee.sys.common.DataGridView;
import com.sunlee.sys.common.PinyinUtils;
import com.sunlee.sys.common.ResultObj;
import com.sunlee.sys.common.WebUtils;
import com.sunlee.sys.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * InnoDB free: 9216 kB; (`providerid`) REFER `warehouse/bus_provider`(`id`) 前端控制器
 * </p>
 *
 * @author sunlee
 * @since 2026-03-20
 */
@Slf4j
@RestController
@RequestMapping("/goods")
public class GoodsController {

    @Autowired
    private IGoodsService goodsService;

    @Autowired
    private IProviderService providerService;

    @Autowired
    private ICategoryService categoryService;

    @Autowired
    private IInportService inportService;

    @Autowired
    private ISalesService salesService;

    @Autowired
    private IOutportService outportService;

    @Autowired
    private ISalesbackService salesbackService;

    @Autowired
    private IGoodsStockService goodsStockService;

    @Autowired
    private CostPricePermission costPricePermission;

    /** 一个条码只能对应一个商品；老商品允许暂时不填条码。 */
    private String validateBarcode(GoodsVo goodsVo) {
        String barcode = StringUtils.trimToNull(goodsVo.getBarcode());
        goodsVo.setBarcode(barcode);
        if (barcode == null) return null;
        if (barcode.length() > 64) return "商品条码不能超过64位";
        QueryWrapper<Goods> query = new QueryWrapper<>();
        query.eq("barcode", barcode);
        if (goodsVo.getId() != null) query.ne("id", goodsVo.getId());
        return goodsService.count(query) > 0 ? "该条码已绑定其他商品" : null;
    }

    /** 扫码枪输出条码后，按完整条码查商品，并返回所选仓库库存。 */
    @RequestMapping("findByBarcode")
    public DataGridView findByBarcode(String barcode, Integer warehouseId) {
        String code = StringUtils.trimToNull(barcode);
        if (code == null) return new DataGridView(Collections.emptyList());
        QueryWrapper<Goods> query = new QueryWrapper<>();
        query.eq("barcode", code).eq("available", Constast.AVAILABLE_TRUE).last("LIMIT 1");
        Goods goods = goodsService.getOne(query);
        if (goods == null) return new DataGridView(Collections.emptyList());
        if (warehouseId != null) {
            goods.setNumber(goodsStockService.getStockNumber(goods.getId(), warehouseId));
        }
        Provider provider = providerService.getById(goods.getProviderid());
        if (provider != null) goods.setProvidername(provider.getProvidername());
        costPricePermission.mask(goods);
        return new DataGridView(Collections.singletonList(goods));
    }

    /**
     * 查询商品（支持名称、拼音、简写搜索，批量查询避免 N+1）
     */
    @RequestMapping("loadAllGoods")
    public DataGridView loadAllGoods(GoodsVo goodsVo){
        IPage<Goods> page = new Page<>(goodsVo.getPage(), goodsVo.getLimit());
        QueryWrapper<Goods> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(goodsVo.getProviderid()!=null && goodsVo.getProviderid()!=0, "providerid", goodsVo.getProviderid());
        queryWrapper.eq(goodsVo.getCategoryid()!=null && goodsVo.getCategoryid()!=0, "categoryid", goodsVo.getCategoryid());
        // 支持商品名称、拼音、简写模糊搜索
        if (StringUtils.isNotBlank(goodsVo.getGoodsname())) {
            String keyword = goodsVo.getGoodsname().trim();
            queryWrapper.and(w -> w
                .like("goodsname", keyword)
                .or().like("pinyin", keyword)
                .or().like("abbreviation", keyword)
            );
        }
        queryWrapper.like(StringUtils.isNotBlank(goodsVo.getProductcode()), "productcode", goodsVo.getProductcode());
        queryWrapper.like(StringUtils.isNotBlank(goodsVo.getBarcode()), "barcode", goodsVo.getBarcode().trim());
        queryWrapper.like(StringUtils.isNotBlank(goodsVo.getPromitcode()), "promitcode", goodsVo.getPromitcode());
        queryWrapper.like(StringUtils.isNotBlank(goodsVo.getDescription()), "description", goodsVo.getDescription());
        queryWrapper.like(StringUtils.isNotBlank(goodsVo.getSize()), "size", goodsVo.getSize());
        queryWrapper.like(StringUtils.isNotBlank(goodsVo.getAttribute()), "attribute", goodsVo.getAttribute());
        queryWrapper.orderByDesc("id");
        goodsService.page(page, queryWrapper);

        // 批量查询供应商和分类，避免 N+1
        List<Goods> records = page.getRecords();
        Set<Integer> providerIds = records.stream().map(Goods::getProviderid).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> categoryIds = records.stream().map(Goods::getCategoryid).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Integer, String> providerMap = new HashMap<>();
        if (!providerIds.isEmpty()) {
            providerService.listByIds(providerIds).forEach(p -> providerMap.put(p.getId(), p.getProvidername()));
        }
        Map<Integer, String> categoryMap = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            categoryService.listByIds(categoryIds).forEach(c -> categoryMap.put(c.getId(), c.getCatename()));
        }

        for (Goods goods : records) {
            goods.setProvidername(providerMap.get(goods.getProviderid()));
            goods.setCategoryname(categoryMap.get(goods.getCategoryid()));
        }

        // 指定仓库时，库存列覆盖为该仓分仓库存（该仓无记录的商品按0计）
        if (goodsVo.getWarehouseId() != null && !records.isEmpty()) {
            List<Integer> goodsIds = records.stream().map(Goods::getId).collect(Collectors.toList());
            QueryWrapper<GoodsStock> stockQuery = new QueryWrapper<>();
            stockQuery.eq("warehouse_id", goodsVo.getWarehouseId());
            stockQuery.in("goodsid", goodsIds);
            Map<Integer, Integer> stockMap = goodsStockService.list(stockQuery).stream()
                .collect(Collectors.toMap(GoodsStock::getGoodsid,
                    s -> s.getNumber() != null ? s.getNumber() : 0));
            for (Goods goods : records) {
                goods.setNumber(stockMap.getOrDefault(goods.getId(), 0));
            }
        }
        costPricePermission.mask(records);
        return new DataGridView(page.getTotal(), records);
    }

    /**
     * 添加商品
     */
    @OperationLog(type = "添加", module = "商品管理", description = "#args[0].goodsname != null ? '添加商品: ' + #args[0].goodsname : '添加商品'")
    @RequestMapping("addGoods")
    public ResultObj addGoods(GoodsVo goodsVo){
        try {
            if (!costPricePermission.canView() && goodsVo.getCostPrice() != null) {
                return ResultObj.error("无权设置商品参考成本价");
            }
            String barcodeError = validateBarcode(goodsVo);
            if (barcodeError != null) return ResultObj.error(barcodeError);
            // 手动数据校验
            if (goodsVo.getGoodsname() == null || goodsVo.getGoodsname().trim().isEmpty()) {
                return ResultObj.error("商品名称不能为空");
            }
            if (goodsVo.getNumber() != null && goodsVo.getNumber() < 0) {
                return ResultObj.error("商品数量不能为负数");
            }
            if (goodsVo.getCostPrice() != null && goodsVo.getCostPrice() < 0) {
                return ResultObj.error("参考成本价不能为负数");
            }
            if (goodsVo.getGoodsimg()!=null && goodsVo.getGoodsimg().endsWith("_temp")){
                String newName = AppFileUtils.renameFile(goodsVo.getGoodsimg());
                goodsVo.setGoodsimg(newName);
            }
            goodsVo.setNumber(0);
            goodsVo.setAvailable(Constast.AVAILABLE_TRUE);
            // 自动生成拼音和简写
            if (StringUtils.isNotBlank(goodsVo.getGoodsname())) {
                goodsVo.setPinyin(PinyinUtils.getPingYin(goodsVo.getGoodsname()));
                goodsVo.setAbbreviation(PinyinUtils.getAbbreviation(goodsVo.getGoodsname()));
            }
            goodsService.save(goodsVo);
            return ResultObj.ADD_SUCCESS;
        } catch (Exception e) {
            log.error("添加商品失败: {}", e.getMessage(), e);
            return ResultObj.error("添加失败: " + e.getMessage());
        }
    }

    /**
     * 修改商品
     */
    @OperationLog(type = "修改", module = "商品管理", description = "#args[0].goodsname != null ? '修改商品: ' + #args[0].goodsname : '修改商品'")
    @RequestMapping("updateGoods")
    public ResultObj updateGoods(GoodsVo goodsVo){
        try {
            Goods oldGoods = goodsVo.getId() == null ? null : goodsService.getById(goodsVo.getId());
            if (oldGoods == null) {
                return ResultObj.error("商品不存在");
            }
            if (!costPricePermission.canView()) {
                if (goodsVo.getCostPrice() != null) {
                    return ResultObj.error("无权修改商品参考成本价");
                }
                goodsVo.setCostPrice(oldGoods.getCostPrice());
            }
            String barcodeError = validateBarcode(goodsVo);
            if (barcodeError != null) return ResultObj.error(barcodeError);
            if (goodsVo.getCostPrice() != null && goodsVo.getCostPrice() < 0) {
                return ResultObj.error("参考成本价不能为负数");
            }
            if (goodsVo.getGoodsimg()!=null
                    && !goodsVo.getGoodsimg().equals(Constast.DEFAULT_IMG_GOODS)
                    && goodsVo.getGoodsimg().endsWith("_temp")){
                String newName = AppFileUtils.renameFile(goodsVo.getGoodsimg());
                goodsVo.setGoodsimg(newName);
                Goods oldGoodsForImg = goodsService.getById(goodsVo.getId());
                if (oldGoodsForImg != null) {
                    String oldPath = oldGoodsForImg.getGoodsimg();
                    AppFileUtils.removeFileByPath(oldPath);
                }
            }
            goodsVo.setNumber(oldGoods.getNumber());
            // 更新拼音和简写
            if (StringUtils.isNotBlank(goodsVo.getGoodsname())) {
                goodsVo.setPinyin(PinyinUtils.getPingYin(goodsVo.getGoodsname()));
                goodsVo.setAbbreviation(PinyinUtils.getAbbreviation(goodsVo.getGoodsname()));
            }
            goodsService.updateById(goodsVo);
            // MyBatis-Plus 默认跳过 null，单独清空用户删除的条码。
            if (goodsVo.getBarcode() == null && oldGoods != null && oldGoods.getBarcode() != null) {
                goodsService.update(new UpdateWrapper<Goods>().eq("id", goodsVo.getId()).set("barcode", null));
            }
            if (goodsVo.getCostPrice() == null && oldGoods != null && oldGoods.getCostPrice() != null) {
                goodsService.update(new UpdateWrapper<Goods>().eq("id", goodsVo.getId()).set("cost_price", null));
            }
            return ResultObj.UPDATE_SUCCESS;
        } catch (Exception e) {
            log.error("修改商品失败: {}", e.getMessage(), e);
            return ResultObj.error("修改失败: " + e.getMessage());
        }
    }

    /**
     * 商品上下架
     */
    @OperationLog(type = "#args[1] == 1 ? '上架' : '下架'", module = "商品管理", description = "#args[1] == 1 ? '上架商品ID: ' + #args[0] : '下架商品ID: ' + #args[0]")
    @RequestMapping("updateGoodsAvailable")
    public ResultObj updateGoodsAvailable(Integer id, Integer available){
        try {
            Goods goods = new Goods();
            goods.setId(id);
            goods.setAvailable(available);
            goodsService.updateById(goods);
            return ResultObj.UPDATE_SUCCESS;
        } catch (Exception e) {
            log.error("商品上下架失败: {}", e.getMessage(), e);
            return ResultObj.error("操作失败: " + e.getMessage());
        }
    }

    /**
     * 删除商品
     */
    @OperationLog(type = "删除", module = "商品管理", description = "'删除商品ID: ' + #args[0]")
    @RequestMapping("deleteGoods")
    public ResultObj deleteGoods(Integer id, String goodsimg){
        try {
            AppFileUtils.removeFileByPath(goodsimg);
            goodsService.deleteGoodsById(id);
            return ResultObj.DELETE_SUCCESS;
        } catch (Exception e) {
            log.error("删除商品失败: {}", e.getMessage(), e);
            return ResultObj.error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 加载所有可用的商品
     * @return
     */
    @RequestMapping("loadAllGoodsForSelect")
    public DataGridView loadAllGoodsForSelect(){
        QueryWrapper<Goods> queryWrapper = new QueryWrapper<Goods>();
        queryWrapper.eq("available",Constast.AVAILABLE_TRUE);
        List<Goods> list = goodsService.list(queryWrapper);
        for (Goods goods : list) {
            Provider provider = providerService.getById(goods.getProviderid());
            if (null!=provider){
                goods.setProvidername(provider.getProvidername());
            }
        }
        costPricePermission.mask(list);
        return new DataGridView(list);
    }

    /**
     * POS页面加载商品（按销售量排序，支持分页）
     */
    @RequestMapping("loadGoodsForPOS")
    public DataGridView loadGoodsForPOS(Integer page, Integer limit, String keyword, Integer warehouseId){
        // 查询所有有效商品
        QueryWrapper<Goods> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("available", Constast.AVAILABLE_TRUE);
        if (StringUtils.isNotBlank(keyword)) {
            String kw = keyword.trim();
            queryWrapper.and(w -> w
                .like("goodsname", kw)
                .or().like("pinyin", kw)
                .or().like("abbreviation", kw)
                .or().eq("barcode", kw)
            );
        }

        List<Goods> allGoods = goodsService.list(queryWrapper);

        // 查询每个商品的销售量
        Map<Integer, Integer> salesCountMap = salesService.getSalesCountByGoodsId();

        // 设置销售量和供应商名称
        for (Goods goods : allGoods) {
            goods.setSalesCount(salesCountMap.getOrDefault(goods.getId(), 0));
            Provider provider = providerService.getById(goods.getProviderid());
            if (provider != null) {
                goods.setProvidername(provider.getProvidername());
            }
        }

        // 指定仓库时，库存数覆盖为该仓分仓库存（POS 按仓开单，库存上限/置灰均按该仓）
        if (warehouseId != null) {
            for (Goods goods : allGoods) {
                goods.setNumber(goodsStockService.getStockNumber(goods.getId(), warehouseId));
            }
        }

        // 按销售量降序排序
        allGoods.sort((a, b) -> Integer.compare(b.getSalesCount(), a.getSalesCount()));

        // 分页处理
        int pageNum = (page != null && page > 0) ? page : 1;
        int pageSize = (limit != null && limit > 0) ? limit : 30;
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allGoods.size());

        List<Goods> pageData = fromIndex < allGoods.size()
                ? allGoods.subList(fromIndex, toIndex)
                : new ArrayList<>();

        costPricePermission.mask(pageData);
        return new DataGridView((long) allGoods.size(), pageData);
    }

    /**
     * 根据供应商ID查询商品信息
     * @param providerid    供应商ID
     * @return
     */
    @RequestMapping("loadGoodsByProviderId")
    public DataGridView loadGoodsByProviderId(Integer providerid,Integer allStatus,Integer warehouseId){
        QueryWrapper<Goods> queryWrapper = new QueryWrapper<Goods>();
        if (!Constast.AVAILABLE_TRUE.equals(allStatus)){
            queryWrapper.eq("available",Constast.AVAILABLE_TRUE);
        }
        queryWrapper.eq(providerid!=null,"providerid",providerid);
        List<Goods> list = goodsService.list(queryWrapper);
        for (Goods goods : list) {
            Provider provider = providerService.getById(goods.getProviderid());
            if (null!=provider){
                goods.setProvidername(provider.getProvidername());
            }
        }
        // 指定仓库时，库存数覆盖为该仓分仓库存（进货页按仓展示）
        if (warehouseId != null) {
            for (Goods goods : list) {
                goods.setNumber(goodsStockService.getStockNumber(goods.getId(), warehouseId));
            }
        }
        costPricePermission.mask(list);
        return new DataGridView(list);
    }

    @RequestMapping("loadAllWarningGoods")
    public DataGridView loadAllWarningGoods(){
        List<Goods> goods = goodsService.loadAllWarning();
        for (Goods g : goods) {
            Provider provider = providerService.getById(g.getProviderid());
            if (provider != null) {
                g.setProvidername(provider.getProvidername());
            }
        }
        costPricePermission.mask(goods);
        return new DataGridView((long) goods.size(),goods);
    }

    /**
     * 加载首页统计数据
     */
    @RequestMapping("loadDashboardStats")
    public Map<String, Object> loadDashboardStats(){
        Map<String, Object> result = new HashMap<>();
        // 商品总数
        long goodsTotal = goodsService.count();
        result.put("goodsTotal", goodsTotal);
        // 今日入库商品数量
        LocalDate today = LocalDate.now();
        Date dayStart = java.sql.Timestamp.valueOf(today.atStartOfDay());
        Date dayEnd = java.sql.Timestamp.valueOf(today.plusDays(1).atStartOfDay());
        QueryWrapper<Inport> inportQuery = new QueryWrapper<>();
        inportQuery.select("IFNULL(SUM(number), 0) as number");
        inportQuery.ge("inporttime", dayStart);
        inportQuery.lt("inporttime", dayEnd);
        Map<String, Object> inportResult = inportService.getMap(inportQuery);
        result.put("todayInport", inportResult != null ? inportResult.get("number") : 0);
        // 今日销售商品数量
        QueryWrapper<Sales> salesQuery = new QueryWrapper<>();
        salesQuery.select("IFNULL(SUM(number), 0) as number");
        salesQuery.ge("salestime", dayStart);
        salesQuery.lt("salestime", dayEnd);
        Map<String, Object> salesResult = salesService.getMap(salesQuery);
        result.put("todaySales", salesResult != null ? salesResult.get("number") : 0);
        return result;
    }

    /**
     * 加载最近操作记录（进货、销售、进货退货、销售退货）
     */
    @RequestMapping("loadRecentOperations")
    public DataGridView loadRecentOperations(){
        List<Map<String, Object>> allOps = new ArrayList<>();

        // 进货
        QueryWrapper<Inport> inportQW = new QueryWrapper<>();
        inportQW.orderByDesc("inporttime");
        inportQW.last("LIMIT 10");
        List<Inport> inports = inportService.list(inportQW);
        for (Inport item : inports) {
            Map<String, Object> op = new HashMap<>();
            op.put("type", "进货");
            op.put("typeTag", "success");
            Goods goods = goodsService.getById(item.getGoodsid());
            op.put("goodsname", goods != null ? goods.getGoodsname() : "未知商品");
            op.put("number", item.getNumber());
            op.put("time", item.getInporttime());
            op.put("operateperson", item.getOperateperson());
            allOps.add(op);
        }

        // 销售
        QueryWrapper<Sales> salesQW = new QueryWrapper<>();
        salesQW.orderByDesc("salestime");
        salesQW.last("LIMIT 10");
        List<Sales> salesList = salesService.list(salesQW);
        for (Sales item : salesList) {
            Map<String, Object> op = new HashMap<>();
            op.put("type", "销售");
            op.put("typeTag", "primary");
            Goods goods = goodsService.getById(item.getGoodsid());
            op.put("goodsname", goods != null ? goods.getGoodsname() : "未知商品");
            op.put("number", item.getNumber());
            op.put("time", item.getSalestime());
            op.put("operateperson", item.getOperateperson());
            allOps.add(op);
        }

        // 进货退货
        QueryWrapper<Outport> outportQW = new QueryWrapper<>();
        outportQW.orderByDesc("outputtime");
        outportQW.last("LIMIT 10");
        List<Outport> outports = outportService.list(outportQW);
        for (Outport item : outports) {
            Map<String, Object> op = new HashMap<>();
            op.put("type", "进货退货");
            op.put("typeTag", "warning");
            Goods goods = goodsService.getById(item.getGoodsid());
            op.put("goodsname", goods != null ? goods.getGoodsname() : "未知商品");
            op.put("number", item.getNumber());
            op.put("time", item.getOutputtime());
            op.put("operateperson", item.getOperateperson());
            allOps.add(op);
        }

        // 销售退货
        QueryWrapper<Salesback> salesbackQW = new QueryWrapper<>();
        salesbackQW.orderByDesc("salesbacktime");
        salesbackQW.last("LIMIT 10");
        List<Salesback> salesbacks = salesbackService.list(salesbackQW);
        for (Salesback item : salesbacks) {
            Map<String, Object> op = new HashMap<>();
            op.put("type", "销售退货");
            op.put("typeTag", "danger");
            Goods goods = goodsService.getById(item.getGoodsid());
            op.put("goodsname", goods != null ? goods.getGoodsname() : "未知商品");
            op.put("number", item.getNumber());
            op.put("time", item.getSalesbacktime());
            op.put("operateperson", item.getOperateperson());
            allOps.add(op);
        }

        // 按时间倒序排序，取前10条
        allOps.sort((a, b) -> {
            Date timeA = (Date) a.get("time");
            Date timeB = (Date) b.get("time");
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });
        if (allOps.size() > 10) {
            allOps = allOps.subList(0, 10);
        }

        return new DataGridView((long) allOps.size(), allOps);
    }

    /**
     * 批量生成已有商品的拼音和简写（数据迁移用）
     */
    @RequestMapping("regeneratePinyin")
    public ResultObj regeneratePinyin() {
        try {
            List<Goods> allGoods = goodsService.list();
            int count = 0;
            for (Goods goods : allGoods) {
                if (StringUtils.isNotBlank(goods.getGoodsname())) {
                    goods.setPinyin(PinyinUtils.getPingYin(goods.getGoodsname()));
                    goods.setAbbreviation(PinyinUtils.getAbbreviation(goods.getGoodsname()));
                    goodsService.updateById(goods);
                    count++;
                }
            }
            return new ResultObj(200, "成功更新 " + count + " 个商品的拼音");
        } catch (Exception e) {
            log.error("批量生成拼音失败: {}", e.getMessage(), e);
            return new ResultObj(-1, "生成拼音失败");
        }
    }

}

