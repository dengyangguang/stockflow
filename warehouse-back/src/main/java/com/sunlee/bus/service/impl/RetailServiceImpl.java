package com.sunlee.bus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sunlee.bus.entity.Goods;
import com.sunlee.bus.entity.Retail;
import com.sunlee.bus.entity.RetailLog;
import com.sunlee.bus.entity.Retailback;
import com.sunlee.bus.mapper.GoodsMapper;
import com.sunlee.bus.mapper.RetailLogMapper;
import com.sunlee.bus.mapper.RetailMapper;
import com.sunlee.bus.mapper.RetailbackMapper;
import com.sunlee.bus.service.IGoodsStockService;
import com.sunlee.bus.service.IRetailService;
import com.sunlee.bus.service.IMemberService;
import com.sunlee.bus.service.ShoppingCardService;
import com.sunlee.bus.vo.RetailVo;
import com.sunlee.sys.common.DataGridView;

import java.util.Date;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RetailServiceImpl extends ServiceImpl<RetailMapper, Retail> implements IRetailService {

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private RetailbackMapper retailbackMapper;

    @Autowired
    private RetailLogMapper retailLogMapper;

    @Autowired
    private IGoodsStockService goodsStockService;

    @Autowired
    private IMemberService memberService;

    @Autowired
    private ShoppingCardService shoppingCardService;

    private Goods verifyCurrentPrice(Retail retail) {
        Goods goods = goodsMapper.selectById(retail.getGoodsid());
        if (goods == null || goods.getAvailable() == null || goods.getAvailable() != 1
                || goods.getPrice() == null || goods.getPrice() <= 0) {
            throw new IllegalArgumentException("商品不存在、已下架或售价无效");
        }
        if (retail.getRetailprice() == null || BigDecimal.valueOf(goods.getPrice())
                .compareTo(BigDecimal.valueOf(retail.getRetailprice())) != 0) {
            throw new IllegalArgumentException("商品售价已变化，请刷新页面重新结算: " + goods.getGoodsname());
        }
        return goods;
    }

    @Override
    public void checkoutShoppingCard(List<Retail> list, BigDecimal expectedRate) {
        BigDecimal rate = shoppingCardService.getRate();
        if (rate == null) {
            throw new IllegalStateException("请先设置超市购物卡扣点率");
        }
        if (expectedRate == null || expectedRate.compareTo(rate) != 0) {
            throw new IllegalStateException("扣点率已变化，请刷新页面后重新结算");
        }
        for (Retail retail : list) {
            Goods goods = verifyCurrentPrice(retail);
            retail.setRetailprice(goods.getPrice());
            retail.setPaytype("超市购物卡");
            retail.setMemberId(null);
        }
        batchSave(list);
        shoppingCardService.record(list.get(0).getOrderno(), rate, list);
    }

    private BigDecimal amount(Retail retail, int quantity) {
        return BigDecimal.valueOf(retail.getRetailprice()).multiply(BigDecimal.valueOf(quantity));
    }

    private void refundMember(Retail retail, int quantity) {
        BigDecimal refundAmount = amount(retail, quantity);
        List<RetailLog> previousReturns = retailLogMapper.selectList(new QueryWrapper<RetailLog>()
                .eq("order_no", retail.getOrderno()).eq("type", "return"));
        BigDecimal refundedBefore = previousReturns.stream()
                .map(log -> BigDecimal.valueOf(log.getPrice()).multiply(BigDecimal.valueOf(log.getNumber())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 消费时按整单金额取整送积分；分次退款按累计退款金额计算本次应冲回的积分。
        int points = refundedBefore.add(refundAmount).intValue() - refundedBefore.intValue();
        memberService.refund(retail.getMemberId(), refundAmount, points, retail.getOperateperson(),
                "零售订单退货 " + retail.getOrderno());
    }

    @Override
    public void checkoutMember(List<Retail> list, Integer memberId, String operator) {
        if (memberId == null || list == null || list.isEmpty()) {
            throw new IllegalArgumentException("请选择会员和商品");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Retail retail : list) {
            Goods goods = verifyCurrentPrice(retail);
            // 余额扣款以数据库当前售价为准，不接受客户端传入的价格。
            retail.setRetailprice(goods.getPrice());
            retail.setPaytype("会员余额");
            retail.setMemberId(memberId);
            total = total.add(amount(retail, retail.getNumber()));
        }
        batchSave(list);
        memberService.consume(memberId, total, operator, "零售订单 " + list.get(0).getOrderno());
    }

    @Override
    public boolean save(Retail entity) {
        Goods goods = goodsMapper.selectById(entity.getGoodsid());
        if (goods == null) {
            throw new RuntimeException("商品不存在: " + entity.getGoodsid());
        }
        // 未指定仓库时落默认仓，保证单据始终记录出库仓
        entity.setWarehouseId(goodsStockService.resolveWarehouseId(entity.getWarehouseId()));
        // 原子扣减分仓库存，该仓库存不足时扣减失败并回滚事务
        goodsStockService.decrease(entity.getGoodsid(), entity.getWarehouseId(), entity.getNumber(), goods.getGoodsname());
        return super.save(entity);
    }

    @Override
    public boolean updateById(Retail entity) {
        Retail retail = baseMapper.selectById(entity.getId());
        if (retail == null) {
            throw new RuntimeException("零售记录不存在: " + entity.getId());
        }
        if (retail.getMemberId() != null || "超市购物卡".equals(retail.getPaytype())) {
            throw new RuntimeException("该支付方式的订单请通过退货处理，不能直接编辑");
        }
        if ("会员余额".equals(entity.getPaytype()) || "超市购物卡".equals(entity.getPaytype())) {
            throw new RuntimeException("该支付方式请使用零售结算");
        }
        entity.setMemberId(null);
        // 禁止编辑时更换商品：库存按商品维度差量调整，换商品会导致新旧商品库存都错账
        if (!retail.getGoodsid().equals(entity.getGoodsid())) {
            throw new RuntimeException("编辑单据不允许更换商品，请删除原单后重新开单");
        }
        // 禁止编辑时更换仓库：库存按仓差量调整，换仓会导致两个仓都错账（与换商品同理）
        if (entity.getWarehouseId() != null && !entity.getWarehouseId().equals(retail.getWarehouseId())) {
            throw new RuntimeException("编辑单据不允许更换仓库，请删除原单后重新开单");
        }
        // 已退完的记录禁止编辑，避免静默复活已退完订单导致账实不符
        if (retail.getOrderStatus() != null && retail.getOrderStatus() == 1) {
            throw new RuntimeException("该记录已退完，禁止编辑");
        }
        Goods goods = goodsMapper.selectById(entity.getGoodsid());
        if (goods == null) {
            throw new RuntimeException("商品不存在: " + entity.getGoodsid());
        }
        if (entity.getNumber() == null) {
            throw new RuntimeException("零售数量不能为空");
        }
        if (entity.getNumber() < 0) {
            throw new RuntimeException("零售数量不能为负数");
        }
        //按差量调整库存：新数量比原数量多则原子扣减，少则回补（均按原出库仓）
        int delta = entity.getNumber() - retail.getNumber();
        if (delta > 0) {
            goodsStockService.decrease(entity.getGoodsid(), retail.getWarehouseId(), delta, goods.getGoodsname());
        } else if (delta < 0) {
            goodsStockService.increase(entity.getGoodsid(), retail.getWarehouseId(), -delta);
        }
        return super.updateById(entity);
    }

    @Override
    public void deleteRetail(Integer id) {
        Retail retail = baseMapper.selectById(id);
        if (retail == null) {
            throw new RuntimeException("零售记录不存在: " + id);
        }
        if (retail.getMemberId() != null || "超市购物卡".equals(retail.getPaytype())) {
            throw new RuntimeException("该支付方式的订单请通过退货处理，不能直接删除");
        }
        // 级联软删除该零售单关联的所有退货记录
        Retailback retailbackUpdate = new Retailback();
        retailbackUpdate.setIsdelete(1);
        QueryWrapper<Retailback> wrapper = new QueryWrapper<>();
        wrapper.eq("retailid", id);
        retailbackMapper.update(retailbackUpdate, wrapper);
        // 回滚商品库存（按原出库仓回库）
        goodsStockService.increase(retail.getGoodsid(), retail.getWarehouseId(), retail.getNumber());
        // 软删除零售单
        baseMapper.deleteById(id);
    }

    @Override
    public void batchSave(List<Retail> list) {
        for (Retail entity : list) {
            Goods goods = goodsMapper.selectById(entity.getGoodsid());
            if (goods == null) {
                throw new RuntimeException("商品不存在: " + entity.getGoodsid());
            }
            // 未指定仓库时落默认仓
            entity.setWarehouseId(goodsStockService.resolveWarehouseId(entity.getWarehouseId()));
            // 原子扣减分仓库存，任一商品该仓库存不足则整批回滚
            goodsStockService.decrease(entity.getGoodsid(), entity.getWarehouseId(), entity.getNumber(), goods.getGoodsname());
        }
        saveBatch(list);

        // 记录操作日志
        for (Retail entity : list) {
            RetailLog log = new RetailLog();
            log.setOrderNo(entity.getOrderno());
            log.setGoodsId(entity.getGoodsid());
            log.setType("retail");
            log.setNumber(entity.getNumber());
            log.setPrice(entity.getRetailprice());
            log.setPaytype(entity.getPaytype());
            log.setOperatePerson(entity.getOperateperson());
            log.setOperateTime(entity.getRetailtime());
            log.setRemark(entity.getRemark());
            retailLogMapper.insert(log);
        }
    }

    @Override
    public DataGridView queryOrders(RetailVo retailVo) {
        // 按订单号分组，SQL + 数据库分页（避免全表加载与内存分页，保证按时间倒序稳定输出）
        Page<Map<String, Object>> page = new Page<>(
                retailVo.getPage() != null ? retailVo.getPage() : 1,
                retailVo.getLimit() != null ? retailVo.getLimit() : 10);
        IPage<Map<String, Object>> result = baseMapper.selectOrdersPage(page, retailVo);
        return new DataGridView(result.getTotal(), result.getRecords());
    }

    @Override
    public List<Retail> queryOrderDetail(String orderNo) {
        QueryWrapper<Retail> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("orderno", orderNo);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public void returnSingleGoods(Integer retailId, Integer returnNumber) {
        Retail retail = baseMapper.selectById(retailId);
        if (retail == null) {
            throw new RuntimeException("零售记录不存在");
        }

        if (retail.getOrderStatus() != null && retail.getOrderStatus() == 1) {
            throw new RuntimeException("该订单已退完，无法操作");
        }

        int returnQty = (returnNumber != null && returnNumber > 0) ? returnNumber : retail.getNumber();
        if (returnQty > retail.getNumber()) {
            throw new RuntimeException("退货数量不能超过零售数量");
        }

        // 原子扣减剩余可退数量（并发退货时防止超退导致库存与单据不一致）
        if (baseMapper.decreaseRemaining(retailId, returnQty) == 0) {
            throw new RuntimeException("退货失败：剩余可退数量不足或该记录已退完");
        }

        // 回滚商品库存（按原出库仓回库）
        goodsStockService.increase(retail.getGoodsid(), retail.getWarehouseId(), returnQty);

        if (retail.getMemberId() != null) {
            refundMember(retail, returnQty);
        }

        // 记录退货日志
        RetailLog log = new RetailLog();
        log.setOrderNo(retail.getOrderno());
        log.setGoodsId(retail.getGoodsid());
        log.setType("return");
        log.setNumber(returnQty);
        log.setPrice(retail.getRetailprice());
        log.setPaytype(retail.getPaytype());
        log.setOperatePerson(retail.getOperateperson());
        log.setOperateTime(new Date());
        log.setRemark(returnQty >= retail.getNumber() ? "单商品退货（全部）" : "单商品退货（部分）");
        retailLogMapper.insert(log);

        // 如果退全部，则标记该记录为已退完（数量已被原子扣减为0）
        if (returnQty >= retail.getNumber()) {
            Retail finish = new Retail();
            finish.setId(retailId);
            finish.setOrderStatus(1);
            baseMapper.updateById(finish);
        }

        checkOrderReturnComplete(retail.getOrderno());
        if ("超市购物卡".equals(retail.getPaytype())) {
            shoppingCardService.recalculate(retail.getOrderno(), queryOrderDetail(retail.getOrderno()));
        }
    }

    @Override
    public void returnOrder(String orderNo) {
        QueryWrapper<Retail> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("orderno", orderNo);
        queryWrapper.eq("order_status", 0);
        List<Retail> list = baseMapper.selectList(queryWrapper);

        for (Retail retail : list) {
            if (retail.getNumber() == null || retail.getNumber() <= 0) {
                continue;
            }
            // 原子扣减剩余数量：并发整单退货时已被其他事务退掉的记录跳过，防止重复回库
            if (baseMapper.decreaseRemaining(retail.getId(), retail.getNumber()) == 0) {
                continue;
            }
            // 回滚商品库存（按原出库仓回库）
            goodsStockService.increase(retail.getGoodsid(), retail.getWarehouseId(), retail.getNumber());
            if (retail.getMemberId() != null) {
                refundMember(retail, retail.getNumber());
            }

            RetailLog log = new RetailLog();
            log.setOrderNo(retail.getOrderno());
            log.setGoodsId(retail.getGoodsid());
            log.setType("return");
            log.setNumber(retail.getNumber());
            log.setPrice(retail.getRetailprice());
            log.setPaytype(retail.getPaytype());
            log.setOperatePerson(retail.getOperateperson());
            log.setOperateTime(new Date());
            log.setRemark("整单退货");
            retailLogMapper.insert(log);

            // 标记为已退完（数量已被原子扣减为0）
            Retail finish = new Retail();
            finish.setId(retail.getId());
            finish.setOrderStatus(1);
            baseMapper.updateById(finish);
        }
        if (!list.isEmpty() && "超市购物卡".equals(list.get(0).getPaytype())) {
            shoppingCardService.recalculate(orderNo, queryOrderDetail(orderNo));
        }
    }

    private void checkOrderReturnComplete(String orderNo) {
        QueryWrapper<Retail> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("orderno", orderNo);
        queryWrapper.eq("order_status", 0);
        queryWrapper.gt("number", 0);
        long count = baseMapper.selectCount(queryWrapper);

        if (count == 0) {
            Retail update = new Retail();
            update.setOrderStatus(1);
            QueryWrapper<Retail> updateWrapper = new QueryWrapper<>();
            updateWrapper.eq("orderno", orderNo);
            baseMapper.update(update, updateWrapper);
        }
    }

    @Override
    public void addToOrder(List<Retail> list) {
        if (list == null || list.isEmpty()) {
            throw new RuntimeException("商品列表不能为空");
        }

        String orderNo = list.get(0).getOrderno();
        if (orderNo == null || orderNo.isBlank()) {
            throw new RuntimeException("订单号不能为空");
        }
        List<Retail> existingOrder = queryOrderDetail(orderNo);
        if (existingOrder.isEmpty()) {
            throw new RuntimeException("订单不存在");
        }
        if (existingOrder.stream().anyMatch(item -> item.getMemberId() != null
                || "超市购物卡".equals(item.getPaytype()))) {
            throw new RuntimeException("该支付方式的订单暂不支持加货，请新开一单");
        }

        for (Retail retail : list) {
            retail.setOrderno(orderNo);
            retail.setMemberId(null);

            // 原子扣减分仓库存，该仓库存不足则整批回滚（未指定仓库时落默认仓）
            Goods goods = goodsMapper.selectById(retail.getGoodsid());
            if (goods == null) {
                throw new RuntimeException("商品不存在: " + retail.getGoodsid());
            }
            retail.setWarehouseId(goodsStockService.resolveWarehouseId(retail.getWarehouseId()));
            goodsStockService.decrease(retail.getGoodsid(), retail.getWarehouseId(), retail.getNumber(), goods.getGoodsname());
        }

        saveBatch(list);

        // 记录操作日志
        for (Retail entity : list) {
            RetailLog log = new RetailLog();
            log.setOrderNo(entity.getOrderno());
            log.setGoodsId(entity.getGoodsid());
            log.setType("add");
            log.setNumber(entity.getNumber());
            log.setPrice(entity.getRetailprice());
            log.setPaytype(entity.getPaytype());
            log.setOperatePerson(entity.getOperateperson());
            log.setOperateTime(entity.getRetailtime());
            log.setRemark(entity.getRemark());
            retailLogMapper.insert(log);
        }
    }

    @Override
    public DataGridView queryReturnAddRecords(RetailVo retailVo) {
        // SQL 联表 + 数据库分页（避免全表加载与 N+1 查询）
        Page<Map<String, Object>> page = new Page<>(
                retailVo.getPage() != null ? retailVo.getPage() : 1,
                retailVo.getLimit() != null ? retailVo.getLimit() : 10);
        IPage<Map<String, Object>> result = baseMapper.selectReturnAddRecordsPage(page, retailVo);

        // 设置类型中文标签
        for (Map<String, Object> record : result.getRecords()) {
            String type = (String) record.get("type");
            if ("retail".equals(type)) {
                record.put("type", "零售");
                record.put("typeTag", "primary");
            } else if ("add".equals(type)) {
                record.put("type", "加货");
                record.put("typeTag", "success");
            } else if ("return".equals(type)) {
                record.put("type", "退货");
                record.put("typeTag", "danger");
            }
        }

        return new DataGridView(result.getTotal(), result.getRecords());
    }
}
