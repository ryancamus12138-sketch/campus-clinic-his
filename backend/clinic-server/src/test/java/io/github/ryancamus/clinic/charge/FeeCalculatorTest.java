package io.github.ryancamus.clinic.charge;

import io.github.ryancamus.clinic.charge.fee.FeeCalculator.Breakdown;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.ChargedLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.RefundSplit;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator;
import io.github.ryancamus.clinic.charge.fee.FeePolicy;
import io.github.ryancamus.clinic.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeeCalculatorTest {

    private static final FeePolicy STUDENT = new FeePolicy("STU", "大学生医保（示例）", new BigDecimal("0.80"));

    @Test
    void studentPaysTwentyPercentOfInsurableItemsAndAllOfTheRest() {
        Breakdown b = FeeCalculator.calculate(List.of(
                line("GH-PT", "1.00", 1, true),
                line("JC-XCG", "20.00", 1, true),
                line("YM-FW", "25.00", 1, false)), STUDENT);

        assertThat(b.lines()).extracting(l -> l.insuranceAmount().toString())
                .containsExactly("0.80", "16.00", "0.00");
        assertThat(b.lines()).extracting(l -> l.selfAmount().toString())
                .containsExactly("0.20", "4.00", "25.00");
        assertThat(b.totalAmount()).isEqualTo(Money.of("46.00"));
        assertThat(b.insuranceAmount()).isEqualTo(Money.of("16.80"));
        assertThat(b.selfAmount()).isEqualTo(Money.of("29.20"));
    }

    @Test
    void selfPayPolicyChargesEverything() {
        Breakdown b = FeeCalculator.calculate(List.of(line("JC-XCG", "20.00", 2, true)), FeePolicy.SELF_PAY);
        assertThat(b.insuranceAmount()).isEqualTo(Money.ZERO);
        assertThat(b.selfAmount()).isEqualTo(Money.of("40.00"));
    }

    @Test
    void roundingResidueStaysWithinTheLine() {
        // 0.33 × 0.85 = 0.2805 → 统筹 0.28，自付 0.05，合计仍是 0.33
        Breakdown b = FeeCalculator.calculate(List.of(line("X", "0.33", 1, true)),
                new FeePolicy("T", "测试", new BigDecimal("0.85")));
        ChargedLine l = b.lines().getFirst();
        assertThat(l.insuranceAmount()).isEqualTo(Money.of("0.28"));
        assertThat(l.selfAmount()).isEqualTo(Money.of("0.05"));
    }

    @Test
    void partialRefundsAddUpExactlyToTheOriginal() {
        // 10.00 × 3，统筹 0.8：统筹 24.00、自付 6.00；三次各退一件
        ChargedLine l = FeeCalculator.calculate(List.of(line("X", "10.00", 3, true)), STUDENT).lines().getFirst();
        RefundSplit r1 = FeeCalculator.refund(l, 0, 1);
        RefundSplit r2 = FeeCalculator.refund(l, 1, 1);
        RefundSplit r3 = FeeCalculator.refund(l, 2, 1);
        assertThat(r1.selfAmount().add(r2.selfAmount()).add(r3.selfAmount())).isEqualTo(Money.of("6.00"));
        assertThat(r1.amount().add(r2.amount()).add(r3.amount())).isEqualTo(Money.of("30.00"));
        assertThatThrownBy(() -> FeeCalculator.refund(l, 2, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratioOutsideZeroToOneIsRejected() {
        assertThatThrownBy(() -> new FeePolicy("X", "x", new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * M4：10 万张随机收费单（随机单价、数量、是否可报销、报销比例），每行再随机分多次退完。
     * 断言分毫不差：行内 统筹+自付=金额；整单合计=各行之和；累计退款不超过原值、退完恰好等于原值。
     */
    @Test
    void hundredThousandRandomOrdersReconcileToTheCent() {
        Random random = new Random(20260625L);
        BigDecimal[] ratios = {BigDecimal.ZERO, new BigDecimal("0.80"), new BigDecimal("0.85"),
                new BigDecimal("0.90"), new BigDecimal("0.7"), new BigDecimal("0.3333")};
        int orders = 100_000;
        long lines = 0;
        long refunds = 0;
        BigDecimal grandTotal = BigDecimal.ZERO;
        BigDecimal grandSplit = BigDecimal.ZERO;
        BigDecimal grandRefunded = BigDecimal.ZERO;

        for (int i = 0; i < orders; i++) {
            FeePolicy policy = new FeePolicy("R", "随机", ratios[random.nextInt(ratios.length)]);
            List<FeeLine> feeLines = new ArrayList<>();
            int n = 1 + random.nextInt(8);
            for (int k = 0; k < n; k++) {
                String price = BigDecimal.valueOf(1 + random.nextInt(99_999), 2).toPlainString();
                feeLines.add(line("I" + k, price, 1 + random.nextInt(20), random.nextBoolean()));
            }
            Breakdown b = FeeCalculator.calculate(feeLines, policy);
            lines += n;

            Money sumAmount = Money.ZERO;
            Money sumIns = Money.ZERO;
            Money sumSelf = Money.ZERO;
            for (ChargedLine l : b.lines()) {
                assertThat(l.amount()).isEqualTo(l.line().unitPrice().multiply(l.line().qty()));
                assertThat(l.insuranceAmount().add(l.selfAmount())).isEqualTo(l.amount());
                sumAmount = sumAmount.add(l.amount());
                sumIns = sumIns.add(l.insuranceAmount());
                sumSelf = sumSelf.add(l.selfAmount());

                // 随机分多次退完
                int refunded = 0;
                Money refundAmount = Money.ZERO;
                Money refundSelf = Money.ZERO;
                while (refunded < l.line().qty()) {
                    int k = 1 + random.nextInt(l.line().qty() - refunded);
                    RefundSplit r = FeeCalculator.refund(l, refunded, k);
                    assertThat(r.insuranceAmount().add(r.selfAmount())).isEqualTo(r.amount());
                    refunded += k;
                    refundAmount = refundAmount.add(r.amount());
                    refundSelf = refundSelf.add(r.selfAmount());
                    assertThat(refundAmount.compareTo(l.amount())).isLessThanOrEqualTo(0);
                    refunds++;
                }
                assertThat(refundAmount).isEqualTo(l.amount());
                assertThat(refundSelf).isEqualTo(l.selfAmount());
                grandRefunded = grandRefunded.add(refundAmount.amount());
            }
            assertThat(b.totalAmount()).isEqualTo(sumAmount);
            assertThat(b.insuranceAmount()).isEqualTo(sumIns);
            assertThat(b.selfAmount()).isEqualTo(sumSelf);
            assertThat(b.insuranceAmount().add(b.selfAmount())).isEqualTo(b.totalAmount());
            grandTotal = grandTotal.add(b.totalAmount().amount());
            grandSplit = grandSplit.add(b.insuranceAmount().amount()).add(b.selfAmount().amount());
        }
        System.out.printf("[M4] orders=%d lines=%d refunds=%d total=%s split=%s refunded=%s diff=%s%n",
                orders, lines, refunds, grandTotal, grandSplit, grandRefunded, grandTotal.subtract(grandSplit));
        assertThat(grandSplit).isEqualByComparingTo(grandTotal);
        assertThat(grandRefunded).isEqualByComparingTo(grandTotal);
    }

    private static FeeLine line(String code, String price, int qty, boolean insurable) {
        return new FeeLine(code, code, 3, Money.of(price), qty, insurable);
    }
}
