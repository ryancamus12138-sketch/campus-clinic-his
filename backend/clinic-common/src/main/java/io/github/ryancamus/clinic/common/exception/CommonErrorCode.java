package io.github.ryancamus.clinic.common.exception;

/**
 * 通用错误码，与具体业务模块无关。
 */
public enum CommonErrorCode implements ErrorCode {

    SUCCESS(0, "成功", 200),
    BAD_REQUEST(400, "请求参数不正确", 400),
    UNAUTHORIZED(401, "未登录或登录已过期", 401),
    FORBIDDEN(403, "没有访问权限", 403),
    NOT_FOUND(404, "资源不存在", 404),
    METHOD_NOT_ALLOWED(405, "请求方法不支持", 405),
    UNSUPPORTED_MEDIA_TYPE(415, "请求内容类型不支持", 415),
    CONFLICT(409, "数据已被修改，请刷新后重试", 409),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试", 429),
    INTERNAL_ERROR(500, "系统繁忙，请稍后再试", 500),
    ILLEGAL_STATE(1001, "当前状态不允许该操作", 409),
    NEGATIVE_AMOUNT(1002, "金额不能为负数", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(int code, String message, int httpStatus) {
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
