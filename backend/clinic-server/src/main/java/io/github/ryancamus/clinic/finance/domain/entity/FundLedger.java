package io.github.ryancamus.clinic.finance.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资金流水。只追加，不继承 BaseEntity（没有修改人、版本号）。
 * 金额带符号（退费为负），因此用 BigDecimal 而不是非负的 Money。
 */
@Getter
@Setter
@TableName("fund_ledger")
public class FundLedger {

    public static final int BIZ_PAYMENT = 1;
    public static final int BIZ_REFUND = 2;

    @TableId(type = IdType.INPUT)
    private Long id;
    private LocalDate ledgerDate;
    private Long campusId;
    private Long deptId;
    private Long shiftId;
    private Integer bizType;
    private String bizNo;
    private Integer channel;
    private Integer itemType;
    private Integer patientType;
    private BigDecimal amount;
    private BigDecimal insuranceAmount;
    private LocalDateTime createTime;
}
