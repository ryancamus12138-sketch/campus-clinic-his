package io.github.ryancamus.clinic.finance.invoice;

import io.github.ryancamus.clinic.common.money.Money;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 财政电子票据接口 {@link io.github.ryancamus.clinic.finance.invoice.FiscalInvoiceClient} 的测试替身（Test Double）。
 * 只存在于 src/test，仅用于自动化测试；不是生产环境的外部平台适配器，不会进入生产构建。
 * <p>
 * 按业务键幂等，可按概率失败，用于验证发件箱投递与开票重试。生产环境没有任何实现，票据由财务人工开具后登记票号。
 */
public class MockFiscalInvoiceClient implements FiscalInvoiceClient {

    private final Map<String, Issued> issued = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1_000_000);
    private final AtomicLong calls = new AtomicLong();
    private volatile double failRate;

    public MockFiscalInvoiceClient(@Value("${clinic.invoice.mock.fail-rate:0}") double failRate) {
        this.failRate = failRate;
    }

    @Override
    public Issued issue(String bizKey, int invoiceType, Money amount, String originalInvoiceNo) {
        calls.incrementAndGet();
        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            throw new FiscalException("模拟票据平台繁忙");
        }
        return issued.computeIfAbsent(bizKey, k -> {
            // 测试替身的票据号带 TEST 前缀
            String no = "TEST" + sequence.incrementAndGet();
            return new Issued(no, "/mock-invoice/" + no + ".pdf");
        });
    }

    public void setFailRate(double failRate) {
        this.failRate = failRate;
    }

    /** 平台上实际开出的票据张数（同一业务键只算一张） */
    public long calls() {
        return calls.get();
    }
}
