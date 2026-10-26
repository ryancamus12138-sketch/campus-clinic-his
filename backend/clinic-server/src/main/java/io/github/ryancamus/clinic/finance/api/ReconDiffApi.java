package io.github.ryancamus.clinic.finance.api;

import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;

/**
 * 资金差异登记：渠道对账发现的长款、短款、金额不符，登记为差异由财务处理（原路退回或补记）。
 * 同一笔差异重复对账时只登记一次。
 */
public interface ReconDiffApi {

    /** 长款：渠道有款、系统不应收（如关单后到账、同一收费单被两笔支付付清） */
    int LONG = 1;
    /** 短款：系统已记收、渠道无此交易 */
    int SHORT = 2;
    /** 金额不符 */
    int AMOUNT_MISMATCH = 3;

    /** 同一差异重复登记时忽略 */
    void record(Diff diff);

    record Diff(LocalDate reconDate, int channel, int diffType, String payNo, String channelTradeNo,
                Money ourAmount, Money channelAmount) {
    }
}
