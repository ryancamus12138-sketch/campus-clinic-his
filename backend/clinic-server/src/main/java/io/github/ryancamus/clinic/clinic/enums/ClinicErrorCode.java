package io.github.ryancamus.clinic.clinic.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 门诊诊疗模块错误码，5xxxx 段。
 */
public enum ClinicErrorCode implements ErrorCode {

    VISIT_NOT_FOUND(50001, "挂号记录不存在", 404),
    VISIT_NOT_ACTIVE(50002, "该挂号未缴费或已取消，不能开方", 409),
    INVALID_RX_ITEM(50003, "处方明细不完整：药品需选择药品，诊疗项目需选择项目", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    ClinicErrorCode(int code, String message, int httpStatus) {
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
