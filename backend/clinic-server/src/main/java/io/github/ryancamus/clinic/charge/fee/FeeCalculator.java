package io.github.ryancamus.clinic.charge.fee;

import io.github.ryancamus.clinic.common.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 费用计算。纯函数，不依赖 Spring 和数据库，便于用大量随机数据做性质测试。
 * <p>
 * 规则（每条都由测试守住）：
 * <ol>
 *   <li>每行：金额 = 单价 × 数量；统筹 = 金额 × 比例（四舍五入到分）；自付 = 金额 − 统筹。
 *       自付用减法得到，而不是再乘一次比例，所以“统筹 + 自付 = 金额”恒成立。</li>
 *   <li>整单的各项合计 = 各行之和，不在整单层面重新乘比例，避免行与单对不上。</li>
 *   <li>部分退费：退 k 件时按“累计已退件数”计算累计应退金额，再与之前累计值相减；
 *       最后一次退完时累计值就是原金额。因此无论分几次退，退款合计都不会超过原金额，
 *       退完时恰好相等，不需要额外存储每次的尾差。</li>
 * </ol>
 */
public final class FeeCalculator {

    private FeeCalculator() {
    }

    /**
     * @param insurable 是否属于医保可报销范围
     */
    public record FeeLine(String itemCode, String itemName, int itemType, Money unitPrice, int qty,
                          boolean insurable) {

        public FeeLine {
            if (qty <= 0) {
                throw new IllegalArgumentException("qty must be positive");
            }
        }
    }

    public record ChargedLine(FeeLine line, Money amount, BigDecimal insuranceRatio, Money insuranceAmount,
                              Money selfAmount) {
    }

    public record Breakdown(FeePolicy policy, List<ChargedLine> lines, Money totalAmount, Money insuranceAmount,
                            Money selfAmount) {
    }

    /** 一次退费在某一行上的拆分 */
    public record RefundSplit(Money amount, Money insuranceAmount, Money selfAmount) {
    }

    public static Breakdown calculate(List<FeeLine> lines, FeePolicy policy) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        List<ChargedLine> charged = new ArrayList<>(lines.size());
        Money total = Money.ZERO;
        Money insurance = Money.ZERO;
        Money self = Money.ZERO;
        for (FeeLine line : lines) {
            Money amount = line.unitPrice().multiply(line.qty());
            BigDecimal ratio = line.insurable() ? policy.ratio() : BigDecimal.ZERO.setScale(4);
            Money ins = amount.ratio(ratio);
            Money selfPart = amount.subtract(ins);
            charged.add(new ChargedLine(line, amount, ratio, ins, selfPart));
            total = total.add(amount);
            insurance = insurance.add(ins);
            self = self.add(selfPart);
        }
        return new Breakdown(policy, List.copyOf(charged), total, insurance, self);
    }

    /**
     * 计算某一行本次退 refundQty 件应退的金额。
     *
     * @param refundedQty 本次之前已退件数
     */
    public static RefundSplit refund(ChargedLine line, int refundedQty, int refundQty) {
        int qty = line.line().qty();
        if (refundQty <= 0 || refundedQty < 0 || refundedQty + refundQty > qty) {
            throw new IllegalArgumentException("invalid refund qty");
        }
        Money ins = cumulative(line.insuranceAmount(), qty, refundedQty + refundQty)
                .subtract(cumulative(line.insuranceAmount(), qty, refundedQty));
        Money self = cumulative(line.selfAmount(), qty, refundedQty + refundQty)
                .subtract(cumulative(line.selfAmount(), qty, refundedQty));
        return new RefundSplit(ins.add(self), ins, self);
    }

    /** 退满 n 件时的累计退款：未退完向下取整到分，退完等于原值 */
    private static Money cumulative(Money part, int qty, int n) {
        if (n == qty) {
            return part;
        }
        return new Money(part.amount().multiply(BigDecimal.valueOf(n))
                .divide(BigDecimal.valueOf(qty), Money.SCALE, RoundingMode.DOWN));
    }
}
