package io.github.ryancamus.clinic.charge.insurance.mock;

import io.github.ryancamus.clinic.charge.fee.FeePolicyResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 注册医保结算的测试替身。只由测试支持类 {@link io.github.ryancamus.clinic.support.TestDoubles} 导入，生产代码不会加载；
 * 测试中设置 {@code clinic.test-doubles.insurance=false} 即可去掉，用于验证“平台未接入”时的行为。
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "clinic.test-doubles.insurance", havingValue = "true", matchIfMissing = true)
public class MockInsuranceConfig {

    @Bean
    public MockInsuranceGateway mockInsuranceGateway(FeePolicyResolver policies, MockInsuranceProperties properties,
                                                     Clock clock) {
        return new MockInsuranceGateway(policies, properties, clock);
    }
}
