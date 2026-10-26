package io.github.ryancamus.clinic.framework.web;

import io.github.ryancamus.clinic.common.money.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    private final JsonMapper mapper = build();

    private static JsonMapper build() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().clinicJacksonCustomizer().customize(builder);
        return builder.build();
    }

    record Sample(Long id, Money amount, LocalDateTime time) {
    }

    @Test
    void writesIdsAsStringsMoneyWithTwoDecimalsAndReadableTime() {
        String json = mapper.writeValueAsString(
                new Sample(2026102611461431213L, new Money(new BigDecimal("12.3")), LocalDateTime.of(2026, 10, 26, 15, 5, 14)));
        Map<?, ?> map = mapper.readValue(json, Map.class);
        assertThat(map.get("id")).isEqualTo("2026102611461431213");
        assertThat(map.get("amount")).isEqualTo("12.30");
        // 页面直接显示，不出现 “2026-10-26T15:05:14”
        assertThat(map.get("time")).isEqualTo("2026-10-26 15:05:14");
    }

    @Test
    void readsBothTimeNotations() {
        LocalDateTime expected = LocalDateTime.of(2026, 10, 26, 8, 0, 0);
        assertThat(mapper.readValue("{\"time\":\"2026-10-26 08:00:00\"}", Sample.class).time()).isEqualTo(expected);
        assertThat(mapper.readValue("{\"time\":\"2026-10-26T08:00:00\"}", Sample.class).time()).isEqualTo(expected);
    }
}
