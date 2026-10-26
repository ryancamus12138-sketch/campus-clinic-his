package io.github.ryancamus.clinic.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.schedule.domain.entity.Registration;
import io.github.ryancamus.clinic.schedule.domain.row.VisitRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface RegistrationMapper extends BaseMapper<Registration> {

    String VISIT_COLUMNS = """
            SELECT r.id, r.reg_no, r.patient_id, r.status, s.campus_id, s.dept_id, s.slot_date, s.start_time
            FROM registration r JOIN schedule_slot s ON s.id = r.slot_id
            """;

    @Select("<script>" + VISIT_COLUMNS + """
            WHERE s.slot_date = #{date} AND s.campus_id = #{campusId} AND s.slot_type = 1 AND r.status IN (1, 2)
            <if test="deptId != null">AND s.dept_id = #{deptId}</if>
            ORDER BY s.start_time, r.id
            LIMIT 500
            </script>""")
    List<VisitRow> selectVisits(@Param("campusId") Long campusId, @Param("deptId") Long deptId,
                                @Param("date") LocalDate date);

    @Select(VISIT_COLUMNS + " WHERE r.id = #{id}")
    VisitRow selectVisit(@Param("id") Long id);

    /** 批量插入（审计字段由调用方填好）；任一条违反唯一约束则整条语句失败 */
    int insertBatch(@Param("list") List<Registration> registrations);

    /**
     * 状态迁移（CAS）：只有当前状态等于 from 时才更新。
     * 超时释放与付款确认同时发生时，只有先到的那一个能成功，另一个返回 0 行。
     */
    @Update("""
            UPDATE registration
            SET status = #{to}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = #{from}
            """)
    int casStatus(@Param("id") Long id, @Param("from") int from, @Param("to") int to);

    /**
     * 状态迁移，并要求关联的收费单没有变化（null 安全比较）。
     * 释放挂号前会先关掉它的收费单；若期间收费窗口又为它生成了新的收费单，这里返回 0 行，留给下一轮处理。
     */
    @Update("""
            UPDATE registration
            SET status = #{to}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = #{from} AND charge_order_id <=> #{chargeOrderId}
            """)
    int casStatusWithCharge(@Param("id") Long id, @Param("from") int from, @Param("to") int to,
                            @Param("chargeOrderId") Long chargeOrderId);

    /** 关联收费单：只有待支付且尚未关联时才成功 */
    @Update("""
            UPDATE registration
            SET charge_order_id = #{chargeOrderId}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 0 AND charge_order_id IS NULL
            """)
    int linkChargeOrder(@Param("id") Long id, @Param("chargeOrderId") Long chargeOrderId);

    @Select("""
            SELECT id, slot_id, patient_id, charge_order_id
            FROM registration
            WHERE status = 0 AND expire_time < #{now}
            ORDER BY expire_time
            LIMIT #{limit}
            """)
    List<Registration> selectExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT patient_id FROM registration WHERE slot_id = #{slotId} AND status IN (0, 1)")
    List<Long> selectActivePatientIds(@Param("slotId") Long slotId);

    /** 占用号源的预约数：待支付、已预约、已就诊（就诊后号源不归还） */
    @Select("SELECT COUNT(*) FROM registration WHERE slot_id = #{slotId} AND status IN (0, 1, 2)")
    int countOccupying(@Param("slotId") Long slotId);
}
