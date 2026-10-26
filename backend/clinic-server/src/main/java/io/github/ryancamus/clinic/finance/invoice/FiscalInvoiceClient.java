package io.github.ryancamus.clinic.finance.invoice;

import io.github.ryancamus.clinic.common.money.Money;

/**
 * 财政电子票据平台。接口由各地财政部门规定，按当地票据平台文档实现；正式代码中目前没有实现（未接入），
 * 此时票据保持“待人工开具”，由财务开具后登记票号。测试中使用测试替身。
 * 以业务键幂等：同一 bizKey 重复请求返回同一张票。
 */
public interface FiscalInvoiceClient {

    /**
     * @param originalInvoiceNo 开红票时冲抵的原票号
     * @throws FiscalException 平台暂不可用或拒绝
     */
    Issued issue(String bizKey, int invoiceType, Money amount, String originalInvoiceNo);

    record Issued(String invoiceNo, String pdfUrl) {
    }

    class FiscalException extends RuntimeException {
        public FiscalException(String message) {
            super(message);
        }
    }
}
