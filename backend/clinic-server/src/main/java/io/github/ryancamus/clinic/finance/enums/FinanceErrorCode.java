package io.github.ryancamus.clinic.finance.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 财务模块错误码，6xxxx 段。
 */
public enum FinanceErrorCode implements ErrorCode {

    SHIFT_NOT_FOUND(60001, "班次不存在", 404),
    SHIFT_NOT_OPEN(60002, "该班次已交班", 409),
    SHIFT_NOT_HANDED_OVER(60003, "该班次尚未交班，不能复核", 409),
    NOT_YOUR_SHIFT(60004, "只能交自己的班", 403),
    DIFF_NOT_FOUND(60005, "差异记录不存在", 404),
    INVALID_RANGE(60006, "日期范围不正确（最多 366 天）", 400),
    DIFF_NOTE_REQUIRED(60007, "实点现金与应有不一致，请填写长短款说明", 400),
    INVOICE_NOT_FOUND(60008, "票据记录不存在", 404),
    INVOICE_STATE_CHANGED(60009, "票据状态已变化，请刷新", 409),
    POS_COUNT_REQUIRED(60010, "本班有银行卡收款或退款，请录入 POS 机结算单金额", 400),
    REVIEWER_IS_CASHIER(60011, "不能复核自己的班次", 403);

    private final int code;
    private final String message;
    private final int httpStatus;

    FinanceErrorCode(int code, String message, int httpStatus) {
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
