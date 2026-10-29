package com.sunlee.bus.vo;

import com.sunlee.bus.entity.Retail;
import lombok.Data;

import java.util.List;

@Data
public class MemberRetailCheckoutVo {
    private Integer memberId;
    private String paymentPin;
    private List<Retail> items;
}
