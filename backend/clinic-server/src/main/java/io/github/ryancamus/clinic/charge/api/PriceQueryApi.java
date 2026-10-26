package io.github.ryancamus.clinic.charge.api;

import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 收费模块对外提供的价目查询。其他模块只能依赖本接口，不能直接访问价目表。
 */
public interface PriceQueryApi {

    /** 查询某时刻生效的价格 */
    Optional<PriceSnapshot> findEffective(String itemCode, LocalDateTime at);

    /**
     * 当前生效价格（本地缓存 30 秒）。抢约高峰时每个请求都要取价，缓存避免把价目表查询压到数据库；
     * 改价在 30 秒内生效，对校医院足够。
     */
    Optional<PriceSnapshot> findCurrentCached(String itemCode);

    /** 批量查询当前生效价格，用于列表展示 */
    Map<String, PriceSnapshot> findCurrent(Collection<String> itemCodes);

    /**
     * 价格快照：下单时冻结，之后改价不影响已生成的单据。
     *
     * @param insurable 是否属于医保可报销范围
     */
    record PriceSnapshot(String itemCode, String itemName, int itemType, Money unitPrice, boolean insurable) {
    }
}
