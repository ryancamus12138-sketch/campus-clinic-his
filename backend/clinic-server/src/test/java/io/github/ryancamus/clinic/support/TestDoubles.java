package io.github.ryancamus.clinic.support;

import io.github.ryancamus.clinic.charge.campuscard.mock.MockCampusCardConfig;
import io.github.ryancamus.clinic.charge.insurance.mock.MockInsuranceConfig;
import io.github.ryancamus.clinic.charge.insurance.mock.MockInsuranceProperties;
import io.github.ryancamus.clinic.charge.pay.mock.MockPayConfig;
import io.github.ryancamus.clinic.finance.invoice.MockFiscalInvoiceClient;
import io.github.ryancamus.clinic.pharmacy.trace.MockTraceCodeUploader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 外部平台的测试替身（Test Double）汇总，由 {@link IntegrationTest} 导入。只存在于测试代码中，正式程序里没有任何外部平台实现。
 * <p>
 * 替身按接口约定实现幂等、注入失败、超时和结果未知，用于验证收费流程在各种外部结果下的处理，不代表真实联调。
 * 某个测试要验证“平台未接入”时的行为，设置 {@code clinic.test-doubles.<平台>=false} 即可去掉对应替身。
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockInsuranceProperties.class)
@Import({MockPayConfig.class, MockCampusCardConfig.class, MockInsuranceConfig.class})
public class TestDoubles {

    @Bean
    @ConditionalOnProperty(name = "clinic.test-doubles.fiscal-invoice", havingValue = "true", matchIfMissing = true)
    MockFiscalInvoiceClient mockFiscalInvoiceClient(@Value("${clinic.invoice.mock.fail-rate:0}") double failRate) {
        return new MockFiscalInvoiceClient(failRate);
    }

    @Bean
    @ConditionalOnProperty(name = "clinic.test-doubles.trace-upload", havingValue = "true", matchIfMissing = true)
    MockTraceCodeUploader mockTraceCodeUploader() {
        return new MockTraceCodeUploader();
    }
}
