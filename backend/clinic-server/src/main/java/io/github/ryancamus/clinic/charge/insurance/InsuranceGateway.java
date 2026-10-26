package io.github.ryancamus.clinic.charge.insurance;

import io.github.ryancamus.clinic.charge.fee.FeeCalculator.Breakdown;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 医保结算端口。按国家医疗保障信息平台定点医药机构接口规范的门诊结算流程抽象：
 * <pre>
 *   preSettle  ≈ 2204 门诊费用明细上传 + 2206 门诊预结算：划价时返回基金支付与个人支付
 *   settle     ≈ 2207 门诊结算：个人部分付清后正式结算
 *   cancel     ≈ 2208 门诊结算撤销：退费时撤销原结算，再按剩余明细重新结算
 *   statement  ≈ 3202 医药机构费用结算对明细账：日终对账
 * </pre>
 * 分摊由医保中心计算，医院只负责上传明细、记录结果。
 * <p>
 * 正式代码中目前没有实现（未接入）：此时划价按本院配置的报销规则估算，收费员在医保部门提供的结算终端上办理结算后，
 * 把结算单号、统筹金额登记到本系统（见 {@code InsuranceSettlementService}）。测试中使用测试替身。
 * <p>
 * 实现必须满足：同一 {@code bizNo} 重复结算返回同一结果、重复撤销视为成功（调用方会在失败后重试）。
 */
public interface InsuranceGateway {

    /** 预结算：只计算，不在医保中心留下结算记录 */
    Breakdown preSettle(Insured insured, List<FeeLine> lines);

    /**
     * 正式结算。
     *
     * @param bizNo 本系统结算业务号，医保中心据此去重
     * @throws InsuranceException 医保中心不可用或拒绝，调用方重试
     */
    Settlement settle(String bizNo, Insured insured, List<FeeLine> lines);

    /** 撤销结算；已撤销或不存在的视为成功 */
    void cancel(String bizNo, String setlId);

    /** 医保中心记录的某天有效结算（已撤销的不含） */
    List<Settlement> statement(LocalDate date);

    /**
     * 参保人与就诊凭证。正式结算必须带凭证（接口规范中的就诊凭证类型与编号）；预结算只用于估算，可不带。
     *
     * @param insuranceType 参保类型：0 自费 1 大学生医保 2 职工医保 3 公费医疗
     * @param certType      就诊凭证类型，见 {@link CertType}
     * @param certNo        凭证编号：医保电子凭证为扫码得到的令牌，身份证、社保卡为读卡得到的号码；
     *                      为空表示由适配器按患者档案读取（如实名身份证）
     */
    record Insured(Long patientId, int insuranceType, String certType, String certNo) {

        public Insured(Long patientId, int insuranceType) {
            this(patientId, insuranceType, null, null);
        }
    }

    /** 就诊凭证类型（接口规范 mdtrt_cert_type） */
    final class CertType {
        /** 医保电子凭证（医保码） */
        public static final String E_VOUCHER = "01";
        public static final String ID_CARD = "02";
        public static final String SOCIAL_SECURITY_CARD = "03";

        private CertType() {
        }

        public static boolean valid(String type) {
            return E_VOUCHER.equals(type) || ID_CARD.equals(type) || SOCIAL_SECURITY_CARD.equals(type);
        }
    }

    /**
     * @param setlId  医保中心结算流水号
     * @param medfee  医疗费总额
     * @param fundPay 基金支付
     * @param cashPay 个人现金支付
     */
    record Settlement(String bizNo, String setlId, Money medfee, Money fundPay, Money cashPay,
                      LocalDateTime settledAt) {
    }

    class InsuranceException extends RuntimeException {
        public InsuranceException(String message) {
            super(message);
        }
    }
}
