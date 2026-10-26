package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "收费单")
public record ChargeOrderVO(
        Long id,
        String chargeNo,
        Long patientId,
        String patientName,
        @Schema(description = "1 学生 2 教职工 3 其他") Integer patientType,
        @Schema(description = "1 挂号 2 门诊（开方或收费处划价） 3 体检 4 疫苗") Integer sourceType,
        @Schema(description = "0 待支付 2 已支付 3 部分退费 4 已全退 5 已关闭") Integer status,
        Money totalAmount,
        Money insuranceAmount,
        Money payableAmount,
        Money paidAmount,
        Money refundedAmount,
        String feePolicyCode,
        LocalDateTime expireTime,
        LocalDateTime paidTime,
        LocalDateTime createTime,
        @Schema(description = "详情接口返回") List<ChargeItemVO> items,
        @Schema(description = "详情接口返回") List<PayOrderVO> pays,
        @Schema(description = "详情接口返回") List<RefundVO> refunds) {
}
