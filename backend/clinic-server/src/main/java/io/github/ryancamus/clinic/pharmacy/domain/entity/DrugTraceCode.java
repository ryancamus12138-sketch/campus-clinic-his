package io.github.ryancamus.clinic.pharmacy.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 药品追溯码。生成列 active_code 上的唯一索引保证一盒药在退药前只能被销售一次。
 */
@Getter
@Setter
@TableName("drug_trace_code")
public class DrugTraceCode extends BaseEntity {

    public static final int COLLECTED = 1;
    public static final int UPLOADED = 2;
    public static final int UPLOAD_FAILED = 3;
    public static final int RETURNED = 4;

    private String traceCode;
    private Long drugId;
    private Long batchId;
    private Long chargeItemId;
    private Long stockLockId;
    private Integer status;
    private LocalDateTime uploadTime;
    private Integer retryCount;
}
