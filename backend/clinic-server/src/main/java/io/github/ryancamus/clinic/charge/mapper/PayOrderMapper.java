package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /** 原路退款累加；不超过该笔实收金额（与 CHECK 约束双重保证） */
    @Update("""
            UPDATE pay_order SET refunded_amount = refunded_amount + #{amount}, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND status = 1 AND refunded_amount + #{amount} <= amount
            """)
    int addRefunded(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /** 某渠道在某时间段内成功的收款（按渠道付款时间） */
    @Select("""
            SELECT * FROM pay_order
            WHERE channel = #{channel} AND status = 1 AND success_time >= #{from} AND success_time < #{to}
            """)
    List<PayOrder> selectSucceeded(@Param("channel") int channel, @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Select("SELECT * FROM pay_order WHERE pay_no = #{payNo}")
    PayOrder selectByPayNo(@Param("payNo") String payNo);

    @Select("SELECT * FROM pay_order WHERE idempotency_key = #{key}")
    PayOrder selectByIdempotencyKey(@Param("key") String key);
}
