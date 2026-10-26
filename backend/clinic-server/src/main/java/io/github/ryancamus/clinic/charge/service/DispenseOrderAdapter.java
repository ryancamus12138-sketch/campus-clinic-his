package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.enums.ChargeItemType;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.pharmacy.api.DispenseOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 收费模块对药房端口的实现：只暴露已缴费收费单的药品明细，以及发药/退药数量的条件更新。
 */
@Component
@RequiredArgsConstructor
public class DispenseOrderAdapter implements DispenseOrderPort {

    private final ChargeOrderMapper orderMapper;
    private final ChargeOrderItemMapper itemMapper;

    @Override
    public Optional<DispenseOrder> findPaidOrder(String chargeNo) {
        ChargeOrder order = orderMapper.selectOne(Wrappers.<ChargeOrder>lambdaQuery()
                .eq(ChargeOrder::getChargeNo, chargeNo));
        if (order == null) {
            return Optional.empty();
        }
        ChargeStatus status = ChargeStatus.of(order.getStatus());
        if (status != ChargeStatus.PAID && status != ChargeStatus.PARTIAL_REFUNDED) {
            return Optional.empty();
        }
        var items = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                        .eq(ChargeOrderItem::getChargeOrderId, order.getId())
                        .eq(ChargeOrderItem::getValid, 1)
                        .orderByAsc(ChargeOrderItem::getId))
                .stream()
                .filter(i -> ChargeItemType.isGoods(i.getItemType()))
                .map(i -> new DispenseItem(i.getId(), i.getItemCode(), i.getItemName(), i.getQty(),
                        i.getDispensedQty(), i.getRefundedQty()))
                .toList();
        return Optional.of(new DispenseOrder(order.getId(), order.getChargeNo(), order.getPatientId(),
                order.getCampusId(), order.getStatus(), items));
    }

    @Override
    public boolean markDispensed(Long chargeItemId, int qty) {
        return itemMapper.addDispensed(chargeItemId, qty) == 1;
    }

    @Override
    public boolean markReturned(Long chargeItemId, int qty) {
        return itemMapper.subtractDispensed(chargeItemId, qty) == 1;
    }
}
