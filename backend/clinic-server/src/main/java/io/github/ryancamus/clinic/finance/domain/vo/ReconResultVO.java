package io.github.ryancamus.clinic.finance.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "对账结果")
public record ReconResultVO(LocalDate date, int ourCount, int channelCount, int matched,
                            @Schema(description = "长款：渠道有、本系统无") int longCount,
                            @Schema(description = "短款：本系统有、渠道无") int shortCount,
                            int mismatchCount,
                            @Schema(description = "参与对账的已接入渠道；为空表示没有已接入的平台，本次没有对账")
                            List<Integer> channels) {
}
