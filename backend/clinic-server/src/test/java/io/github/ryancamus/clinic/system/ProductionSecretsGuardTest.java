package io.github.ryancamus.clinic.system;

import io.github.ryancamus.clinic.system.config.ProductionSecretsGuard;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产环境拒绝开发环境的公开密钥和占位值。
 */
class ProductionSecretsGuardTest {

    private static final String REAL_JWT = "Zk3q8l0yT0b2YpU6c9aV1s7wX4eR5tN2mJ8hG3fD6kL0pQ1z";

    @Test
    void activeOnlyUnderTheProdProfile() {
        assertThat(ProductionSecretsGuard.class.getAnnotation(Profile.class).value()).containsExactly("prod");
    }

    @Test
    void rejectsTheDevelopmentKeysAndThePlaceholderJwtSecret() {
        // application-dev.yml 中公开的开发密钥：守卫须自己从该文件中认出它们
        MockEnvironment env = new MockEnvironment()
                .withProperty("clinic.security.jwt.secret", "dev-only-secret-change-me-in-production-0123456789")
                .withProperty("clinic.crypto.index-key", "ysOy5m6YK7osPsMnoV+mk3f4dIE0pUT3b2DaKl7vx30=")
                .withProperty("clinic.crypto.keys.1", "S6O6m/q76co+EogOhjJclw==");
        assertThatThrownBy(() -> new ProductionSecretsGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clinic.security.jwt.secret")
                .hasMessageContaining("clinic.crypto.index-key")
                .hasMessageContaining("clinic.crypto.keys.1");
    }

    @Test
    void acceptsGeneratedSecrets() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("clinic.security.jwt.secret", REAL_JWT)
                .withProperty("clinic.crypto.index-key", "pY2mF8c1qH0vW7sJ4nR6tB9xK3eL5aZ0uD2gS8iO1wM=")
                .withProperty("clinic.crypto.keys.1", "q1W2e3R4t5Y6u7I8o9P0aA==");
        new ProductionSecretsGuard(env).afterPropertiesSet();
    }
}
