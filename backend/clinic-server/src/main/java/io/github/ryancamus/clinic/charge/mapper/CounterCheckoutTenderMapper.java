package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckoutTender;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface CounterCheckoutTenderMapper extends BaseMapper<CounterCheckoutTender> {

    @Update("""
            UPDATE counter_checkout_tender SET status = #{status}, channel_trade_no = #{tradeNo}, tender_ref = #{ref},
                   version = version + 1, update_time = NOW()
            WHERE pay_no = #{payNo}
            """)
    int mark(@Param("payNo") String payNo, @Param("status") int status, @Param("tradeNo") String tradeNo,
             @Param("ref") String ref);

    /** 财务逐笔核实：只能核实可能已扣款的记录，条件更新保证只核实一次 */
    @Update("""
            UPDATE counter_checkout_tender SET status = #{status}, verify_by = #{by}, verify_time = #{at},
                   verify_ref = #{ref}, verify_note = #{note}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND checkout_id = #{checkoutId} AND status IN (0, 1, 3, 6)
            """)
    int verify(@Param("id") Long id, @Param("checkoutId") Long checkoutId, @Param("status") int status,
               @Param("by") Long by, @Param("at") LocalDateTime at, @Param("ref") String ref,
               @Param("note") String note);

    @Select("SELECT * FROM counter_checkout_tender WHERE checkout_id = #{checkoutId} ORDER BY seq")
    List<CounterCheckoutTender> selectByCheckout(@Param("checkoutId") Long checkoutId);
}
