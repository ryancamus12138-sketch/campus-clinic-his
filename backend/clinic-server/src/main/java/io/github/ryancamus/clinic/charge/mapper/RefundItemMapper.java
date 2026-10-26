package io.github.ryancamus.clinic.charge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.charge.domain.entity.RefundItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RefundItemMapper extends BaseMapper<RefundItem> {

    /** 某条收费明细已退款成功的数量 */
    @Select("""
            SELECT COALESCE(SUM(ri.qty), 0) FROM refund_item ri
            JOIN refund_order ro ON ro.id = ri.refund_order_id
            WHERE ri.charge_item_id = #{chargeItemId} AND ro.status = 2
            """)
    int sumSucceededQty(@Param("chargeItemId") Long chargeItemId);
}
