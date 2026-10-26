package io.github.ryancamus.clinic.integration;

import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.invoice.FiscalInvoiceClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 财政电子票据契约：同一业务键只开一张票；红票冲抵原票。
 */
public abstract class FiscalInvoiceClientContract {

    protected abstract FiscalInvoiceClient client();

    @Test
    void sameBizKeyIssuesOneInvoiceAndRedInvoiceGetsItsOwnNumber() {
        String key = "C" + UUID.randomUUID();
        var blue = client().issue(key + ":1", 1, Money.of("20.00"), null);
        var again = client().issue(key + ":1", 1, Money.of("20.00"), null);
        assertThat(again.invoiceNo()).isEqualTo(blue.invoiceNo());
        var red = client().issue(key + ":2", 2, Money.of("20.00"), blue.invoiceNo());
        assertThat(red.invoiceNo()).isNotEqualTo(blue.invoiceNo());
    }
}
