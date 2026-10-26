package io.github.ryancamus.clinic.charge.pay;

import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 付款码收款渠道：收费窗口扫患者手机上的微信、支付宝付款码，以及撤单、退款、日账单。
 * 高校一般经财务处的“统一支付平台”收单，也有直连微信、支付宝商户的。
 * <p>
 * 正式代码中目前没有实现（未接入：窗口不能扫付款码）。接入时只写一个实现类，业务代码不变。测试中使用测试替身。
 */
public interface PayChannelClient {

    boolean supports(int channel);

    /**
     * 付款码支付：收费窗口用扫码枪扫患者手机上的付款码，渠道同步返回结果。
     * 患者需要输入密码时返回 NOT_PAID（真实渠道的 USERPAYING），调用方应稍后查询；
     * 扣款失败返回 CLOSED。以支付单号幂等。
     */
    ChannelTrade micropay(int channel, String payNo, String authCode, Money amount, String subject);

    /** 主动查询交易状态；渠道没有这笔交易时返回 NOT_FOUND */
    ChannelTrade query(String payNo);

    /**
     * 撤单（付款码扣款未完成时关闭交易）。已支付的交易无法关闭，返回 false，调用方应改为退款。
     */
    boolean close(String payNo);

    /**
     * 原路退款。以退款单号为幂等键：同一退款单号重复调用不会重复退款，返回首次的结果。
     */
    RefundResult refund(String payNo, String refundNo, Money refundAmount, Money totalAmount);

    /** 下载某天的交易账单（只含成功收款），用于 T+1 对账 */
    List<ChannelTrade> downloadBill(int channel, LocalDate date);

    enum TradeState { NOT_FOUND, NOT_PAID, SUCCESS, CLOSED }

    enum RefundState { SUCCESS, PROCESSING, FAILED }

    record RefundResult(RefundState state, String channelRefundNo, String message) {
    }

    record ChannelTrade(String payNo, TradeState state, String tradeNo, Money amount, LocalDateTime paidAt) {

        public static ChannelTrade notFound(String payNo) {
            return new ChannelTrade(payNo, TradeState.NOT_FOUND, null, null, null);
        }
    }
}
