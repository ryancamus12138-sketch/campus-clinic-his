package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @param status 3 待处理 4 已处理
 */
@Schema(description = "收款异常：撤回失败、医保撤销失败或中途中断，已收款项或医保结算可能没有撤回")
public record CheckoutExceptionVO(
        Long id,
        String chargeNo,
        Long cashierId,
        String cashierName,
        Integer status,
        String failReason,
        LocalDateTime createTime,
        List<Tender> tenders,
        List<Insurance> insurance,
        Long resolverId,
        String resolverName,
        LocalDateTime resolveTime,
        String resolveNote) {

    /**
     * @param status 0 收取中（结果未知） 1 已收 2 已撤回 3 撤回失败 4 已核实退还 5 已核实未扣款 6 现金待确认退还
     * @param needsVerification 可能已扣款、需要财务逐笔核实
     */
    public record Tender(Long id, String payNo, Integer channel, Money amount, Integer status, String channelTradeNo,
                         String tenderRef, boolean needsVerification, Long verifyBy, String verifyByName,
                         LocalDateTime verifyTime,
                         String verifyRef, String verifyNote) {
    }

    /**
     * @param source 1 平台结算 3 医保终端办理后登记
     * @param status 0 结算中（结果未知） 1 已结算（未撤销） 2 已撤销
     */
    public record Insurance(String bizNo, String setlId, Integer source, Integer status, Money fundPay,
                            String cancelNote) {
    }
}
