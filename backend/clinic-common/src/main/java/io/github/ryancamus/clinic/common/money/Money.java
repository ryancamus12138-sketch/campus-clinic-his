package io.github.ryancamus.clinic.common.money;

import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.exception.CommonErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 金额值对象：不可变、非负、固定两位小数（单位：元）。
 * <p>
 * 系统中所有金额运算都经过本类，业务代码里不出现 double/float。
 * 数据库映射为 DECIMAL(12,2)，见框架层的 MoneyTypeHandler。
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new BizException(CommonErrorCode.NEGATIVE_AMOUNT);
        }
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Money of(String yuan) {
        return new Money(new BigDecimal(yuan));
    }

    public static Money of(BigDecimal yuan) {
        return yuan == null ? null : new Money(yuan);
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    /** 结果为负时抛出业务异常，调用方无需自己判断 */
    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money multiply(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)));
    }

    /** 按比例取值（如医保比例 0.8），四舍五入到分 */
    public Money ratio(BigDecimal rate) {
        return new Money(amount.multiply(rate));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean greaterThan(Money other) {
        return compareTo(other) > 0;
    }

    /**
     * 按权重分摊，保证各份之和严格等于原值。
     * <p>
     * 除最后一份外都向下取整到分，尾差全部落在最后一份，因此最后一份不会为负。
     * 用于混合支付原路退款、整单减免按明细摊销等场景。
     */
    public List<Money> allocate(List<Money> weights) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("weights must not be empty");
        }
        BigDecimal totalWeight = weights.stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() == 0) {
            throw new IllegalArgumentException("total weight must be positive");
        }
        List<Money> parts = new ArrayList<>(weights.size());
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < weights.size() - 1; i++) {
            BigDecimal part = amount.multiply(weights.get(i).amount())
                    .divide(totalWeight, SCALE, RoundingMode.DOWN);
            parts.add(new Money(part));
            allocated = allocated.add(part);
        }
        parts.add(new Money(amount.subtract(allocated)));
        return parts;
    }

    public static Money sum(List<Money> values) {
        return values.stream().reduce(ZERO, Money::add);
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
