package io.github.ryancamus.clinic.framework.security;

/**
 * 框架层发出的安全事件（如已登录用户越权访问被拒），由审计模块监听落库。
 * 框架不依赖业务模块，所以通过事件而不是直接调用。
 *
 * @param action     事件类型，如 ACCESS_DENIED
 * @param actionName 中文描述
 * @param target     操作对象，如请求路径
 * @param success    是否成功
 */
public record SecurityAuditEvent(String action, String actionName, String target, boolean success) {
}
