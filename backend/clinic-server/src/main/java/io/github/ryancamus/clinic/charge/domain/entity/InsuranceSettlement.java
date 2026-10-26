package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("insurance_settlement")
public class InsuranceSettlement extends BaseEntity {

    public static final int SETTLING = 0;
    public static final int SETTLED = 1;
    public static final int CANCELLED = 2;
    public static final int SOURCE_COUNTER = 1;
    /** 医保结算平台未接入：收费员在医保结算终端办理后登记到本系统 */
    public static final int SOURCE_MANUAL = 3;

    private Long chargeOrderId;
    /** 本系统结算业务号：首次结算为收费单号，退费后重新结算为退费单号 */
    private String bizNo;
    /** 医保中心结算流水号 */
    private String setlId;
    /** 0 结算中 1 已结算 2 已撤销 */
    private Integer status;
    /** 1 医保平台结算（需接入医保平台） 3 医保终端办理后登记；取值 2 不使用 */
    private Integer source;
    private String certType;
    private Money medfee;
    private Money fundPay;
    private Money cashPay;
    /** 本系统划价时预结算的基金支付，与医保中心结果不一致时告警 */
    private Money expectedFundPay;
    private LocalDateTime settleTime;
    private LocalDateTime cancelTime;
    /** 人工撤销、人工登记时的操作人与说明 */
    private Long cancelBy;
    private String cancelNote;
    /** 人工登记结算结果的收费员 */
    private Long operatorId;
}
