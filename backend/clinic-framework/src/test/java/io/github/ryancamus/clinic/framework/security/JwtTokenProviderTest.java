package io.github.ryancamus.clinic.framework.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123456";
    private static final Instant NOW = Instant.parse("2026-03-01T08:00:00Z");

    private static ClinicSecurityProperties props(String secret) {
        return new ClinicSecurityProperties(List.of(), List.of(), 5, Duration.ofMinutes(15),
                new ClinicSecurityProperties.Jwt(secret, "campus-clinic-his", Duration.ofMinutes(30), Duration.ofDays(7)),
                new ClinicSecurityProperties.Password(10, 3));
    }

    private static JwtTokenProvider providerAt(Instant instant) {
        return new JwtTokenProvider(props(SECRET), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static LoginUser cashier() {
        return LoginUser.builder()
                .userId(1890000000000000001L)
                .username("cashier01")
                .nickname("测试收费员")
                .userType(1)
                .campusId(1L)
                .roles(Set.of("CASHIER"))
                .permissions(Set.of("charge:settle"))
                .build();
    }

    @Test
    void roundTripKeepsIdentityAndAuthorities() {
        JwtTokenProvider provider = providerAt(NOW);
        JwtTokenProvider.AccessToken token = provider.createAccessToken(cashier());

        JwtTokenProvider.ParsedToken parsed = provider.parse(token.token()).orElseThrow();

        assertThat(parsed.jti()).isEqualTo(token.jti());
        assertThat(parsed.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        LoginUser user = parsed.user();
        assertThat(user.getUserId()).isEqualTo(1890000000000000001L);
        assertThat(user.getUserType()).isEqualTo(1);
        assertThat(user.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_CASHIER", "charge:settle");
    }

    @Test
    void rejectsExpiredToken() {
        String token = providerAt(NOW).createAccessToken(cashier()).token();
        assertThat(providerAt(NOW.plus(Duration.ofMinutes(31))).parse(token)).isEmpty();
    }

    @Test
    void rejectsTamperedToken() {
        String token = providerAt(NOW).createAccessToken(cashier()).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(providerAt(NOW).parse(tampered)).isEmpty();
        assertThat(providerAt(NOW).parse("not-a-jwt")).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtTokenProvider other = new JwtTokenProvider(props("another-secret-another-secret-another-1"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        String token = other.createAccessToken(cashier()).token();
        assertThat(providerAt(NOW).parse(token)).isEmpty();
    }

    @Test
    void refusesShortSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider(props("too-short"), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
