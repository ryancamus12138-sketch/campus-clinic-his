package io.github.ryancamus.clinic.finance.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.ryancamus.clinic.finance.api.FundLedgerApi;
import io.github.ryancamus.clinic.finance.domain.entity.FundLedger;
import io.github.ryancamus.clinic.finance.mapper.FundLedgerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class FundLedgerService implements FundLedgerApi {

    private final FundLedgerMapper ledgerMapper;
    private final Clock clock;

    @Override
    public void recordPayment(PaymentEntry entry) {
        record(entry, FundLedger.BIZ_PAYMENT, 1);
    }

    @Override
    public void recordRefund(PaymentEntry entry) {
        record(entry, FundLedger.BIZ_REFUND, -1);
    }

    private void record(PaymentEntry entry, int bizType, int sign) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 按项目类型排序插入，多个事务并发记账时加锁顺序一致
        for (Map.Entry<Integer, Split> e : new TreeMap<>(entry.amountByItemType()).entrySet()) {
            Split split = e.getValue();
            if (split.self().isZero() && split.insurance().isZero()) {
                continue;
            }
            FundLedger row = new FundLedger();
            row.setId(IdWorker.getId());
            row.setLedgerDate(entry.ledgerDate());
            row.setCampusId(entry.campusId());
            row.setDeptId(entry.deptId());
            row.setShiftId(entry.shiftId());
            row.setBizType(bizType);
            row.setBizNo(entry.payNo());
            row.setChannel(entry.channel());
            row.setItemType(e.getKey());
            row.setPatientType(entry.patientType());
            row.setAmount(sign > 0 ? split.self().amount() : split.self().amount().negate());
            row.setInsuranceAmount(sign > 0 ? split.insurance().amount() : split.insurance().amount().negate());
            row.setCreateTime(now);
            ledgerMapper.insert(row);
        }
    }
}
