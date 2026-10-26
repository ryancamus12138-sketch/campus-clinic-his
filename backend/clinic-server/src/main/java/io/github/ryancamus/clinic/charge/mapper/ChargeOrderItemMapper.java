package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ChargeOrderItemMapper extends BaseMapper<ChargeOrderItem> {

    /** 收费单关闭后明细失效，释放 (ref_type, ref_id) 唯一约束，来源单据可以重新划价 */
    @Update("""
            UPDATE charge_order_item SET valid = 0, version = version + 1, update_time = NOW()
            WHERE charge_order_id = #{chargeOrderId} AND valid = 1
            """)
    int invalidateByOrder(@Param("chargeOrderId") Long chargeOrderId);

    /** 发药：已退费（含处理中）+ 已发药 + 本次 ≤ 数量 */
    @Update("""
            UPDATE charge_order_item SET dispensed_qty = dispensed_qty + #{qty}, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND valid = 1 AND refunded_qty + dispensed_qty + #{qty} <= qty
            """)
    int addDispensed(@Param("id") Long id, @Param("qty") int qty);

    @Update("""
            UPDATE charge_order_item SET dispensed_qty = dispensed_qty - #{qty}, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND dispensed_qty >= #{qty}
            """)
    int subtractDispensed(@Param("id") Long id, @Param("qty") int qty);

    /**
     * 预占退费数量。已发药的不能直接退费（必须先退药），条件与发药共用同一个不等式，
     * 并发的发药与退费申请在同一行上排队，不会出现“既发了药又退了钱”。
     */
    @Update("""
            UPDATE charge_order_item SET refunded_qty = refunded_qty + #{qty}, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND valid = 1 AND refunded_qty + dispensed_qty + #{qty} <= qty
            """)
    int reserveRefund(@Param("id") Long id, @Param("qty") int qty);

    /** 已就诊：挂号费明细的“已提供”数量记为未退部分，之后不能再退 */
    @Update("""
            UPDATE charge_order_item i JOIN charge_order o ON o.id = i.charge_order_id
            SET i.dispensed_qty = i.qty - i.refunded_qty, i.version = i.version + 1, i.update_time = NOW()
            WHERE o.registration_id = #{registrationId} AND o.source_type = 1 AND o.status IN (2, 3)
              AND i.item_type = 1 AND i.dispensed_qty < i.qty - i.refunded_qty
            """)
    int markRegistrationServed(@Param("registrationId") Long registrationId);

    /** 退费被驳回，归还预占的数量 */
    @Update("""
            UPDATE charge_order_item SET refunded_qty = refunded_qty - #{qty}, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND refunded_qty >= #{qty}
            """)
    int cancelRefund(@Param("id") Long id, @Param("qty") int qty);
}
