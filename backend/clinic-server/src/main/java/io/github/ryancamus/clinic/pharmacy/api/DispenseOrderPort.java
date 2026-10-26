package io.github.ryancamus.clinic.pharmacy.api;

import java.util.List;
import java.util.Optional;

/**
 * 药房读取、更新收费单发药进度的端口。接口定义在药房模块，由收费模块实现（依赖倒置）：
 * 收费模块本来就依赖药房（开单要锁库存），药房反过来直接依赖收费会形成循环。
 * <p>
 * 两个更新方法都是条件更新，返回 false 表示数量不满足（已发完、或已申请退费），调用方据此失败回滚。
 */
public interface DispenseOrderPort {

    /** 已缴费（含部分退费）的收费单及其药品、卫生材料明细；未缴费或不存在返回空 */
    Optional<DispenseOrder> findPaidOrder(String chargeNo);

    /** 发药数量增加：要求 已退费 + 已发药 + qty ≤ 数量 */
    boolean markDispensed(Long chargeItemId, int qty);

    /** 退药数量回退：要求 已发药 ≥ qty */
    boolean markReturned(Long chargeItemId, int qty);

    record DispenseOrder(Long chargeOrderId, String chargeNo, Long patientId, Long campusId, int status,
                         List<DispenseItem> items) {
    }

    /**
     * @param refundedQty 已退费或退费处理中的数量，这部分不能再发
     */
    record DispenseItem(Long chargeItemId, String itemCode, String itemName, int qty, int dispensedQty,
                        int refundedQty) {

        public int dispensable() {
            return qty - dispensedQty - refundedQty;
        }
    }
}
