package io.github.ryancamus.clinic.charge.campuscard.mock;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundResult;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundState;
import io.github.ryancamus.clinic.common.money.Money;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 校园一卡通接口 {@link io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway} 的测试替身（Test Double）。
 * 只存在于 src/test，仅用于自动化测试；不是生产环境的外部平台适配器，不会进入生产构建。
 * <p>
 * 卡余额与扣款流水保存在内存中，未设置过余额的卡默认余额 {@link #DEFAULT_BALANCE}；卡号以 {@code 9} 结尾的视为已挂失。
 * 用于验证扣款失败、余额不足、冲正和重复请求时收费流程的处理。
 */
@Slf4j
public class MockCampusCardGateway implements CampusCardGateway {

    public static final Money DEFAULT_BALANCE = Money.of("500.00");
    private static final Pattern CARD_NO = Pattern.compile("\\d{6,20}");

    private final Clock clock;
    private final Map<String, Money> balances = new ConcurrentHashMap<>();
    private final Map<String, Debit> debits = new ConcurrentHashMap<>();
    private final Map<String, Money> refunded = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();

    public MockCampusCardGateway(Clock clock) {
        this.clock = clock;
        log.warn("校园卡使用模拟实现：未连接学校一卡通平台");
    }

    @Override
    public synchronized Debit debit(String bizNo, String cardNo, Money amount) {
        Debit existing = debits.get(bizNo);
        if (existing != null) {
            return existing;
        }
        if (cardNo == null || !CARD_NO.matcher(cardNo).matches()) {
            throw new CampusCardException("校园卡号无效");
        }
        if (cardNo.endsWith("9")) {
            throw new CampusCardException("校园卡已挂失");
        }
        Money balance = balance(cardNo);
        if (amount.greaterThan(balance)) {
            throw new CampusCardException("校园卡余额不足（余额 " + balance + " 元）");
        }
        balances.put(cardNo, balance.subtract(amount));
        Debit d = new Debit(bizNo, cardNo, "TEST-CARD" + IdWorker.getId(), amount, LocalDateTime.now(clock).withNano(0));
        debits.put(bizNo, d);
        return d;
    }

    @Override
    public synchronized void reverse(String bizNo) {
        Debit d = debits.remove(bizNo);
        if (d != null) {
            balances.put(d.cardNo(), balance(d.cardNo()).add(d.amount().subtract(refundedOf(bizNo))));
        }
    }

    @Override
    public synchronized RefundResult refund(String bizNo, String refundNo, Money amount) {
        RefundResult existing = refunds.get(refundNo + "@" + bizNo);
        if (existing != null) {
            return existing;
        }
        Debit d = debits.get(bizNo);
        if (d == null) {
            return new RefundResult(RefundState.FAILED, null, "扣款记录不存在");
        }
        if (refundedOf(bizNo).add(amount).greaterThan(d.amount())) {
            return new RefundResult(RefundState.FAILED, null, "退款金额超过扣款金额");
        }
        refunded.merge(bizNo, amount, Money::add);
        balances.put(d.cardNo(), balance(d.cardNo()).add(amount));
        RefundResult ok = new RefundResult(RefundState.SUCCESS, "CARDR" + IdWorker.getId(), null);
        refunds.put(refundNo + "@" + bizNo, ok);
        return ok;
    }

    @Override
    public List<Debit> statement(LocalDate date) {
        return debits.values().stream().filter(d -> d.at().toLocalDate().equals(date)).toList();
    }

    public Money balance(String cardNo) {
        return balances.getOrDefault(cardNo, DEFAULT_BALANCE);
    }

    /** 测试用：设置卡余额 */
    public void setBalance(String cardNo, Money balance) {
        balances.put(cardNo, balance);
    }

    private Money refundedOf(String bizNo) {
        return refunded.getOrDefault(bizNo, Money.ZERO);
    }
}
