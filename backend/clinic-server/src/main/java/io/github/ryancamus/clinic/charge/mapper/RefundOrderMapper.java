package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.RefundOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface RefundOrderMapper extends BaseMapper<RefundOrder> {

    @Update("""
            UPDATE refund_order SET status = #{to}, approver_id = COALESCE(#{approverId}, approver_id),
                   version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = #{from}
            """)
    int casStatus(@Param("id") Long id, @Param("from") int from, @Param("to") int to,
                  @Param("approverId") Long approverId);

    /** 审批（通过或驳回）：记录审批人、时间和意见，只对待审核的生效一次 */
    @Update("""
            UPDATE refund_order SET status = #{to}, approver_id = #{approverId}, approve_time = #{at},
                   approve_note = #{note}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 0
            """)
    int decide(@Param("id") Long id, @Param("to") int to, @Param("approverId") Long approverId,
               @Param("at") LocalDateTime at, @Param("note") String note);

    @Update("""
            UPDATE refund_order SET status = 2, channel_refund_no = #{channelRefundNo}, success_time = #{at},
                   version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 1
            """)
    int markSuccess(@Param("id") Long id, @Param("channelRefundNo") String channelRefundNo,
                    @Param("at") LocalDateTime at);

    @Update("""
            UPDATE refund_order SET status = 3, retry_count = retry_count + 1, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND status = 1
            """)
    int markFailed(@Param("id") Long id);

    @Select("SELECT * FROM refund_order WHERE idempotency_key = #{key}")
    RefundOrder selectByIdempotencyKey(@Param("key") String key);

    /** 需要重试的：退款中超过一段时间仍无结果的，以及失败且未超过重试上限的 */
    @Select("""
            SELECT * FROM refund_order
            WHERE (status = 1 AND update_time < #{before}) OR (status = 3 AND retry_count < #{maxRetry})
            ORDER BY id LIMIT #{limit}
            """)
    List<RefundOrder> selectRetryable(@Param("before") LocalDateTime before, @Param("maxRetry") int maxRetry,
                                      @Param("limit") int limit);
}
