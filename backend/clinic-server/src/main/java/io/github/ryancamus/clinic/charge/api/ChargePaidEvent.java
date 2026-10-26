package io.github.ryancamus.clinic.charge.api;

/**
 * 收费单已付清。在确认付款的事务内同步发布，监听方的数据库修改与付款记录同时提交；
 * 监听方抛出异常则整个结算事务回滚，本次收取的款项按窗口结算的规则撤回或转为收款异常。
 *
 * @param registrationId 来源为挂号时非空
 */
public record ChargePaidEvent(Long chargeOrderId, int sourceType, Long registrationId, Long patientId) {
}
