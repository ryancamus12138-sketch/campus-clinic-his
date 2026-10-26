package io.github.ryancamus.clinic.schedule.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 号源与预约模块错误码，2xxxx 段。
 */
public enum ScheduleErrorCode implements ErrorCode {

    SLOT_NOT_FOUND(20001, "号源不存在", 404),
    SLOT_NOT_OPEN(20002, "号源尚未开放挂号", 409),
    SLOT_FULL(20003, "该时段已挂满", 409),
    ALREADY_BOOKED(20004, "该患者已挂该时段的号，请勿重复挂号", 409),
    SLOT_PASSED(20005, "该时段已过，不能挂号", 409),
    BOOKING_NOT_FOUND(20007, "挂号记录不存在", 404),
    CANNOT_CANCEL(20008, "当前状态不能取消", 409),
    PAID_BOOKING_CANCEL(20009, "已缴费的挂号请先办理退费，退费完成后自动退号", 409),
    PRICE_ITEM_NOT_FOUND(20010, "价目编码不存在或未生效", 400),
    DEPT_NOT_FOUND(20011, "科室不存在或已停用", 400),
    INVALID_PERIOD(20012, "时段设置不正确", 400),
    NOT_PAYABLE(20013, "该挂号无需缴费或已失效", 409),
    PAYMENT_DEADLINE_PASSED(20014, "已超过付款期限，号源已释放", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    ScheduleErrorCode(int code, String message, int httpStatus) {
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
