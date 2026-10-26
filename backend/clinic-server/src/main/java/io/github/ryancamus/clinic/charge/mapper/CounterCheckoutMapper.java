package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckout;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface CounterCheckoutMapper extends BaseMapper<CounterCheckout> {

    @Update("""
            UPDATE counter_checkout SET status = #{to}, fail_reason = #{reason}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = #{from}
            """)
    int casStatus(@Param("id") Long id, @Param("from") int from, @Param("to") int to, @Param("reason") String reason);

    @Update("""
            UPDATE counter_checkout SET status = 4, resolve_note = #{note}, resolver_id = #{resolverId},
                   resolve_time = #{at}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 3
            """)
    int resolve(@Param("id") Long id, @Param("note") String note, @Param("resolverId") Long resolverId,
                @Param("at") LocalDateTime at);

    /** 阻止该收费单再收费、作废的结算：进行中（0）或待人工处理（3） */
    @Select("""
            SELECT * FROM counter_checkout WHERE charge_order_id = #{chargeOrderId} AND status IN (0, 3)
            ORDER BY id DESC LIMIT 1
            """)
    CounterCheckout selectBlocking(@Param("chargeOrderId") Long chargeOrderId);

    /** 进行中却长时间没有结束的结算（程序在收款途中中断） */
    @Select("SELECT * FROM counter_checkout WHERE status = 0 AND create_time < #{before} ORDER BY id LIMIT #{limit}")
    List<CounterCheckout> selectStale(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
