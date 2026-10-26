package io.github.ryancamus.clinic.finance.api;

import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;
import java.util.Map;

/**
 * 资金流水记账。流水只追加不修改：退费记负数（红冲），日结、报表、对账都以流水为准。
 * <p>
 * 必须在业务事务内调用，与支付状态变更同时提交或同时回滚。
 */
public interface FundLedgerApi {

    /**
     * 记录一笔收款，按项目类型拆成多行，便于按收入科目出报表。
     * 唯一键（业务类型、单号、项目类型、记账日）是防止重复入账的第二道防线。
     */
    void recordPayment(PaymentEntry entry);

    /**
     * 记录一笔退款：按项目类型记负数（红冲），原收款流水不修改，历史日报不受影响。
     *
     * @param entry payNo 字段填退费单号
     */
    void recordRefund(PaymentEntry entry);

    /**
     * @param amountByItemType 项目类型 → 金额拆分；各项自付之和必须等于实收（实退）金额
     */
    /**
     * @param deptId 开单科室，可为空（收费处直接划价）
     */
    record PaymentEntry(LocalDate ledgerDate, Long campusId, Long deptId, Long shiftId, String payNo, int channel,
                        int patientType, Map<Integer, Split> amountByItemType) {
    }

    /**
     * @param self      个人自付（现金流）
     * @param insurance 医保统筹记账（非现金，报表用）
     */
    record Split(Money self, Money insurance) {

        public Split add(Split other) {
            return new Split(self.add(other.self), insurance.add(other.insurance));
        }
    }
}
