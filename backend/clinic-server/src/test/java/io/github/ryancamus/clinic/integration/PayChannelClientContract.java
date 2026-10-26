package io.github.ryancamus.clinic.integration;

import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundState;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.TradeState;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient;
import io.github.ryancamus.clinic.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 付款码渠道契约：收费业务依赖的渠道语义。测试替身与将来的真实实现（统一支付平台、微信、支付宝）都必须通过。
 * 真实实现的子类在对方的沙箱 / 测试环境中运行，{@link #authCode} 用沙箱买家的付款码。
 */
public abstract class PayChannelClientContract {

    protected abstract PayChannelClient client();

    protected abstract int channel();

    /** 一个可用的付款码 */
    protected abstract String authCode();

    private static String newPayNo() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    @Test
    void micropayChargesOnceAndAppearsInTheBill() {
        String payNo = newPayNo();
        var first = client().micropay(channel(), payNo, authCode(), Money.of("6.00"), "契约测试");
        var again = client().micropay(channel(), payNo, authCode(), Money.of("6.00"), "契约测试");
        assertThat(first.state()).isEqualTo(TradeState.SUCCESS);
        assertThat(again.tradeNo()).isEqualTo(first.tradeNo());
        var trade = client().query(payNo);
        assertThat(trade.state()).isEqualTo(TradeState.SUCCESS);
        assertThat(trade.amount()).isEqualTo(Money.of("6.00"));
        assertThat(client().downloadBill(channel(), LocalDate.now())).filteredOn(t -> t.payNo().equals(payNo))
                .hasSize(1);
    }

    @Test
    void paidTradeCannotBeClosedOnlyRefunded() {
        String payNo = newPayNo();
        client().micropay(channel(), payNo, authCode(), Money.of("2.50"), "契约测试");
        assertThat(client().close(payNo)).isFalse();
        assertThat(client().query(payNo).state()).isEqualTo(TradeState.SUCCESS);
    }

    @Test
    void unknownTradeIsReportedAsNotFound() {
        assertThat(client().query(newPayNo()).state()).isEqualTo(TradeState.NOT_FOUND);
    }

    @Test
    void refundIsIdempotentByRefundNoAndNeverExceedsThePaidAmount() {
        String payNo = newPayNo();
        client().micropay(channel(), payNo, authCode(), Money.of("3.00"), "契约测试");
        var first = client().refund(payNo, "R1-" + payNo, Money.of("2.00"), Money.of("3.00"));
        var again = client().refund(payNo, "R1-" + payNo, Money.of("2.00"), Money.of("3.00"));
        assertThat(first.state()).isEqualTo(RefundState.SUCCESS);
        assertThat(again.channelRefundNo()).isEqualTo(first.channelRefundNo());
        assertThat(client().refund(payNo, "R2-" + payNo, Money.of("1.50"), Money.of("3.00")).state())
                .isEqualTo(RefundState.FAILED);
    }
}
