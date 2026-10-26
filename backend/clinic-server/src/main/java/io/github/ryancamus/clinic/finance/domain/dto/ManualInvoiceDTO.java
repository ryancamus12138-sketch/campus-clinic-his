package io.github.ryancamus.clinic.finance.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "人工开具的票据号")
public record ManualInvoiceDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{4,32}") @Schema(description = "票据代码 + 号码") String invoiceNo) {
}
