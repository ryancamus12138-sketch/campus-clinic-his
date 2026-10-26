package io.github.ryancamus.clinic.common.exception;

/**
 * 错误码契约。
 * <p>
 * 编码按模块分段：0 成功；1xxx 通用；1xxxx 系统与认证；20xxx 挂号；21xxx 患者；3xxxx 收费与退费；
 * 4xxxx 药房；5xxxx 门诊开方；6xxxx 财务。每个模块用自己的枚举实现本接口，避免一个巨型常量类。
 */
public interface ErrorCode {

    /** 业务错误码，前端据此做分支处理 */
    int code();

    /** 默认提示信息，可被 {@link BizException} 中的具体信息覆盖 */
    String message();

    /** 对应的 HTTP 状态码 */
    int httpStatus();
}
