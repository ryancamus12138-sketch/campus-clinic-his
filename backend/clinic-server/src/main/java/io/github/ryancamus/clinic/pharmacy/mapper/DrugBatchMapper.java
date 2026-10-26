package io.github.ryancamus.clinic.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 批次库存的所有变更都是带条件的增量更新，条件不满足返回 0 行，由调用方回滚或重试；
 * 不存在“先查余量、再写回”的读改写。
 */
public interface DrugBatchMapper extends BaseMapper<DrugBatch> {

    /** 候选批次（未过近效期、有在库），按 id 升序，用于随后逐行加锁 */
    @Select("""
            <script>
            SELECT id FROM drug_batch
            WHERE campus_id = #{campusId} AND drug_id IN
              <foreach collection="drugIds" item="d" open="(" separator="," close=")">#{d}</foreach>
              AND expire_date &gt; #{minExpire} AND qty_on_hand &gt; 0
            ORDER BY id
            </script>
            """)
    List<Long> selectCandidateIds(@Param("campusId") Long campusId, @Param("drugIds") Collection<Long> drugIds,
                                  @Param("minExpire") LocalDate minExpire);

    /** 锁住一个批次行并读取最新数量 */
    @Select("SELECT * FROM drug_batch WHERE id = #{id} FOR UPDATE")
    DrugBatch selectForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE drug_batch SET qty_locked = qty_locked + #{qty}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND qty_on_hand - qty_locked >= #{qty}
            """)
    int lockQty(@Param("id") Long id, @Param("qty") int qty);

    @Update("""
            UPDATE drug_batch SET qty_locked = qty_locked - #{qty}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND qty_locked >= #{qty}
            """)
    int unlockQty(@Param("id") Long id, @Param("qty") int qty);

    /** 发药：锁定量转为实扣 */
    @Update("""
            UPDATE drug_batch
            SET qty_on_hand = qty_on_hand - #{qty}, qty_locked = qty_locked - #{qty},
                version = version + 1, update_time = NOW()
            WHERE id = #{id} AND qty_locked >= #{qty}
            """)
    int dispenseQty(@Param("id") Long id, @Param("qty") int qty);

    @Update("""
            UPDATE drug_batch SET qty_on_hand = qty_on_hand + #{qty}, version = version + 1, update_time = NOW()
            WHERE id = #{id}
            """)
    int returnQty(@Param("id") Long id, @Param("qty") int qty);

    /** 入库：同院区、同药品、同批号累加 */
    @Insert("""
            INSERT INTO drug_batch (id, campus_id, drug_id, batch_no, expire_date, purchase_price, qty_on_hand,
                                    qty_locked, create_time, update_time, create_by, update_by, version)
            VALUES (#{id}, #{campusId}, #{drugId}, #{batchNo}, #{expireDate}, #{purchasePrice}, #{qty}, 0,
                    NOW(), NOW(), #{operator}, #{operator}, 0)
            ON DUPLICATE KEY UPDATE qty_on_hand = qty_on_hand + VALUES(qty_on_hand), version = version + 1,
                                    update_time = NOW(), update_by = VALUES(update_by)
            """)
    int upsertInbound(@Param("id") Long id, @Param("campusId") Long campusId, @Param("drugId") Long drugId,
                      @Param("batchNo") String batchNo, @Param("expireDate") LocalDate expireDate,
                      @Param("purchasePrice") BigDecimal purchasePrice, @Param("qty") int qty,
                      @Param("operator") Long operator);
}
