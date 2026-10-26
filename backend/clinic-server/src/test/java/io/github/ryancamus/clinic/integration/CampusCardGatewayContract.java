package io.github.ryancamus.clinic.integration;

import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway.CampusCardException;
import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundState;
import io.github.ryancamus.clinic.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校园卡（一卡通）接口约定。当前只有测试替身运行这套契约；将来接入一卡通平台时，真实实现须在对方测试环境中通过同一套契约。
 */
public abstract class CampusCardGatewayContract {

    protected abstract CampusCardGateway gateway();

    /** 一张余额为 balance 的测试卡 */
    protected abstract String cardWithBalance(Money balance);

    private static String bizNo() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    @Test
    void debitIsIdempotentByBizNoAndAppearsInTheStatement() {
        String card = cardWithBalance(Money.of("50.00"));
        String bizNo = bizNo();
        var first = gateway().debit(bizNo, card, Money.of("12.00"));
        var again = gateway().debit(bizNo, card, Money.of("12.00"));
        assertThat(again.tradeNo()).isEqualTo(first.tradeNo());
        assertThat(gateway().statement(LocalDate.now())).filteredOn(d -> d.bizNo().equals(bizNo)).hasSize(1);
    }

    @Test
    void insufficientBalanceIsRejectedWithoutDebiting() {
        String card = cardWithBalance(Money.of("5.00"));
        String bizNo = bizNo();
        assertThatThrownBy(() -> gateway().debit(bizNo, card, Money.of("8.00")))
                .isInstanceOf(CampusCardException.class);
        assertThat(gateway().statement(LocalDate.now())).noneMatch(d -> d.bizNo().equals(bizNo));
    }

    @Test
    void reverseUndoesTheDebitAndIsIdempotent() {
        String card = cardWithBalance(Money.of("20.00"));
        String bizNo = bizNo();
        gateway().debit(bizNo, card, Money.of("20.00"));
        gateway().reverse(bizNo);
        gateway().reverse(bizNo);
        assertThat(gateway().statement(LocalDate.now())).noneMatch(d -> d.bizNo().equals(bizNo));
        // 冲正后余额恢复，可以再次消费
        gateway().debit(bizNo(), card, Money.of("20.00"));
    }

    @Test
    void refundIsIdempotentAndNeverExceedsTheDebit() {
        String card = cardWithBalance(Money.of("30.00"));
        String bizNo = bizNo();
        gateway().debit(bizNo, card, Money.of("10.00"));
        var first = gateway().refund(bizNo, "F1", Money.of("6.00"));
        var again = gateway().refund(bizNo, "F1", Money.of("6.00"));
        assertThat(first.state()).isEqualTo(RefundState.SUCCESS);
        assertThat(again.channelRefundNo()).isEqualTo(first.channelRefundNo());
        assertThat(gateway().refund(bizNo, "F2", Money.of("5.00")).state()).isEqualTo(RefundState.FAILED);
    }
}
