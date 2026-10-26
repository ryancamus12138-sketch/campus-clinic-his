package io.github.ryancamus.clinic.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.finance.domain.entity.CashierShift;
import io.github.ryancamus.clinic.finance.domain.vo.ShiftTotals;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface CashierShiftMapper extends BaseMapper<CashierShift> {

    @Select("SELECT * FROM cashier_shift WHERE cashier_id = #{cashierId} AND status = 0")
    CashierShift selectOpen(@Param("cashierId") Long cashierId);

    /**
     * 按班次汇总资金流水：收款分现金、卡（校园卡、银行卡）、扫码三列，退款合计；
     * 现金应有 = 现金收 − 现金退；POS 应有 = 银行卡收 − 银行卡退（与 POS 机结算单核对）
     */
    @Select("""
            SELECT
              COALESCE(SUM(CASE WHEN biz_type = 1 AND channel = 1 THEN amount END), 0) AS cash_amount,
              COALESCE(SUM(CASE WHEN biz_type = 1 AND channel IN (2, 6) THEN amount END), 0) AS card_amount,
              COALESCE(SUM(CASE WHEN biz_type = 1 AND channel IN (3, 4, 5, 7) THEN amount END), 0) AS online_amount,
              COALESCE(-SUM(CASE WHEN biz_type = 2 THEN amount END), 0) AS refund_amount,
              COALESCE(SUM(CASE WHEN channel = 1 THEN amount END), 0) AS expected_cash,
              COALESCE(SUM(CASE WHEN channel = 6 THEN amount END), 0) AS pos_expected,
              COUNT(CASE WHEN channel = 6 THEN 1 END) AS pos_count,
              COUNT(DISTINCT CASE WHEN biz_type = 1 THEN biz_no END) AS order_count
            FROM fund_ledger
            WHERE shift_id = #{shiftId}
            """)
    ShiftTotals sumLedger(@Param("shiftId") Long shiftId);

    @Update("""
            UPDATE cashier_shift
            SET status = 1, end_time = #{endTime}, cash_amount = #{t.cashAmount}, card_amount = #{t.cardAmount},
                online_amount = #{t.onlineAmount}, refund_amount = #{t.refundAmount},
                expected_cash = #{t.expectedCash}, order_count = #{t.orderCount}, counted_cash = #{countedCash},
                pos_expected = #{t.posExpected}, pos_counted = #{posCounted},
                diff_note = #{diffNote}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 0
            """)
    int handOver(@Param("id") Long id, @Param("t") ShiftTotals totals, @Param("countedCash") BigDecimal countedCash,
                 @Param("posCounted") BigDecimal posCounted, @Param("diffNote") String diffNote,
                 @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE cashier_shift SET status = 2, reviewer_id = #{reviewerId}, review_time = #{at}, review_note = #{note},
                   version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 1
            """)
    int review(@Param("id") Long id, @Param("reviewerId") Long reviewerId, @Param("note") String note,
               @Param("at") LocalDateTime at);
}
