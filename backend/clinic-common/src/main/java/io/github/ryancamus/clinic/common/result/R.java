package io.github.ryancamus.clinic.common.result;

import io.github.ryancamus.clinic.common.exception.CommonErrorCode;
import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 统一返回体。
 *
 * @param code    业务码，0 表示成功
 * @param msg     提示信息
 * @param data    业务数据
 * @param traceId 请求链路 ID，便于按日志排查
 */
public record R<T>(int code, String msg, T data, String traceId) {

    public static <T> R<T> ok(T data) {
        return new R<>(CommonErrorCode.SUCCESS.code(), CommonErrorCode.SUCCESS.message(), data, TraceContext.get());
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.message());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String msg) {
        return new R<>(errorCode.code(), msg, null, TraceContext.get());
    }

    public boolean isSuccess() {
        return code == CommonErrorCode.SUCCESS.code();
    }
}
