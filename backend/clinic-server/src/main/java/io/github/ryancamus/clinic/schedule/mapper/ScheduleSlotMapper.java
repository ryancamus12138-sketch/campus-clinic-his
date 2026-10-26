package io.github.ryancamus.clinic.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ScheduleSlotMapper extends BaseMapper<ScheduleSlot> {

    /**
     * 扣减号源：数据库层的最终防线。
     * 余量为 0 时影响行数为 0，即使 Redis 因故障多放进了请求，也不会超卖。
     */
    @Update("""
            UPDATE schedule_slot
            SET remaining = remaining - 1, version = version + 1, update_time = NOW()
            WHERE id = #{slotId} AND status = 1 AND remaining > 0
            """)
    int decrRemaining(@Param("slotId") Long slotId);

    /** 合并扣减：一次扣 k 个，余量不足 k 时影响 0 行，由调用方改为逐条处理 */
    @Update("""
            UPDATE schedule_slot
            SET remaining = remaining - #{count}, version = version + 1, update_time = NOW()
            WHERE id = #{slotId} AND status = 1 AND remaining >= #{count}
            """)
    int decrRemainingBy(@Param("slotId") Long slotId, @Param("count") int count);

    /** 归还号源；上限为总量，防止重复归还导致余量超过总数 */
    @Update("""
            UPDATE schedule_slot
            SET remaining = remaining + 1, version = version + 1, update_time = NOW()
            WHERE id = #{slotId} AND remaining < total
            """)
    int incrRemaining(@Param("slotId") Long slotId);

    /** 批量排班；与已有时段冲突（同院区、科室、类型、日期、开始时间、医生）的自动跳过 */
    int insertIgnoreBatch(@Param("slots") List<ScheduleSlot> slots);
}
