package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * @param note 审批意见；驳回时为驳回理由（必填）
 */
@Schema(description = "退费审批意见")
public record RefundDecisionDTO(@Size(max = 200) String note) {
}
