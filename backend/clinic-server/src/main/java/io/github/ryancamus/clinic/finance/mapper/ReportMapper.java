package io.github.ryancamus.clinic.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.finance.domain.entity.ReportDailyAgg;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ReportMapper extends BaseMapper<ReportDailyAgg> {

    /**
     * 从资金流水聚合一段日期（只读，不落库）。用于每日校准，以及当天的实时数据。
     * 收费行：原价合计 = 自付 + 统筹；退费行：只计入退款额（自付部分）。
     */
    @Select("""
            <script>
            SELECT ledger_date AS stat_date, campus_id, item_type, channel, patient_type,
                   COUNT(DISTINCT CASE WHEN biz_type = 1 THEN biz_no END) AS order_count,
                   COALESCE(SUM(CASE WHEN biz_type = 1 THEN amount + insurance_amount END), 0) AS total_amount,
                   COALESCE(SUM(CASE WHEN biz_type = 1 THEN insurance_amount END), 0) AS insurance_amount,
                   COALESCE(SUM(CASE WHEN biz_type = 1 THEN amount END), 0) AS self_amount,
                   COALESCE(-SUM(CASE WHEN biz_type = 2 THEN amount END), 0) AS refund_amount
            FROM fund_ledger
            WHERE ledger_date BETWEEN #{from} AND #{to}
              <if test="campusId != null">AND campus_id = #{campusId}</if>
            GROUP BY ledger_date, campus_id, item_type, channel, patient_type
            </script>
            """)
    List<ReportDailyAgg> aggregateLedger(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                         @Param("campusId") Long campusId);

    @Select("""
            <script>
            SELECT * FROM report_daily_agg
            WHERE stat_date BETWEEN #{from} AND #{to}
              <if test="campusId != null">AND campus_id = #{campusId}</if>
            </script>
            """)
    List<ReportDailyAgg> selectRange(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                     @Param("campusId") Long campusId);

    /** 按开单科室汇总流水；金额都是分组合计，交给 Java 端组装 */
    @Select("""
            <script>
            SELECT dept_id AS deptId,
                   COUNT(DISTINCT CASE WHEN biz_type = 1 THEN biz_no END) AS orderCount,
                   COALESCE(SUM(CASE WHEN biz_type = 1 THEN amount + insurance_amount END), 0) AS totalAmount,
                   COALESCE(SUM(CASE WHEN biz_type = 1 THEN insurance_amount END), 0) AS insuranceAmount,
                   COALESCE(SUM(CASE WHEN biz_type = 1 THEN amount END), 0) AS selfAmount,
                   COALESCE(-SUM(CASE WHEN biz_type = 2 THEN amount + insurance_amount END), 0) AS refundAmount
            FROM fund_ledger
            WHERE ledger_date BETWEEN #{from} AND #{to}
              <if test="campusId != null">AND campus_id = #{campusId}</if>
            GROUP BY dept_id
            </script>
            """)
    List<Map<String, Object>> sumByDept(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                        @Param("campusId") Long campusId);

    /** 按办理班次的收费员汇总窗口收退费（窗口收取的各种方式都计入办理人的班次） */
    @Select("""
            <script>
            SELECT s.cashier_id AS cashierId,
                   COUNT(DISTINCT CASE WHEN l.biz_type = 1 THEN l.biz_no END) AS chargeCount,
                   COALESCE(SUM(CASE WHEN l.biz_type = 1 THEN l.amount END), 0) AS chargeAmount,
                   COUNT(DISTINCT CASE WHEN l.biz_type = 2 THEN l.biz_no END) AS refundCount,
                   COALESCE(-SUM(CASE WHEN l.biz_type = 2 THEN l.amount END), 0) AS refundAmount
            FROM fund_ledger l
            JOIN cashier_shift s ON s.id = l.shift_id
            WHERE l.ledger_date BETWEEN #{from} AND #{to}
              <if test="campusId != null">AND l.campus_id = #{campusId}</if>
            GROUP BY s.cashier_id
            </script>
            """)
    List<Map<String, Object>> sumByCashier(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                           @Param("campusId") Long campusId);

    @Delete("DELETE FROM report_daily_agg WHERE stat_date = #{date}")
    int deleteDay(@Param("date") LocalDate date);
}
