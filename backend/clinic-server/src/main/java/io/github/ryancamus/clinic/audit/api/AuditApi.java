package io.github.ryancamus.clinic.audit.api;

/**
 * 审计日志对外接口。
 * <p>
 * 写操作由拦截器统一记录，业务代码一般不需要调用；需要显式调用的是两类：
 * 未登录时发生的事件（登录成功/失败）和敏感数据的读取（查看完整证件号）。
 */
public interface AuditApi {

    /**
     * 以当前登录用户的身份记录一次操作；终端 IP、User-Agent、链路 ID 自动取自当前请求。
     *
     * @param action     操作编码，如 PATIENT_SENSITIVE_VIEW
     * @param actionName 中文描述
     * @param target     操作对象，如 patientId=123
     * @param success    是否成功
     * @param detail     补充说明，不得包含证件号、手机号等敏感值
     */
    void record(String action, String actionName, String target, boolean success, String detail);

    /** 以指定账号记录（用于登录等尚未建立登录态的场景） */
    void recordAs(Long userId, String username, String action, String actionName, boolean success, String detail);
}
