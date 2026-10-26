package io.github.ryancamus.clinic.charge.campuscard;

import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundResult;
import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学校一卡通（校园卡）消费接口。收费窗口刷卡或读卡号后扣款，由学校一卡通平台记账。
 * <p>
 * 一卡通平台各校厂商不同（接口多为 WebService 或 HTTP + 签名），这里只定义收费需要的语义：
 * 扣款、冲正（撤销刚才的扣款）、退款、按日下载流水对账。所有操作以本系统业务号幂等。
 * <p>
 * 正式代码中目前没有实现（未接入）：收费界面不提供校园卡收款。测试中使用测试替身。
 */
public interface CampusCardGateway {

    /**
     * 扣款。同一业务号重复调用返回首次结果，不会重复扣款。
     *
     * @param bizNo 本系统支付单号
     * @throws CampusCardException 卡号无效、挂失、余额不足、平台不可用
     */
    Debit debit(String bizNo, String cardNo, Money amount);

    /** 冲正：撤销尚未对账的扣款（收款中途失败时使用）。没有这笔扣款或已冲正时视为成功 */
    void reverse(String bizNo);

    /** 退款到原卡，以退费单号幂等 */
    RefundResult refund(String bizNo, String refundNo, Money amount);

    /** 某天成功且未冲正的扣款流水，用于次日对账 */
    List<Debit> statement(LocalDate date);

    record Debit(String bizNo, String cardNo, String tradeNo, Money amount, LocalDateTime at) {
    }

    class CampusCardException extends RuntimeException {
        public CampusCardException(String message) {
            super(message);
        }
    }
}
