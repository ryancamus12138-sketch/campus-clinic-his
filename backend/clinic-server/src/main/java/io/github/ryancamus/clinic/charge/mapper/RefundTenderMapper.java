package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface RefundTenderMapper extends BaseMapper<RefundTender> {

    @Select("SELECT * FROM refund_tender WHERE refund_order_id = #{refundId} ORDER BY id")
    List<RefundTender> selectByRefund(@Param("refundId") Long refundId);

    @Update("""
            UPDATE refund_tender SET status = #{status}, channel_refund_no = #{channelRefundNo},
                   version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status NOT IN (1, 3)
            """)
    int markResult(@Param("id") Long id, @Param("status") int status, @Param("channelRefundNo") String channelRefundNo);

    @Update("UPDATE refund_tender SET status = 3, version = version + 1, update_time = NOW() WHERE id = #{id} AND status = 0")
    int markAwaitingConfirm(@Param("id") Long id);

    /** 收费员确认已当面退还：待确认 → 已退，条件更新保证只确认一次 */
    @Update("""
            UPDATE refund_tender SET status = 1, channel_refund_no = #{reference}, confirm_by = #{cashierId},
                   confirm_time = #{at}, confirm_shift_id = #{shiftId}, confirm_note = #{note},
                   version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 3
            """)
    int confirm(@Param("id") Long id, @Param("reference") String reference, @Param("cashierId") Long cashierId,
                @Param("at") LocalDateTime at, @Param("shiftId") Long shiftId, @Param("note") String note);
}
