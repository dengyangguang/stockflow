package com.sunlee.sys.common;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sunlee.bus.entity.Goods;
import com.sunlee.sys.entity.Permission;
import com.sunlee.sys.entity.User;
import com.sunlee.sys.service.IPermissionService;
import com.sunlee.sys.service.IRoleService;
import com.sunlee.sys.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 商品参考成本价的独立查看权限；无权限时响应不得包含成本数据。 */
@Component
public class CostPricePermission {
    @Autowired
    private IUserService userService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private IPermissionService permissionService;

    public boolean canView() {
        User sessionUser = (User) StpUtil.getSession().get("user");
        if (sessionUser == null) {
            return false;
        }
        User user = userService.getById(sessionUser.getId());
        if (user == null || !Constast.AVAILABLE_TRUE.equals(user.getAvailable())) {
            return false;
        }
        if (Constast.USER_TYPE_SUPER.equals(user.getType())) {
            return true;
        }
        Set<Integer> permissionIds = new HashSet<>();
        List<Integer> roleIds = roleService.queryUserRoleIdsByUid(user.getId());
        if (roleIds == null) {
            return false;
        }
        for (Integer roleId : roleIds) {
            List<Integer> rolePermissionIds = roleService.queryRolePermissionIdsByRid(roleId);
            if (rolePermissionIds != null) {
                permissionIds.addAll(rolePermissionIds);
            }
        }
        if (permissionIds.isEmpty()) {
            return false;
        }
        QueryWrapper<Permission> query = new QueryWrapper<>();
        query.in("id", permissionIds)
            .eq("type", Constast.TYPE_PERMISSION)
            .eq("available", Constast.AVAILABLE_TRUE)
            .in("percode", "goods:cost:view", "goods:*", "*:*");
        return permissionService.count(query) > 0;
    }

    public void mask(Goods goods) {
        if (goods != null && !canView()) {
            goods.setCostPrice(null);
        }
    }

    public void mask(Collection<Goods> goodsList) {
        if (!canView()) {
            goodsList.forEach(goods -> goods.setCostPrice(null));
        }
    }
}
