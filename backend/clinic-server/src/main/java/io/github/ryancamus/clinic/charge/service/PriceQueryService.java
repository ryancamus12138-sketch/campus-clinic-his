package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.api.PriceQueryApi;
import io.github.ryancamus.clinic.charge.domain.entity.PriceItem;
import io.github.ryancamus.clinic.charge.mapper.PriceItemMapper;
import io.github.ryancamus.clinic.framework.cache.LocalCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PriceQueryService implements PriceQueryApi {

    private final PriceItemMapper priceItemMapper;
    private final Clock clock;

    private final LocalCache<String, Optional<PriceSnapshot>> currentCache = LocalCache.of(5_000, Duration.ofSeconds(30));

    @Override
    public Optional<PriceSnapshot> findCurrentCached(String itemCode) {
        return currentCache.get(itemCode, code -> findEffective(code, LocalDateTime.now(clock)));
    }

    @Override
    public Optional<PriceSnapshot> findEffective(String itemCode, LocalDateTime at) {
        PriceItem item = priceItemMapper.selectOne(Wrappers.<PriceItem>lambdaQuery()
                .eq(PriceItem::getItemCode, itemCode)
                .le(PriceItem::getEffectiveFrom, at)
                .and(w -> w.isNull(PriceItem::getEffectiveTo).or().gt(PriceItem::getEffectiveTo, at))
                .orderByDesc(PriceItem::getEffectiveFrom)
                .last("LIMIT 1"));
        return Optional.ofNullable(item).map(PriceQueryService::toSnapshot);
    }

    @Override
    public Map<String, PriceSnapshot> findCurrent(Collection<String> itemCodes) {
        if (itemCodes.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return priceItemMapper.selectList(Wrappers.<PriceItem>lambdaQuery()
                        .in(PriceItem::getItemCode, itemCodes)
                        .le(PriceItem::getEffectiveFrom, now)
                        .and(w -> w.isNull(PriceItem::getEffectiveTo).or().gt(PriceItem::getEffectiveTo, now)))
                .stream()
                .map(PriceQueryService::toSnapshot)
                .collect(Collectors.toMap(PriceSnapshot::itemCode, Function.identity(), (a, b) -> a));
    }

    private static PriceSnapshot toSnapshot(PriceItem item) {
        return new PriceSnapshot(item.getItemCode(), item.getItemName(), item.getItemType(),
                item.getUnitPrice(), item.getInsuranceClass() != null && item.getInsuranceClass() == 1);
    }
}
