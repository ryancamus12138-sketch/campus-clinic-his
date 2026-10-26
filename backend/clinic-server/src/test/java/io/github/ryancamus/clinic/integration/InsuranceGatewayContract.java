package io.github.ryancamus.clinic.integration;

import io.github.ryancamus.clinic.charge.enums.ChargeItemType;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway.Insured;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 医保结算接口约定。当前只有测试替身运行这套契约；将来接入医保信息平台时，真实实现须在医保测试环境中通过同一套契约。
 */
public abstract class InsuranceGatewayContract {

    protected abstract InsuranceGateway gateway();

    /** 测试用参保人（大学生医保），带就诊凭证 */
    protected abstract Insured student();

    private static final List<FeeLine> LINES = List.of(
            new FeeLine("JC-XCG", "血常规", ChargeItemType.EXAMINATION, Money.of("20.00"), 1, true),
            new FeeLine("TJ-SELF", "自费项目", ChargeItemType.TREATMENT, Money.of("5.00"), 1, false));

    @Test
    void preSettleSplitsFundAndCashWithoutLeavingASettlement() {
        var b = gateway().preSettle(student(), LINES);
        assertThat(b.totalAmount()).isEqualTo(Money.of("25.00"));
        assertThat(b.insuranceAmount().add(b.selfAmount())).isEqualTo(b.totalAmount());
        assertThat(b.lines().get(1).insuranceAmount()).isEqualTo(Money.ZERO);
    }

    @Test
    void settleIsIdempotentByBizNoAndMatchesPreSettle() {
        String bizNo = "C" + UUID.randomUUID().toString().substring(0, 12);
        var pre = gateway().preSettle(student(), LINES);
        var first = gateway().settle(bizNo, student(), LINES);
        var again = gateway().settle(bizNo, student(), LINES);
        assertThat(again.setlId()).isEqualTo(first.setlId());
        assertThat(first.fundPay()).isEqualTo(pre.insuranceAmount());
        assertThat(first.cashPay()).isEqualTo(pre.selfAmount());
        assertThat(gateway().statement(LocalDate.now())).anyMatch(s -> s.bizNo().equals(bizNo));
    }

    @Test
    void formalSettlementRequiresAVisitCredential() {
        String bizNo = "C" + UUID.randomUUID().toString().substring(0, 12);
        Insured noCredential = new Insured(student().patientId(), student().insuranceType());
        assertThatThrownBy(() -> gateway().settle(bizNo, noCredential, LINES)).isInstanceOf(RuntimeException.class);
        assertThat(gateway().statement(LocalDate.now())).noneMatch(s -> s.bizNo().equals(bizNo));
    }

    @Test
    void cancelIsIdempotentAndRemovesTheSettlementFromTheStatement() {
        String bizNo = "C" + UUID.randomUUID().toString().substring(0, 12);
        var s = gateway().settle(bizNo, student(), LINES);
        gateway().cancel(bizNo, s.setlId());
        gateway().cancel(bizNo, s.setlId());
        assertThat(gateway().statement(LocalDate.now())).noneMatch(x -> x.bizNo().equals(bizNo));
    }
}
