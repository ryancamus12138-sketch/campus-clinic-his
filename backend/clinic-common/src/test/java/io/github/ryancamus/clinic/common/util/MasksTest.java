package io.github.ryancamus.clinic.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MasksTest {

    @Test
    void masksIdNumbersAndPhones() {
        assertThat(Masks.idNo("310101200001011234")).isEqualTo("3*************1234");
        assertThat(Masks.idNo("E12345678")).isEqualTo("E****5678");
        assertThat(Masks.idNo("A123")).isEqualTo("****");
        assertThat(Masks.phone("13800138000")).isEqualTo("138****8000");
        assertThat(Masks.phone(null)).isNull();
    }
}
