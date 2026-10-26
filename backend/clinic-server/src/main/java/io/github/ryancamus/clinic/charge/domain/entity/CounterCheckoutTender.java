package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 窗口结算中一种收款方式的收取与撤回状态 */
@Getter
@Setter
@TableName("counter_checkout_tender")
public class CounterCheckoutTender extends BaseEntity {

    public static final int COLLECTING = 0;
    public static final int COLLECTED = 1;
    public static final int REVERTED = 2;
    public static final int REVERT_FAILED = 3;
    /** 财务核实：款项已退还患者（如已在 POS 机撤销、现金已当面退还） */
    public static final int VERIFIED_RETURNED = 4;
    /** 财务核实：实际未扣款（如平台查询无此交易） */
    public static final int VERIFIED_NOT_CHARGED = 5;
    /**
     * 现金已收、结算未完成：现金是否已当面退还患者，系统无从得知，不能视为已撤回，
     * 须由财务向收费员、患者核实后登记（{@link #VERIFIED_RETURNED} 或 {@link #VERIFIED_NOT_CHARGED}）。
     */
    public static final int CASH_RETURN_UNCONFIRMED = 6;

    private Long checkoutId;
    private Integer seq;
    private String payNo;
    private Integer channel;
    private Money amount;
    private Integer status;
    private String channelTradeNo;
    private String tenderRef;
    private Long verifyBy;
    private LocalDateTime verifyTime;
    private String verifyRef;
    private String verifyNote;

    /** 收取中、已收（中断时）、撤回失败、现金待确认退还：钱可能还在系统之外，需要财务逐笔核实 */
    public boolean needsVerification() {
        return status != null && (status == COLLECTING || status == COLLECTED || status == REVERT_FAILED
                || status == CASH_RETURN_UNCONFIRMED);
    }
}
