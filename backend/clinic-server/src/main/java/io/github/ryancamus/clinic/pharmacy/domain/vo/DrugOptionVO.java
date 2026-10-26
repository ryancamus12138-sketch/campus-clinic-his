package io.github.ryancamus.clinic.pharmacy.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "药品选项（开方用）")
public record DrugOptionVO(
        Long id,
        String drugCode,
        String name,
        String spec,
        String unit,
        @Schema(description = "1 药品 2 卫生材料") Integer goodsType,
        Money retailPrice,
        boolean insurable,
        @Schema(description = "本院区可用量（在库 − 锁定，不含近效期批次）") int available) {
}
