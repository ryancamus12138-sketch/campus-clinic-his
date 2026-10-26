package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 退费单：原路退回到收款的支付单。amount 为退回患者的现金，insuranceAmount 为医保统筹冲销。
 */
@Getter
@Setter
@TableName("refund_order")
public class RefundOrder extends BaseEntity {

    private String refundNo;
    private Long chargeOrderId;
    private Long payOrderId;
    private Money amount;
    private Money insuranceAmount;
    private String reason;
    private Integer status;
    private String idempotencyKey;
    private Long approverId;
    private LocalDateTime approveTime;
    private String approveNote;
    /** 办理退费的班次：原收款中有窗口收取的方式（现金、银行卡、校园卡）时记录 */
    private Long shiftId;
    private String channelRefundNo;
    private LocalDateTime successTime;
    private Integer retryCount;
}
