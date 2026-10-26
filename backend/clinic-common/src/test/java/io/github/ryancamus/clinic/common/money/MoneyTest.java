package io.github.ryancamus.clinic.common.money;

import io.github.ryancamus.clinic.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesToTwoDecimals() {
        assertThat(Money.of("1.005").amount()).isEqualByComparingTo("1.01");
        assertThat(Money.of("3").toString()).isEqualTo("3.00");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.of("-0.01")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> Money.of("1.00").subtract(Money.of("1.01"))).isInstanceOf(BizException.class);
    }

    @ParameterizedTest
    @CsvSource({"100.00,0.8,80.00", "12.35,0.8,9.88", "0.01,0.8,0.01", "33.33,0.8,26.66"})
    void ratioRoundsHalfUp(String amount, String rate, String expected) {
        assertThat(Money.of(amount).ratio(new BigDecimal(rate)).amount()).isEqualByComparingTo(expected);
    }

    @Test
    void allocateKeepsExactTotal() {
        Money total = Money.of("100.00");
        List<Money> parts = total.allocate(List.of(Money.of("1"), Money.of("1"), Money.of("1")));
        assertThat(parts).extracting(Money::toString).containsExactly("33.33", "33.33", "33.34");
        assertThat(Money.sum(parts)).isEqualTo(total);
    }

    @Test
    void allocateNeverLosesACentOnRandomInput() {
        Random random = new Random(20260101L);
        for (int round = 0; round < 10_000; round++) {
            Money total = new Money(BigDecimal.valueOf(random.nextInt(1_000_000), 2));
            int n = 1 + random.nextInt(5);
            List<Money> weights = random.ints(n, 1, 100_000)
                    .mapToObj(v -> new Money(BigDecimal.valueOf(v, 2)))
                    .toList();
            List<Money> parts = total.allocate(weights);
            assertThat(Money.sum(parts)).isEqualTo(total);
            assertThat(parts).allMatch(p -> p.amount().signum() >= 0);
        }
    }
}
