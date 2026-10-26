package io.github.ryancamus.clinic.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.pharmacy.domain.entity.StockLock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 锁定记录的数量变更。MySQL 单表 UPDATE 的赋值从左到右求值，status 放在最后，按更新后的数量推导。
 */
public interface StockLockMapper extends BaseMapper<StockLock> {

    String STATUS = """
            status = CASE WHEN qty - dispensed_qty - released_qty > 0 THEN 1
                          WHEN dispensed_qty = 0 THEN 3
                          WHEN returned_qty = dispensed_qty THEN 4
                          ELSE 2 END""";

    /**
     * 锁住某条明细的全部锁定记录（按 id 升序）。发药、退药、释放都先做这一步，
     * 同一明细上的并发操作在这里排队，之后基于确定的数量计算分配。
     * <p>
     * 必须走 (charge_item_id, batch_id) 索引：表小时优化器会为 ORDER BY id 选全表扫描，
     * 加锁读会逐行等待别的事务持有的行，两个事务各持一行互等，形成死锁。
     */
    @Select("""
            SELECT * FROM stock_lock FORCE INDEX (uk_item_batch)
            WHERE charge_item_id = #{chargeItemId}
            ORDER BY id
            FOR UPDATE
            """)
    List<StockLock> selectForUpdate(@Param("chargeItemId") Long chargeItemId);

    @Update("UPDATE stock_lock SET dispensed_qty = dispensed_qty + #{qty}, version = version + 1, update_time = NOW(), "
            + STATUS + " WHERE id = #{id} AND qty - dispensed_qty - released_qty >= #{qty}")
    int dispense(@Param("id") Long id, @Param("qty") int qty);

    @Update("UPDATE stock_lock SET released_qty = released_qty + #{qty}, version = version + 1, update_time = NOW(), "
            + STATUS + " WHERE id = #{id} AND qty - dispensed_qty - released_qty >= #{qty}")
    int release(@Param("id") Long id, @Param("qty") int qty);

    @Update("UPDATE stock_lock SET returned_qty = returned_qty + #{qty}, version = version + 1, update_time = NOW(), "
            + STATUS + " WHERE id = #{id} AND dispensed_qty - returned_qty >= #{qty}")
    int returnQty(@Param("id") Long id, @Param("qty") int qty);
}
