package com.sunlee.bus.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sunlee.bus.entity.Goods;
import com.sunlee.bus.entity.Retail;
import com.sunlee.bus.entity.RetailLog;
import com.sunlee.bus.service.IGoodsService;
import com.sunlee.bus.service.IOperationLogService;
import com.sunlee.bus.service.IRetailService;
import com.sunlee.bus.service.MemberPaymentPinService;
import com.sunlee.bus.service.ShoppingCardService;
import com.sunlee.bus.vo.RetailVo;
import com.sunlee.bus.vo.MemberRetailCheckoutVo;
import com.sunlee.bus.vo.ShoppingCardCheckoutVo;
import com.sunlee.sys.annotation.OperationLog;
import com.sunlee.sys.common.Constast;
import com.sunlee.sys.common.DataGridView;
import com.sunlee.sys.common.ResultObj;
import com.sunlee.sys.common.WebUtils;
import com.sunlee.sys.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.math.BigDecimal;

@Slf4j
@RestController
@RequestMapping("/retail")
public class RetailController {

    @Autowired
    private IRetailService retailService;

    @Autowired
    private IGoodsService goodsService;

    @Autowired
    private IOperationLogService operationLogService;

    @Autowired
    private MemberPaymentPinService memberPaymentPinService;

    @Autowired
    private ShoppingCardService shoppingCardService;

    @RequestMapping("loadShoppingCardRate")
    public Map<String, Object> loadShoppingCardRate() {
        Map<String, Object> result = new HashMap<>();
        result.put("ratePercent", shoppingCardService.getRate());
        return result;
    }

    @OperationLog(type = "修改", module = "散客零售", description = "'设置超市购物卡扣点率'")
    @RequestMapping("saveShoppingCardRate")
    public ResultObj saveShoppingCardRate(BigDecimal ratePercent) {
        try {
            shoppingCardService.saveRate(ratePercent);
            return ResultObj.ok("扣点率已保存");
        } catch (Exception e) {
            return ResultObj.error(e.getMessage());
        }
    }

    @RequestMapping("loadAllRetail")
    public DataGridView loadAllRetail(RetailVo retailVo) {
        IPage<Retail> page = new Page<>(retailVo.getPage(), retailVo.getLimit());
        QueryWrapper<Retail> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(retailVo.getGoodsid() != null && retailVo.getGoodsid() != 0, "goodsid", retailVo.getGoodsid());
        queryWrapper.ge(retailVo.getStartTime() != null, "retailtime", retailVo.getStartTime());
        queryWrapper.le(retailVo.getEndTime() != null, "retailtime", retailVo.getEndTime());
        queryWrapper.orderByDesc("retailtime");
        retailService.page(page, queryWrapper);
        List<Retail> records = page.getRecords();
        for (Retail retail : records) {
            Goods goods = goodsService.getById(retail.getGoodsid());
            if (goods != null) {
                retail.setGoodsname(goods.getGoodsname());
                retail.setSize(goods.getSize());
            }
        }
        return new DataGridView(page.getTotal(), records);
    }

    @OperationLog(type = "添加", module = "散客零售", description = "'零售商品ID: ' + #args[0].goodsid + ', 数量: ' + #args[0].number")
    @RequestMapping("addRetail")
    public ResultObj addRetail(RetailVo retailVo) {
        try {
            if ("会员余额".equals(retailVo.getPaytype()) || "超市购物卡".equals(retailVo.getPaytype())) {
                return ResultObj.error("该支付方式请使用零售结算");
            }
            retailVo.setMemberId(null);
            if (retailVo.getGoodsid() == null) {
                return ResultObj.error("商品ID不能为空");
            }
            if (retailVo.getNumber() == null || retailVo.getNumber() <= 0) {
                return ResultObj.error("零售数量必须大于0");
            }
            if (retailVo.getRetailprice() == null || retailVo.getRetailprice() <= 0) {
                return ResultObj.error("零售价格必须大于0");
            }
            User user = (User) WebUtils.getSession().getAttribute("user");
            retailVo.setOperateperson(user.getName());
            retailVo.setRetailtime(new Date());
            retailService.save(retailVo);
            return ResultObj.ADD_SUCCESS;
        } catch (Exception e) {
            log.error("添加零售失败: {}", e.getMessage(), e);
            return ResultObj.error("添加失败: " + e.getMessage());
        }
    }

    /**
     * 校验批量明细：商品、数量>0、价格>0。负数量经原子库存SQL会反向变成凭空加库存，必须在入口处拦截
     * @return 校验失败时的错误信息，null 表示通过
     */
    private String checkOrderItems(List<Retail> list, boolean specialCheckout) {
        if (list == null || list.isEmpty()) {
            return "零售明细不能为空";
        }
        for (int i = 0; i < list.size(); i++) {
            Retail item = list.get(i);
            if (!specialCheckout && ("会员余额".equals(item.getPaytype()) || "超市购物卡".equals(item.getPaytype()))) {
                return "该支付方式请使用零售结算";
            }
            if (item.getGoodsid() == null) {
                return "第" + (i + 1) + "行商品ID不能为空";
            }
            if (item.getNumber() == null || item.getNumber() <= 0) {
                return "第" + (i + 1) + "行零售数量必须大于0";
            }
            if (item.getRetailprice() == null || item.getRetailprice() <= 0) {
                return "第" + (i + 1) + "行零售价格必须大于0";
            }
        }
        return null;
    }

    @OperationLog(type = "添加", module = "散客零售", description = "''")
    @RequestMapping("batchAddRetail")
    public ResultObj batchAddRetail(@RequestBody List<Retail> list) {
        try {
            String checkError = checkOrderItems(list, false);
            if (checkError != null) {
                return new ResultObj(Constast.ERROR, checkError);
            }
            User user = (User) WebUtils.getSession().getAttribute("user");
            Date now = new Date();
            // 生成订单号：RO + 时间戳
            String orderNo = "RO" + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
            int totalNumber = 0;
            for (Retail retail : list) {
                retail.setMemberId(null);
                retail.setOrderno(orderNo);
                retail.setOperateperson(user.getName());
                retail.setRetailtime(now);
                totalNumber += retail.getNumber();
            }
            retailService.batchSave(list);
            // 手动记录操作日志
            com.sunlee.bus.entity.OperationLog logEntity = new com.sunlee.bus.entity.OperationLog();
            logEntity.setType("添加");
            logEntity.setModule("散客零售");
            logEntity.setDescription("批量零售, 订单号: " + orderNo + ", 共" + list.size() + "种商品, 总数量: " + totalNumber);
            logEntity.setOperateperson(user.getName());
            logEntity.setOperatetime(now);
            operationLogService.save(logEntity);
            return ResultObj.ADD_SUCCESS;
        } catch (Exception e) {
            log.error("批量零售失败: {}", e.getMessage(), e);
            return new ResultObj(Constast.ERROR, "添加失败: " + e.getMessage());
        }
    }

    @OperationLog(type = "添加", module = "散客零售", description = "'会员余额零售'")
    @RequestMapping("checkoutMember")
    public ResultObj checkoutMember(@RequestBody MemberRetailCheckoutVo checkout) {
        try {
            if (checkout == null || checkout.getMemberId() == null) {
                return ResultObj.error("请选择会员");
            }
            List<Retail> list = checkout.getItems();
            String checkError = checkOrderItems(list, true);
            if (checkError != null) {
                return ResultObj.error(checkError);
            }
            String pinError = memberPaymentPinService.verify(checkout.getMemberId(), checkout.getPaymentPin());
            if (pinError != null) {
                return ResultObj.error(pinError);
            }
            User user = (User) WebUtils.getSession().getAttribute("user");
            Date now = new Date();
            String orderNo = "RO" + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
            for (Retail retail : list) {
                retail.setId(null);
                retail.setOrderno(orderNo);
                retail.setOperateperson(user.getName());
                retail.setRetailtime(now);
                retail.setOrderStatus(0);
                retail.setIsdelete(0);
            }
            retailService.checkoutMember(list, checkout.getMemberId(), user.getName());
            return new ResultObj(Constast.OK, "会员余额支付成功");
        } catch (Exception e) {
            log.error("会员余额支付失败: {}", e.getMessage(), e);
            return ResultObj.error("支付失败: " + e.getMessage());
        }
    }

    @OperationLog(type = "添加", module = "散客零售", description = "'超市购物卡零售'")
    @RequestMapping("checkoutShoppingCard")
    public ResultObj checkoutShoppingCard(@RequestBody ShoppingCardCheckoutVo checkout) {
        try {
            if (checkout == null) {
                return ResultObj.error("结算信息不能为空");
            }
            List<Retail> list = checkout.getItems();
            String checkError = checkOrderItems(list, true);
            if (checkError != null) {
                return ResultObj.error(checkError);
            }
            User user = (User) WebUtils.getSession().getAttribute("user");
            Date now = new Date();
            String orderNo = "RO" + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
            for (Retail retail : list) {
                retail.setId(null);
                retail.setOrderno(orderNo);
                retail.setOperateperson(user.getName());
                retail.setRetailtime(now);
                retail.setOrderStatus(0);
                retail.setIsdelete(0);
            }
            retailService.checkoutShoppingCard(list, checkout.getExpectedRate());
            return ResultObj.ok("超市购物卡结算成功");
        } catch (Exception e) {
            log.error("超市购物卡结算失败: {}", e.getMessage(), e);
            return ResultObj.error("结算失败: " + e.getMessage());
        }
    }

    @OperationLog(type = "修改", module = "散客零售", description = "'修改零售记录ID: ' + #args[0].id")
    @RequestMapping("updateRetail")
    public ResultObj updateRetail(RetailVo retailVo) {
        try {
            retailService.updateById(retailVo);
            return ResultObj.UPDATE_SUCCESS;
        } catch (Exception e) {
            log.error("修改零售记录失败: {}", e.getMessage(), e);
            return ResultObj.error("修改失败: " + e.getMessage());
        }
    }

    @OperationLog(type = "删除", module = "散客零售", description = "'删除零售记录ID: ' + #args[0]")
    @RequestMapping("deleteRetail")
    public ResultObj deleteRetail(Integer id) {
        try {
            retailService.deleteRetail(id);
            return ResultObj.DELETE_SUCCESS;
        } catch (Exception e) {
            log.error("删除零售记录失败: {}", e.getMessage(), e);
            return ResultObj.error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 查询零售订单列表（按订单号分组）
     */
    @RequestMapping("loadAllOrders")
    public DataGridView loadAllOrders(RetailVo retailVo) {
        return retailService.queryOrders(retailVo);
    }

    /**
     * 查询订单详情
     */
    @RequestMapping("loadOrderDetail")
    public DataGridView loadOrderDetail(String orderNo) {
        List<Retail> list = retailService.queryOrderDetail(orderNo);
        for (Retail retail : list) {
            Goods goods = goodsService.getById(retail.getGoodsid());
            if (goods != null) {
                retail.setGoodsname(goods.getGoodsname());
                retail.setSize(goods.getSize());
            }
        }
        return new DataGridView((long) list.size(), list);
    }

    /**
     * 单商品退货
     */
    @OperationLog(type = "退货", module = "散客零售", description = "'单商品退货, 零售ID: ' + #args[0]")
    @RequestMapping("returnSingleGoods")
    public ResultObj returnSingleGoods(Integer retailId, Integer returnNumber) {
        try {
            retailService.returnSingleGoods(retailId, returnNumber);
            return new ResultObj(Constast.OK, "退货成功");
        } catch (Exception e) {
            log.error("单商品退货失败: {}", e.getMessage(), e);
            return new ResultObj(Constast.ERROR, "退货失败: " + e.getMessage());
        }
    }

    /**
     * 整单退货
     */
    @OperationLog(type = "退货", module = "散客零售", description = "'整单退货, 订单号: ' + #args[0]")
    @RequestMapping("returnOrder")
    public ResultObj returnOrder(String orderNo) {
        try {
            retailService.returnOrder(orderNo);
            return new ResultObj(Constast.OK, "退货成功");
        } catch (Exception e) {
            log.error("整单退货失败: {}", e.getMessage(), e);
            return new ResultObj(Constast.ERROR, "退货失败: " + e.getMessage());
        }
    }

    /**
     * 向订单添加商品
     */
    @OperationLog(type = "加货", module = "散客零售", description = "'向订单 ' + #args[0].orderno + ' 添加商品'")
    @RequestMapping("addToOrder")
    public ResultObj addToOrder(@RequestBody List<Retail> list) {
        try {
            String checkError = checkOrderItems(list, false);
            if (checkError != null) {
                return new ResultObj(Constast.ERROR, checkError);
            }
            User user = (User) WebUtils.getSession().getAttribute("user");
            Date now = new Date();
            for (Retail retail : list) {
                retail.setOperateperson(user.getName());
                retail.setRetailtime(now);
            }
            retailService.addToOrder(list);
            return new ResultObj(Constast.OK, "加货成功");
        } catch (Exception e) {
            log.error("加货失败: {}", e.getMessage(), e);
            return new ResultObj(Constast.ERROR, "加货失败: " + e.getMessage());
        }
    }

    /**
     * 查询退加货记录
     */
    @RequestMapping("loadReturnAddRecords")
    public DataGridView loadReturnAddRecords(RetailVo retailVo) {
        return retailService.queryReturnAddRecords(retailVo);
    }
}
