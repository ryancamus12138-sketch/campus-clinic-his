package io.github.ryancamus.clinic.finance.domain.vo;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 班次流水汇总（查询结果） */
@Getter
@Setter
public class ShiftTotals {

    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private BigDecimal onlineAmount;
    private BigDecimal refundAmount;
    private BigDecimal expectedCash;
    /** 银行卡（POS）净额 = 收款 − 退款，应与 POS 机结算单一致 */
    private BigDecimal posExpected;
    /** 银行卡（POS）收款、退款笔数；有任何一笔都要核对 POS 结算单（收退相抵为 0 也要核对） */
    private Integer posCount;
    private Integer orderCount;
}
