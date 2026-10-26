package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.dto.PriceItemCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.PriceVersionDTO;
import io.github.ryancamus.clinic.charge.domain.entity.PriceItem;
import io.github.ryancamus.clinic.charge.domain.vo.PriceItemAdminVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.mapper.PriceItemMapper;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收费项目维护。价格分版本：调价插入一个新版本，并把当前版本的生效区间截止到新版本的生效时间；
 * 划价时按当时生效的版本取价，所以可以提前录入物价部门通知的调价，到点自动切换，已划价的收费单不受影响。
 * 同一项目同时只允许一个待生效的调价。
 */
@Service
@RequiredArgsConstructor
public class PriceItemAdminService {

    private final PriceItemMapper mapper;
    private final Clock clock;

    public List<PriceItemAdminVO> list(Integer itemType) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, List<PriceItem>> byCode = mapper.selectList(Wrappers.<PriceItem>lambdaQuery()
                        .eq(itemType != null, PriceItem::getItemType, itemType)
                        .and(w -> w.isNull(PriceItem::getEffectiveTo).or().gt(PriceItem::getEffectiveTo, now)))
                .stream().collect(Collectors.groupingBy(PriceItem::getItemCode));
        return byCode.values().stream().map(versions -> toVO(versions, now))
                .sorted(Comparator.comparing(PriceItemAdminVO::itemType).thenComparing(PriceItemAdminVO::itemCode))
                .toList();
    }

    @Transactional
    public PriceItemAdminVO create(PriceItemCreateDTO dto) {
        BizException.throwIf(mapper.exists(Wrappers.<PriceItem>lambdaQuery().eq(PriceItem::getItemCode, dto.itemCode())),
                ChargeErrorCode.PRICE_ITEM_EXISTS);
        PriceItem item = new PriceItem();
        item.setItemCode(dto.itemCode());
        item.setItemName(dto.itemName().trim());
        item.setInsuranceCode(dto.insuranceCode());
        item.setItemType(dto.itemType());
        item.setUnit(dto.unit().trim());
        item.setUnitPrice(Money.of(dto.unitPrice()));
        item.setInsuranceClass(dto.insuranceClass());
        // DATETIME 列按秒四舍五入，截掉毫秒，避免存进去的生效时间比现在晚不到一秒
        item.setEffectiveFrom(LocalDateTime.now(clock).withNano(0));
        mapper.insert(item);
        return toVO(List.of(item), LocalDateTime.now(clock));
    }

    @Transactional
    public PriceItemAdminVO schedule(String itemCode, PriceVersionDTO dto) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PriceItem> open = openVersions(itemCode);
        PriceItem current = current(open, now);
        BizException.throwIf(open.stream().anyMatch(v -> v.getEffectiveFrom().isAfter(now)),
                ChargeErrorCode.PRICE_PENDING_EXISTS);
        BizException.throwIf(dto.effectiveFrom().isBefore(now.withNano(0).minusMinutes(1))
                || !dto.effectiveFrom().isAfter(current.getEffectiveFrom()), ChargeErrorCode.PRICE_VERSION_INVALID);
        current.setEffectiveTo(dto.effectiveFrom());
        mapper.updateById(current);
        PriceItem next = new PriceItem();
        next.setItemCode(current.getItemCode());
        next.setItemName(current.getItemName());
        next.setInsuranceCode(current.getInsuranceCode());
        next.setItemType(current.getItemType());
        next.setUnit(current.getUnit());
        next.setUnitPrice(Money.of(dto.unitPrice()));
        next.setInsuranceClass(dto.insuranceClass());
        next.setEffectiveFrom(dto.effectiveFrom());
        mapper.insert(next);
        return toVO(openVersions(itemCode), now);
    }

    /** 撤销尚未生效的调价，当前版本恢复为长期有效 */
    @Transactional
    public PriceItemAdminVO cancelPending(String itemCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PriceItem> open = openVersions(itemCode);
        PriceItem pending = open.stream().filter(v -> v.getEffectiveFrom().isAfter(now)).findFirst()
                .orElseThrow(() -> new BizException(ChargeErrorCode.PRICE_NO_PENDING));
        PriceItem current = current(open, now);
        mapper.deleteById(pending.getId());
        mapper.update(Wrappers.<PriceItem>lambdaUpdate().set(PriceItem::getEffectiveTo, null)
                .eq(PriceItem::getId, current.getId()));
        return toVO(openVersions(itemCode), now);
    }

    /** 尚未结束的版本：当前版本和待生效版本 */
    private List<PriceItem> openVersions(String itemCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PriceItem> list = mapper.selectList(Wrappers.<PriceItem>lambdaQuery()
                .eq(PriceItem::getItemCode, itemCode)
                .and(w -> w.isNull(PriceItem::getEffectiveTo).or().gt(PriceItem::getEffectiveTo, now))
                .orderByAsc(PriceItem::getEffectiveFrom));
        BizException.throwIf(list.isEmpty(), ChargeErrorCode.PRICE_ITEM_NOT_FOUND);
        return list;
    }

    private static PriceItem current(List<PriceItem> open, LocalDateTime now) {
        return open.stream().filter(v -> !v.getEffectiveFrom().isAfter(now)).reduce((a, b) -> b)
                .orElseThrow(() -> new BizException(ChargeErrorCode.PRICE_ITEM_NOT_FOUND));
    }

    private static PriceItemAdminVO toVO(List<PriceItem> versions, LocalDateTime now) {
        List<PriceItem> sorted = versions.stream().sorted(Comparator.comparing(PriceItem::getEffectiveFrom)).toList();
        PriceItem cur = sorted.stream().filter(v -> !v.getEffectiveFrom().isAfter(now)).reduce((a, b) -> b)
                .orElse(sorted.get(0));
        PriceItem pending = sorted.stream().filter(v -> v.getEffectiveFrom().isAfter(now)).findFirst().orElse(null);
        return new PriceItemAdminVO(cur.getItemCode(), cur.getItemName(), cur.getInsuranceCode(), cur.getItemType(),
                cur.getUnit(), version(cur), pending == null ? null : version(pending));
    }

    private static PriceItemAdminVO.Version version(PriceItem v) {
        return new PriceItemAdminVO.Version(v.getUnitPrice(), v.getInsuranceClass(), v.getEffectiveFrom(),
                v.getEffectiveTo());
    }
}
