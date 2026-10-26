package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "退费单")
public record RefundVO(
        Long id,
        String refundNo,
        Long chargeOrderId,
        String chargeNo,
        @Schema(description = "退回患者的个人自付部分") Money amount,
        @Schema(description = "医保统筹冲销") Money insuranceAmount,
        @Schema(description = "0 待审核 1 退款中 2 成功 3 失败 4 已驳回") Integer status,
        String reason,
        LocalDateTime createTime,
        LocalDateTime successTime,
        List<Item> items,
        @Schema(description = "按原收款方式拆分的退款；现金、银行卡需收费员确认") List<Tender> tenders,
        @Schema(description = "医保平台未接入、需在医保终端撤销并重结后登记") boolean awaitingInsuranceRegistration,
        @Schema(description = "申请人") Long applicantId,
        String applicantName,
        @Schema(description = "审批人；自动通过的为空") Long approverId,
        String approverName,
        LocalDateTime approveTime,
        @Schema(description = "审批意见；驳回时为驳回理由") String approveNote) {

    public record Item(Long chargeItemId, Integer qty, Money amount, Money insuranceAmount, Money selfAmount) {
    }

    /**
     * @param status 0 待退 1 已退 2 失败 3 待收费员确认（现金、银行卡）
     */
    public record Tender(Long id, Integer channel, Money amount, Integer status, String channelRefundNo,
                         Long confirmBy, LocalDateTime confirmTime, String confirmNote) {
    }
}
