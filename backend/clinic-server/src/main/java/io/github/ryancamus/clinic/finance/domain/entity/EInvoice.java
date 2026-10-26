package io.github.ryancamus.clinic.finance.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 医疗收费电子票据。biz_key 唯一：同一笔业务无论触发多少次只对应一张票。
 */
@Getter
@Setter
@TableName("e_invoice")
public class EInvoice extends BaseEntity {

    public static final int BLUE = 1;
    public static final int RED = 2;

    public static final int PENDING = 0;
    public static final int ISSUED = 1;
    public static final int FAILED = 2;

    private String invoiceNo;
    private Long chargeOrderId;
    private Long patientId;
    private String chargeNo;
    private Long refundOrderId;
    private Integer invoiceType;
    private Long originalInvoiceId;
    /** 票面金额（原价合计） */
    private Money amount;
    private Money selfAmount;
    private Money insuranceAmount;
    private String pdfUrl;
    private LocalDateTime issueTime;
    private Integer retryCount;
    private Integer status;
    /** 1 平台开具 2 人工开具后登记 */
    private Integer issueMode;
    private Long issuedBy;
    private String bizKey;
}
