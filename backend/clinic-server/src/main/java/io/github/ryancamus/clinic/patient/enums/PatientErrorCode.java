package io.github.ryancamus.clinic.patient.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 患者档案错误码，21xxx 段（2xxxx 为患者与预约）。
 */
public enum PatientErrorCode implements ErrorCode {

    PATIENT_NOT_FOUND(21001, "患者档案不存在", 404),
    INVALID_ID_NO(21002, "证件号码格式或校验位不正确", 400),
    ID_NO_MISMATCH(21003, "出生日期或性别与身份证号不一致", 400),
    INVALID_PHONE(21004, "手机号格式不正确", 400),
    DUPLICATE_ID_NO(21005, "该证件号已建档", 409),
    DUPLICATE_SCHOOL_NO(21006, "该学号/工号已建档", 409),
    SCHOOL_NO_REQUIRED(21007, "学生和教职工必须填写学号/工号", 400),
    REASON_REQUIRED(21008, "查看完整信息需要填写原因", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    PatientErrorCode(int code, String message, int httpStatus) {
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
