package io.github.ryancamus.clinic.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.event.domain.entity.OutboxMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * 取一批到期事件并加锁；SKIP LOCKED 让多个投递器各取各的，不互相等待。
     * 同一聚合（如同一张收费单）前面还有未投递成功的事件时，后面的事件先不取，保证按序消费。
     */
    @Select("""
            SELECT * FROM outbox_message m
            WHERE m.status = 0 AND m.next_retry_time <= #{asOf}
              AND NOT EXISTS (SELECT 1 FROM outbox_message e
                              WHERE e.aggregate_id = m.aggregate_id AND e.status = 0 AND e.id < m.id)
            ORDER BY m.id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxMessage> lockDue(@Param("asOf") LocalDateTime asOf, @Param("limit") int limit);

    @Update("UPDATE outbox_message SET status = 1, last_error = NULL WHERE id = #{id}")
    int markDelivered(@Param("id") Long id);

    @Update("""
            UPDATE outbox_message
            SET status = #{status}, retry_count = retry_count + 1, next_retry_time = #{next}, last_error = #{error}
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id, @Param("status") int status, @Param("next") LocalDateTime next,
                   @Param("error") String error);

    /** 消费记录：插入成功（返回 1）才执行业务，重复投递时返回 0 */
    @Insert("INSERT IGNORE INTO event_consume_record (event_id, consumer, create_time) VALUES (#{eventId}, #{consumer}, NOW())")
    int insertConsumeRecord(@Param("eventId") Long eventId, @Param("consumer") String consumer);

    @Select("SELECT COUNT(*) FROM outbox_message WHERE status = #{status}")
    long countByStatus(@Param("status") int status);
}
