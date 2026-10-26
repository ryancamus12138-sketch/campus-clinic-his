package io.github.ryancamus.clinic.common.exception;

import lombok.Getter;

/**
 * 业务异常：表示调用方可以理解并处理的失败（参数不合法、状态不允许、已约满等），
 * 由全局异常处理器转换成统一返回体。
 * <p>
 * 不采集堆栈：抢约高峰时 90% 的请求以“已约满”结束，采集堆栈是这类请求最主要的 CPU 开销之一，
 * 而业务异常的堆栈对排查问题没有价值。
 */
@Getter
public class BizException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BizException(errorCode, message);
        }
    }
}
