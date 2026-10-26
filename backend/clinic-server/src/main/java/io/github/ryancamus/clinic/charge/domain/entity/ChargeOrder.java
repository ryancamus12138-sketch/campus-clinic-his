package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 收费单。金额关系由数据库 CHECK 约束兜底：个人应付 = 原价 − 医保记账 − 减免，已付 ≤ 应付，已退 ≤ 已付。
 * 状态迁移只通过 ChargeOrderMapper 中带状态条件的 UPDATE 完成，不使用 updateById 整行覆盖。
 */
@Getter
@Setter
@TableName("charge_order")
public class ChargeOrder extends BaseEntity {

    private String chargeNo;
    private Long campusId;
    /** 开单科室；收费处直接划价的为空 */
    private Long deptId;
    private Long patientId;
    private Integer patientType;
    private Long registrationId;
    private Integer sourceType;
    private Integer status;
    private Money totalAmount;
    private Money insuranceAmount;
    private Money discountAmount;
    private Money payableAmount;
    private Money paidAmount;
    private Money refundedAmount;
    private String feePolicyCode;
    private LocalDateTime expireTime;
    private LocalDateTime paidTime;
    private Long cashierId;
    private Long shiftId;
}
