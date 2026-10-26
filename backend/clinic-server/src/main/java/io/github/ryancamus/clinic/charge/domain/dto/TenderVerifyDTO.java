package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 财务逐笔核实收款异常中可能已扣款的一笔收款。
 *
 * @param result    RETURNED 款项已退还患者；NOT_CHARGED 核实实际未扣款
 * @param reference 凭证号：银行卡为 POS 撤销/退货参考号，校园卡、付款码为平台流水号或查询单号；现金可不填
 * @param note      核实说明，如“已在 POS 机撤销当笔交易”“一卡通后台查询无此流水”
 */
@Schema(description = "收款异常逐笔核实")
public record TenderVerifyDTO(
        @NotNull Result result,
        @Size(max = 64) @Pattern(regexp = "^$|[A-Za-z0-9-]{4,64}") String reference,
        @Size(max = 200) String note) {

    public enum Result {
        /** 款项已退还患者 */
        RETURNED,
        /** 核实实际未扣款 */
        NOT_CHARGED
    }
}
