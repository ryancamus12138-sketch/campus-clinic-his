package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface ChargeOrderMapper extends BaseMapper<ChargeOrder> {

    /**
     * 付清：只有待支付且金额恰好等于应付时才成功。
     * 返回 0 表示状态已变化（被作废或关单），调用方回滚整个结算事务并撤回本次收取的款项。
     */
    @Update("""
            UPDATE charge_order
            SET status = 2, paid_amount = #{amount}, paid_time = #{paidTime}, cashier_id = #{cashierId},
                shift_id = #{shiftId}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 0 AND payable_amount = #{amount}
            """)
    int markPaid(@Param("id") Long id, @Param("amount") BigDecimal amount, @Param("paidTime") LocalDateTime paidTime,
                 @Param("cashierId") Long cashierId, @Param("shiftId") Long shiftId);

    /**
     * 退款成功累加已退金额，并按是否退完推导状态（赋值从左到右求值，status 用的是累加后的值）。
     * 条件“已退 + 本次 ≤ 已付”保证无论多少笔退费并发完成，都不会超额。
     */
    @Update("""
            UPDATE charge_order
            SET refunded_amount = refunded_amount + #{amount},
                status = IF(refunded_amount = paid_amount, 4, 3),
                version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status IN (2, 3) AND refunded_amount + #{amount} <= paid_amount
            """)
    int addRefunded(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /** 锁住收费单这一行，直到当前事务结束（见 ChargeOrderGuard） */
    @Select("SELECT id FROM charge_order WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);

    @Update("""
            UPDATE charge_order
            SET status = #{to}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = #{from}
            """)
    int casStatus(@Param("id") Long id, @Param("from") int from, @Param("to") int to);

    @Select("""
            SELECT id FROM charge_order
            WHERE status = 0 AND expire_time < #{now}
            ORDER BY expire_time
            LIMIT #{limit}
            """)
    List<Long> selectExpiredUnpaidIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
