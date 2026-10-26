package io.github.ryancamus.clinic.pharmacy.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 药房模块错误码，4xxxx 段。
 */
public enum PharmacyErrorCode implements ErrorCode {

    DRUG_NOT_FOUND(40001, "药品不存在或已停用", 404),
    STOCK_INSUFFICIENT(40002, "可用库存不足", 409),
    ORDER_NOT_DISPENSABLE(40003, "收费单不存在或尚未缴费", 404),
    ITEM_NOT_IN_ORDER(40004, "明细不属于该收费单", 400),
    DISPENSE_QTY_EXCEEDED(40005, "发药数量超过可发数量", 409),
    TRACE_CODE_REQUIRED(40006, "该药品需逐盒扫描追溯码，扫码数量须等于发药数量", 400),
    TRACE_CODE_INVALID(40007, "追溯码格式不正确", 400),
    TRACE_CODE_USED(40008, "追溯码已被使用，请核对是否串换药品", 409),
    RETURN_QTY_EXCEEDED(40009, "退药数量超过已发数量", 409),
    TRACE_CODE_NOT_FOUND(40010, "追溯码不属于该明细的已发药品", 400),
    INVALID_BATCH(40011, "批次信息不正确", 400),
    DRUG_CODE_EXISTS(40012, "药品编码已存在", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    PharmacyErrorCode(int code, String message, int httpStatus) {
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
