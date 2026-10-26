package io.github.ryancamus.clinic.integration;

import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.campuscard.mock.MockCampusCardGateway;
import io.github.ryancamus.clinic.charge.config.ChargeProperties;
import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.fee.FeePolicyResolver;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.charge.insurance.mock.MockInsuranceGateway;
import io.github.ryancamus.clinic.charge.insurance.mock.MockInsuranceProperties;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient;
import io.github.ryancamus.clinic.charge.pay.mock.MockPayChannelClient;
import io.github.ryancamus.clinic.charge.pay.mock.MockPayGateway;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.invoice.FiscalInvoiceClient;
import io.github.ryancamus.clinic.finance.invoice.MockFiscalInvoiceClient;
import org.junit.jupiter.api.Nested;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 测试替身运行接口约定（契约）测试，确认替身的行为符合收费业务依赖的接口语义。
 * 这些测试只验证接口约定和异常处理，不代表与任何外部平台完成联调；当前版本未接入任何外部平台。
 */
class MockAdaptersContractTest {

    @Nested
    class MockPay extends PayChannelClientContract {

        private final MockPayGateway gateway = new MockPayGateway(Clock.systemDefaultZone());
        private final MockPayChannelClient client = new MockPayChannelClient(gateway);

        @Override
        protected PayChannelClient client() {
            return client;
        }

        @Override
        protected int channel() {
            return PayChannel.WECHAT.code();
        }

        @Override
        protected String authCode() {
            return "134567890123456789";
        }
    }

    @Nested
    class MockCampusCard extends CampusCardGatewayContract {

        private final MockCampusCardGateway gateway = new MockCampusCardGateway(Clock.systemDefaultZone());

        @Override
        protected CampusCardGateway gateway() {
            return gateway;
        }

        @Override
        protected String cardWithBalance(Money balance) {
            String cardNo = "2026" + (100000 + ThreadLocalRandom.current().nextInt(80000) * 10 + 1);
            gateway.setBalance(cardNo, balance);
            return cardNo;
        }
    }

    @Nested
    class MockInsurance extends InsuranceGatewayContract {

        private final MockInsuranceGateway gateway = new MockInsuranceGateway(new FeePolicyResolver(
                new ChargeProperties(Duration.ofHours(2), new BigDecimal("200.00"),
                        List.of(new ChargeProperties.FeePolicyConfig("STU", "大学生医保（示例）", List.of(1),
                                new BigDecimal("0.80"))))),
                new MockInsuranceProperties(0), Clock.systemDefaultZone());

        @Override
        protected InsuranceGateway gateway() {
            return gateway;
        }

        @Override
        protected InsuranceGateway.Insured student() {
            return new InsuranceGateway.Insured(90001L, 1, InsuranceGateway.CertType.E_VOUCHER, "E-VOUCHER-TOKEN");
        }
    }

    @Nested
    class MockFiscal extends FiscalInvoiceClientContract {

        private final MockFiscalInvoiceClient client = new MockFiscalInvoiceClient(0);

        @Override
        protected FiscalInvoiceClient client() {
            return client;
        }
    }
}
