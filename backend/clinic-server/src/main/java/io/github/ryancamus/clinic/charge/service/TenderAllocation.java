package io.github.ryancamus.clinic.charge.service;

import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.api.FundLedgerApi.Split;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一张收费单由多种方式付清（或一笔退费按多种方式原路退回）时，把按项目类型汇总的自付金额分到各收款方式上，
 * 用于逐笔记资金流水。
 * <p>
 * 按项目类型顺序依次填满每种收款方式的金额（不按比例拆分），结果均为整分、无尾差，
 * 且每种收款方式各项目类型之和恰好等于该方式的金额。医保统筹部分不是现金流，全部记在第一种方式上，
 * 只用于报表的医保列。
 */
final class TenderAllocation {

    private TenderAllocation() {
    }

    /**
     * @param byItemType 项目类型 → 自付与统筹
     * @param amounts    各收款方式的金额，之和必须等于自付合计
     * @return 与 amounts 一一对应的项目类型拆分
     */
    static List<Map<Integer, Split>> allocate(Map<Integer, Split> byItemType, List<Money> amounts) {
        Money selfTotal = byItemType.values().stream().map(Split::self).reduce(Money.ZERO, Money::add);
        if (!Money.sum(amounts).equals(selfTotal)) {
            throw new IllegalArgumentException("tender amounts " + Money.sum(amounts) + " != self total " + selfTotal);
        }
        List<Map<Integer, Split>> result = new ArrayList<>();
        amounts.forEach(a -> result.add(new TreeMap<>()));
        int tender = 0;
        Money capacity = amounts.isEmpty() ? Money.ZERO : amounts.getFirst();
        for (Map.Entry<Integer, Split> e : new TreeMap<>(byItemType).entrySet()) {
            Money left = e.getValue().self();
            while (!left.isZero()) {
                while (capacity.isZero()) {
                    tender++;
                    capacity = amounts.get(tender);
                }
                Money take = left.greaterThan(capacity) ? capacity : left;
                result.get(tender).merge(e.getKey(), new Split(take, Money.ZERO), Split::add);
                left = left.subtract(take);
                capacity = capacity.subtract(take);
            }
            if (!e.getValue().insurance().isZero() && !result.isEmpty()) {
                result.getFirst().merge(e.getKey(), new Split(Money.ZERO, e.getValue().insurance()), Split::add);
            }
        }
        return result;
    }
}
