package io.github.ryancamus.clinic.charge;

import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeStatusTest {

    @Test
    void unpaidCanOnlyBePaidOrClosed() {
        assertThat(ChargeStatus.UNPAID.next()).containsExactlyInAnyOrder(ChargeStatus.PAID, ChargeStatus.CLOSED);
    }

    @Test
    void terminalStatesHaveNoExit() {
        assertThat(ChargeStatus.CLOSED.next()).isEmpty();
        assertThat(ChargeStatus.REFUNDED.next()).isEmpty();
    }

    @Test
    void paidOrderCanNeverBeClosed() {
        assertThat(ChargeStatus.PAID.canTransitTo(ChargeStatus.CLOSED)).isFalse();
        assertThat(ChargeStatus.PAID.canTransitTo(ChargeStatus.UNPAID)).isFalse();
        assertThat(ChargeStatus.PAID.isSettled()).isTrue();
    }
}
