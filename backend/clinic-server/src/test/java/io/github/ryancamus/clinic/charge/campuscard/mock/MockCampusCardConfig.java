package io.github.ryancamus.clinic.charge.campuscard.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 注册校园一卡通的测试替身。只由测试支持类 {@link io.github.ryancamus.clinic.support.TestDoubles} 导入，生产代码不会加载；
 * 测试中设置 {@code clinic.test-doubles.campus-card=false} 即可去掉，用于验证“平台未接入”时的行为。
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "clinic.test-doubles.campus-card", havingValue = "true", matchIfMissing = true)
public class MockCampusCardConfig {

    @Bean
    public MockCampusCardGateway mockCampusCardGateway(Clock clock) {
        return new MockCampusCardGateway(clock);
    }
}
