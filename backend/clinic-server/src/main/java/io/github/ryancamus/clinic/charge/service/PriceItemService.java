package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.entity.PriceItem;
import io.github.ryancamus.clinic.charge.domain.vo.PriceItemVO;
import io.github.ryancamus.clinic.charge.mapper.PriceItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceItemService {

    private final PriceItemMapper priceItemMapper;
    private final Clock clock;

    public List<PriceItemVO> listCurrent(Integer itemType) {
        LocalDateTime now = LocalDateTime.now(clock);
        return priceItemMapper.selectList(Wrappers.<PriceItem>lambdaQuery()
                        .eq(itemType != null, PriceItem::getItemType, itemType)
                        .le(PriceItem::getEffectiveFrom, now)
                        .and(w -> w.isNull(PriceItem::getEffectiveTo).or().gt(PriceItem::getEffectiveTo, now))
                        .orderByAsc(PriceItem::getItemType, PriceItem::getItemCode))
                .stream()
                .map(p -> new PriceItemVO(p.getItemCode(), p.getItemName(), p.getItemType(), p.getUnit(),
                        p.getUnitPrice(), p.getInsuranceClass() != null && p.getInsuranceClass() == 1))
                .toList();
    }
}
