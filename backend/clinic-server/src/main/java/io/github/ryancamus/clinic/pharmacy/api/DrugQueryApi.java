package io.github.ryancamus.clinic.pharmacy.api;

import io.github.ryancamus.clinic.common.money.Money;

import java.util.Collection;
import java.util.Map;

/**
 * 药品目录查询，供开方与划价使用。
 */
public interface DrugQueryApi {

    Map<Long, DrugSnapshot> findByIds(Collection<Long> drugIds);

    /**
     * @param goodsType     1 药品 2 卫生材料
     * @param traceRequired 发药时是否必须扫追溯码
     */
    record DrugSnapshot(Long id, String drugCode, String name, String spec, String unit, int goodsType,
                        Money retailPrice, boolean insurable, boolean traceRequired, boolean enabled) {

        /** 收费明细里显示的名称：品名 + 规格 */
        public String displayName() {
            return name + " " + spec;
        }
    }
}
