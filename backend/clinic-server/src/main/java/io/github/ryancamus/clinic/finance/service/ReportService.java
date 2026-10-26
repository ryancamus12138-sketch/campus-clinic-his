package io.github.ryancamus.clinic.finance.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.domain.entity.ReportDailyAgg;
import io.github.ryancamus.clinic.finance.domain.vo.CashierWorkloadVO;
import io.github.ryancamus.clinic.finance.domain.vo.DeptRevenueVO;
import io.github.ryancamus.clinic.finance.domain.vo.ReportVO;
import io.github.ryancamus.clinic.finance.enums.FinanceErrorCode;
import io.github.ryancamus.clinic.finance.mapper.ReportMapper;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 收费统计。
 * <p>
 * 资金流水只追加、按记账日分区：过去的日期一旦校准就不会再变（退费记在退费当天），
 * 所以历史数据查预聚合表，只有“今天”实时扫当天分区。每日凌晨用流水重算前两天的预聚合作为兜底校准，
 * 重算是幂等的，任何时候都能手动重跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int MAX_DAYS = 366;

    private final ReportMapper reportMapper;
    private final OrgQueryApi orgQueryApi;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 用流水重算某一天的预聚合（先删后插，同一事务） */
    public int rebuildDay(LocalDate date) {
        List<ReportDailyAgg> rows = reportMapper.aggregateLedger(date, date, null);
        LocalDateTime now = LocalDateTime.now(clock);
        transactionTemplate.executeWithoutResult(tx -> {
            reportMapper.deleteDay(date);
            for (ReportDailyAgg row : rows) {
                row.setId(IdWorker.getId());
                row.setUpdateTime(now);
                reportMapper.insert(row);
            }
        });
        return rows.size();
    }

    /**
     * @param fromLedger true 时全部直接扫流水（核对与性能对照用）
     */
    public ReportVO summary(LocalDate from, LocalDate to, Long campusId, boolean fromLedger) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BizException(FinanceErrorCode.INVALID_RANGE);
        }
        List<ReportDailyAgg> rows;
        if (fromLedger) {
            rows = reportMapper.aggregateLedger(from, to, campusId);
        } else {
            LocalDate today = LocalDate.now(clock);
            rows = new ArrayList<>();
            if (from.isBefore(today)) {
                rows.addAll(reportMapper.selectRange(from, to.isBefore(today) ? to : today.minusDays(1), campusId));
            }
            if (!to.isBefore(today)) {
                rows.addAll(reportMapper.aggregateLedger(from.isAfter(today) ? from : today, to, campusId));
            }
        }
        return new ReportVO(from, to, fromLedger ? "ledger" : "agg", sum(null, rows),
                byDay(rows), group(rows, ReportDailyAgg::getItemType), group(rows, ReportDailyAgg::getChannel),
                group(rows, ReportDailyAgg::getPatientType));
    }

    private static List<ReportVO.DayLine> byDay(List<ReportDailyAgg> rows) {
        Map<LocalDate, List<ReportDailyAgg>> days = new TreeMap<>();
        rows.forEach(r -> days.computeIfAbsent(r.getStatDate(), k -> new ArrayList<>()).add(r));
        List<ReportVO.DayLine> result = new ArrayList<>();
        days.forEach((date, list) -> {
            ReportVO.Line s = sum(null, list);
            result.add(new ReportVO.DayLine(date, s.totalAmount(), s.insuranceAmount(), s.selfAmount(),
                    s.refundAmount(), s.netAmount()));
        });
        return result;
    }

    private static List<ReportVO.Line> group(List<ReportDailyAgg> rows, Function<ReportDailyAgg, Integer> key) {
        Map<Integer, List<ReportDailyAgg>> groups = new TreeMap<>();
        rows.forEach(r -> groups.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r));
        List<ReportVO.Line> result = new ArrayList<>();
        groups.forEach((k, list) -> result.add(sum(k, list)));
        return result;
    }

    private static ReportVO.Line sum(Integer key, List<ReportDailyAgg> rows) {
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal insurance = BigDecimal.ZERO;
        BigDecimal self = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        for (ReportDailyAgg r : rows) {
            count += r.getOrderCount();
            total = total.add(r.getTotalAmount());
            insurance = insurance.add(r.getInsuranceAmount());
            self = self.add(r.getSelfAmount());
            refund = refund.add(r.getRefundAmount());
        }
        return new ReportVO.Line(key, count, total, insurance, self, refund, self.subtract(refund));
    }

    /** 科室核算：按开单科室汇总收入与退费，直接扫流水（科室核算按月出，数据量可控） */
    public List<DeptRevenueVO> byDept(LocalDate from, LocalDate to, Long campusId) {
        checkRange(from, to);
        List<Map<String, Object>> rows = reportMapper.sumByDept(from, to, campusId);
        Map<Long, String> names = orgQueryApi.deptNames(rows.stream().map(r -> (Long) toLong(r.get("deptId")))
                .filter(Objects::nonNull).toList());
        return rows.stream().map(r -> {
            Long deptId = toLong(r.get("deptId"));
            Money total = money(r.get("totalAmount"));
            Money refund = money(r.get("refundAmount"));
            return new DeptRevenueVO(deptId, deptId == null ? "收费处直接划价" : names.get(deptId),
                    toLong(r.get("orderCount")).intValue(), total, money(r.get("insuranceAmount")),
                    money(r.get("selfAmount")), refund, total.subtract(refund));
        }).sorted(Comparator.comparing(DeptRevenueVO::netAmount).reversed()).toList();
    }

    /** 收费员工作量：窗口办理的收费与退费 */
    public List<CashierWorkloadVO> byCashier(LocalDate from, LocalDate to, Long campusId) {
        checkRange(from, to);
        List<Map<String, Object>> rows = reportMapper.sumByCashier(from, to, campusId);
        Map<Long, String> names = orgQueryApi.userNicknames(rows.stream().map(r -> toLong(r.get("cashierId"))).toList());
        return rows.stream().map(r -> {
            Long id = toLong(r.get("cashierId"));
            return new CashierWorkloadVO(id, names.get(id), toLong(r.get("chargeCount")).intValue(),
                    money(r.get("chargeAmount")), toLong(r.get("refundCount")).intValue(), money(r.get("refundAmount")));
        }).sorted(Comparator.comparing(CashierWorkloadVO::chargeCount).reversed()).toList();
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BizException(FinanceErrorCode.INVALID_RANGE);
        }
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Money money(Object v) {
        return v == null ? Money.ZERO : Money.of(new BigDecimal(v.toString()));
    }
}
