package io.github.ryancamus.clinic.charge.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 收费与支付模块错误码，3xxxx 段。
 */
public enum ChargeErrorCode implements ErrorCode {

    PATIENT_NOT_FOUND(30001, "患者不存在", 404),
    PRICE_ITEM_NOT_FOUND(30002, "收费项目不存在或未生效", 400),
    ORDER_NOT_FOUND(30003, "收费单不存在", 404),
    ORDER_NOT_PAYABLE(30004, "收费单当前状态不能支付", 409),
    ORDER_EXPIRED(30005, "收费单已过付款期限", 409),
    ORDER_ALREADY_PAID(30006, "收费单已支付", 409),
    CHANNEL_NOT_SUPPORTED(30007, "不支持的支付方式", 400),
    INSUFFICIENT_CASH(30008, "实收金额不足", 400),
    IDEMPOTENCY_KEY_REUSED(30010, "幂等键已用于其他请求", 409),
    PAY_IN_PROGRESS(30011, "该收费单有一笔支付正在处理，请稍后再试", 409),
    DUPLICATE_ITEM(30013, "同一收费项目请合并数量", 400),
    ORDER_NOT_CLOSABLE(30014, "收费单当前状态不能作废", 409),
    ORDER_NOT_REFUNDABLE(30015, "收费单当前状态不能退费", 409),
    REFUND_QTY_EXCEEDED(30016, "退费数量超过可退数量（已发药的需先到药房退药）", 409),
    REFUND_IN_PROGRESS(30017, "该收费单有一笔退费正在处理，完成后再申请", 409),
    NOTHING_TO_REFUND(30018, "所选项目没有需要退还的个人自付金额", 400),
    REFUND_NOT_FOUND(30019, "退费单不存在", 404),
    REFUND_STATE_CHANGED(30020, "退费单状态已变化，请刷新", 409),
    PRICE_ITEM_EXISTS(30021, "收费项目编码已存在", 409),
    PRICE_VERSION_INVALID(30022, "生效时间必须晚于当前版本的生效时间，且不早于现在", 400),
    PRICE_PENDING_EXISTS(30023, "该项目已有待生效的调价，请先撤销", 409),
    PRICE_NO_PENDING(30024, "该项目没有待生效的调价", 404),
    TENDER_INVALID(30025, "收款方式填写不正确", 400),
    TENDER_SUM_MISMATCH(30026, "各收款方式金额之和须等于应收金额", 400),
    TENDER_FAILED(30027, "收款未成功", 409),
    INSURANCE_SETTLE_FAILED(30028, "医保结算失败，请稍后重试", 503),
    INSURANCE_AMOUNT_CHANGED(30029, "医保结算金额与划价时不一致，本次收费未成立，请撤销该笔医保结算后作废并重新划价", 409),
    REGISTRATION_FEE_SERVED(30030, "已就诊的挂号费不退", 409),
    CERT_INVALID(30033, "就诊凭证类型应为 01 医保电子凭证、02 居民身份证或 03 社会保障卡", 400),
    CHECKOUT_IN_PROGRESS(30034, "该收费单正在由其他窗口收费，请稍后刷新", 409),
    CHECKOUT_NOT_FOUND(30035, "窗口结算记录不存在", 404),
    CHECKOUT_STATE_CHANGED(30036, "该记录状态已变化，请刷新", 409),
    CHECKOUT_NEEDS_ATTENTION(30037, "该收费单有一笔收款异常待财务核对，处理完之前不能再收费或作废", 409),
    INSURANCE_CANCEL_PENDING(30038, "医保结算尚未撤销：请重试撤销，或在医保端手工撤销后勾选“已在医保端手工撤销”", 409),
    REFUND_TENDER_NOT_AWAITING(30039, "该笔退款不在待确认状态，请刷新", 409),
    REFUND_REFERENCE_REQUIRED(30040, "银行卡退款请录入 POS 退货小票上的参考号", 400),
    INSURANCE_NOT_CONNECTED(30041, "医保结算平台未接入：请在医保结算终端完成结算后，登记结算单号和统筹金额", 409),
    INSURANCE_REGISTRATION_INVALID(30042, "医保结算登记信息不正确", 400),
    TENDERS_NOT_VERIFIED(30043, "还有可能已扣款的收款没有逐笔核实，核实后才能解除", 409),
    SAME_PERSON(30044, "不能由经手人本人办理：收款异常由收费员以外的财务处理，退费不能由申请人本人审批", 403),
    VERIFY_REFERENCE_REQUIRED(30045, "请录入核实凭证：银行卡填 POS 撤销或退货参考号，校园卡、付款码填平台流水号", 400),
    REJECT_REASON_REQUIRED(30046, "驳回退费请填写理由", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    ChargeErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
