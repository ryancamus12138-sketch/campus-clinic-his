package io.github.ryancamus.clinic.system.enums;

import io.github.ryancamus.clinic.common.exception.ErrorCode;

/**
 * 系统与认证模块错误码，1xxxx 段。
 */
public enum SystemErrorCode implements ErrorCode {

    BAD_CREDENTIALS(10001, "用户名或密码错误", 401),
    ACCOUNT_LOCKED(10002, "登录失败次数过多，账号已临时锁定，请稍后再试", 423),
    ACCOUNT_DISABLED(10003, "账号已停用，请联系管理员", 403),
    REFRESH_TOKEN_INVALID(10004, "登录已失效，请重新登录", 401),
    PASSWORD_TOO_WEAK(10005, "口令不符合要求", 400),
    CHALLENGE_INVALID(10006, "验证已过期，请重新登录", 401),
    OLD_PASSWORD_WRONG(10008, "原口令不正确", 400),
    USER_NOT_FOUND(10009, "用户不存在", 404),
    USERNAME_EXISTS(10010, "用户名已存在", 409),
    ROLE_CONFLICT(10011, "系统管理员与审计管理员不能由同一账号兼任", 400),
    SELF_LOCKOUT(10012, "不能停用自己，也不能移除自己的管理员角色", 400),
    NOT_STAFF(10016, "这里只能管理员工账号", 400),
    PASSWORD_REUSED(10017, "新口令不能与原口令相同", 400),
    ROLE_NOT_FOUND(10018, "角色不存在", 400),
    CHARGE_POINT_NOT_FOUND(10019, "收费点不存在", 404),
    CAMPUS_NOT_FOUND(10020, "校区不存在", 404),
    CAMPUS_NAME_EXISTS(10021, "校区名称已存在", 409),
    ROLE_NOT_GRANTED(10022, "该账号不具备所选身份，请切换身份，或使用对应岗位的账号登录", 403);

    private final int code;
    private final String message;
    private final int httpStatus;

    SystemErrorCode(int code, String message, int httpStatus) {
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
