package io.github.ryancamus.clinic.charge.pay.mock;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.ChannelTrade;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundResult;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundState;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.TradeState;
import io.github.ryancamus.clinic.common.money.Money;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 付款码渠道（统一支付平台 / 微信 / 支付宝）一侧的测试替身（Test Double）。
 * 只存在于 src/test，仅用于自动化测试；不是生产环境的外部平台适配器，不会进入生产构建。
 * <p>
 * 在内存中提供同步扣款、查询、撤单、退款、日账单，可注入扣款失败、退款失败、账单差异，
 * 用于验证窗口收费、退费和对账在各种渠道结果下的处理，不代表与任何支付平台的真实联调。
 */
public class MockPayGateway {

    private final Clock clock;
    private final Map<String, Trade> trades = new ConcurrentHashMap<>();
    private final AtomicInteger failNextMicropays = new AtomicInteger();
    private final AtomicInteger failNextRefunds = new AtomicInteger();

    public MockPayGateway(Clock clock) {
        this.clock = clock;
    }

    public ChannelTrade query(String payNo) {
        Trade t = trades.get(payNo);
        if (t == null) {
            return ChannelTrade.notFound(payNo);
        }
        synchronized (t) {
            return t.snapshot();
        }
    }

    public boolean close(String payNo) {
        Trade t = trades.get(payNo);
        if (t == null) {
            return true;
        }
        synchronized (t) {
            if (t.state == TradeState.SUCCESS) {
                return false;
            }
            t.state = TradeState.CLOSED;
            return true;
        }
    }

    /**
     * 付款码支付：同步扣款（与真实渠道一致，付款码支付以同步结果和查询为准）。
     * 测试可用 {@link #failNextMicropays} 让接下来几笔扣款失败（如余额不足）。
     */
    public ChannelTrade micropay(int channel, String payNo, String authCode, Money amount, String subject) {
        LocalDateTime now = LocalDateTime.now(clock);
        Trade t = trades.computeIfAbsent(payNo, k -> new Trade(channel, payNo, amount));
        synchronized (t) {
            if (t.state == TradeState.NOT_PAID) {
                if (failNextMicropays.get() > 0 && failNextMicropays.getAndDecrement() > 0) {
                    t.state = TradeState.CLOSED;
                } else {
                    t.state = TradeState.SUCCESS;
                    t.tradeNo = "MOCK" + IdWorker.getId();
                    t.paidAt = now.withNano(0);
                }
            }
            return t.snapshot();
        }
    }

    /** 测试用：接下来 n 笔付款码支付扣款失败 */
    public void failNextMicropays(int n) {
        failNextMicropays.set(n);
    }

    /**
     * 退款：交易必须已成功，累计退款不超过交易金额；同一退款单号重复请求返回首次结果。
     */
    public RefundResult refund(String payNo, String refundNo, Money amount) {
        Trade t = trades.get(payNo);
        if (t == null) {
            return new RefundResult(RefundState.FAILED, null, "交易不存在");
        }
        synchronized (t) {
            RefundResult existing = t.refunds.get(refundNo);
            if (existing != null) {
                return existing;
            }
            if (t.state != TradeState.SUCCESS) {
                return new RefundResult(RefundState.FAILED, null, "交易未成功，不能退款");
            }
            if (t.refunded.add(amount).greaterThan(t.amount)) {
                return new RefundResult(RefundState.FAILED, null, "退款金额超过可退余额");
            }
            if (failNextRefunds.get() > 0 && failNextRefunds.getAndDecrement() > 0) {
                return new RefundResult(RefundState.FAILED, null, "模拟渠道退款失败");
            }
            t.refunded = t.refunded.add(amount);
            RefundResult ok = new RefundResult(RefundState.SUCCESS, "MOCKR" + IdWorker.getId(), null);
            t.refunds.put(refundNo, ok);
            return ok;
        }
    }

    /** 测试用：接下来 n 次退款请求返回失败 */
    public void failNextRefunds(int n) {
        failNextRefunds.set(n);
    }

    public Money refundedAmount(String payNo) {
        Trade t = trades.get(payNo);
        if (t == null) {
            return Money.ZERO;
        }
        synchronized (t) {
            return t.refunded;
        }
    }

    // ------------------------------------------------------------------ 对账单

    private final Map<String, ChannelTrade> billExtras = new ConcurrentHashMap<>();
    private final Set<String> billHidden = ConcurrentHashMap.newKeySet();
    private final Map<String, Money> billAmountOverride = new ConcurrentHashMap<>();
    private final Map<String, Integer> billExtraChannel = new ConcurrentHashMap<>();

    /** 某渠道某天的成功交易账单（按渠道付款时间所在日期） */
    public List<ChannelTrade> bill(int channel, LocalDate date) {
        List<ChannelTrade> result = new ArrayList<>();
        for (Trade t : trades.values()) {
            ChannelTrade snap;
            synchronized (t) {
                snap = t.snapshot();
            }
            if (t.channel != channel || snap.state() != TradeState.SUCCESS || !snap.paidAt().toLocalDate().equals(date)
                    || billHidden.contains(t.payNo)) {
                continue;
            }
            Money amount = billAmountOverride.getOrDefault(t.payNo, snap.amount());
            result.add(new ChannelTrade(snap.payNo(), snap.state(), snap.tradeNo(), amount, snap.paidAt()));
        }
        billExtras.forEach((tradeNo, t) -> {
            if (billExtraChannel.get(tradeNo) == channel && t.paidAt().toLocalDate().equals(date)) {
                result.add(t);
            }
        });
        return result;
    }

    /** 测试注入：账单里多出一笔本系统没有的交易（长款） */
    public void injectBillTrade(int channel, String payNo, Money amount, LocalDateTime paidAt) {
        String tradeNo = "MOCKX" + IdWorker.getId();
        billExtras.put(tradeNo, new ChannelTrade(payNo, TradeState.SUCCESS, tradeNo, amount, paidAt));
        billExtraChannel.put(tradeNo, channel);
    }

    /** 测试注入：账单里漏掉一笔本系统已入账的交易（短款） */
    public void hideFromBill(String payNo) {
        billHidden.add(payNo);
    }

    /** 测试注入：账单金额与本系统不符 */
    public void overrideBillAmount(String payNo, Money amount) {
        billAmountOverride.put(payNo, amount);
    }

    private static final class Trade {
        final int channel;
        final String payNo;
        final Money amount;
        TradeState state = TradeState.NOT_PAID;
        Money refunded = Money.ZERO;
        final Map<String, RefundResult> refunds = new LinkedHashMap<>();
        String tradeNo;
        LocalDateTime paidAt;

        Trade(int channel, String payNo, Money amount) {
            this.channel = channel;
            this.payNo = payNo;
            this.amount = amount;
        }

        ChannelTrade snapshot() {
            return new ChannelTrade(payNo, state, tradeNo, amount, paidAt);
        }
    }
}
