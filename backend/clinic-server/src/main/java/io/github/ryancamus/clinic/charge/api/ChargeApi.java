package io.github.ryancamus.clinic.charge.api;

import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 收费模块对其他模块开放的收费单操作。预约模块通过它为挂号/体检/疫苗生成收费单、在超时释放前关单。
 * <p>
 * 付清后收费模块发布 {@link ChargePaidEvent}（同一事务内同步处理），由来源模块更新自己的单据状态。
 */
public interface ChargeApi {

    /** 收费单来源：门诊（与 ChargeSource.PRESCRIPTION 相同，供其他模块使用；其余为挂号、体检、疫苗） */
    int SOURCE_PRESCRIPTION = 2;

    /**
     * 为一条预约生成收费单。必须在调用方事务内执行：调用方随后把收费单 ID 写回预约，
     * 两步一起提交。同一预约重复生成会因明细唯一键冲突抛出 DuplicateKeyException。
     */
    ChargeOrderView createForRegistration(RegistrationCharge command);

    /**
     * 为医生开具的处方生成收费单，并为其中的药品锁定库存。必须在调用方事务内执行，
     * 库存不足时抛出业务异常，整个开方回滚。
     */
    ChargeOrderView createForPrescription(PrescriptionCharge command);

    Optional<ChargeOrderView> findView(Long chargeOrderId);

    /**
     * 关闭未支付的收费单。关单前先向渠道确认待支付的支付单：已付款的按付款处理，
     * 未付款的在渠道侧关闭，防止“这边关单、那边用户还在付款”。
     */
    CloseOutcome closeIfUnpaid(Long chargeOrderId);

    /**
     * 医生接诊后调用：该预约已付的挂号费记为“已提供服务”，此后不能退（与药品发出后不能直接退费同理）。
     * 可重复调用。
     */
    void markRegistrationServed(Long registrationId);

    enum CloseOutcome {
        /** 已关闭（本次关闭或之前已关闭） */
        CLOSED,
        /** 已支付（可能是关单前查询渠道时才确认的付款） */
        PAID,
        /** 收费窗口正在结算这张单，暂不能关闭，稍后再试 */
        IN_PROGRESS
    }

    /**
     * @param sourceType 1 挂号 3 体检 4 疫苗
     * @param bookedAt   预约时间，按该时刻生效的价格计费
     * @param expireTime 付款截止时间，与预约的释放时间一致
     */
    record RegistrationCharge(Long registrationId, Long patientId, int sourceType, String itemCode,
                              LocalDateTime bookedAt, LocalDateTime expireTime, Long deptId) {
    }

    /**
     * @param campusId 就诊院区，药品从该院区药房锁定
     * @param deptId   开单科室，用于按科室核算收入
     */
    record PrescriptionCharge(Long prescriptionId, Long registrationId, Long patientId, Long campusId,
                              Long deptId, List<PrescriptionLine> lines) {
    }

    /**
     * 处方明细：药品给 drugId，诊疗项目给 itemCode。
     *
     * @param rxItemId 处方明细 ID，作为收费明细的来源，同一处方明细不会被收两次费
     */
    record PrescriptionLine(Long rxItemId, Long drugId, String itemCode, int qty) {
    }

    /**
     * @param status 0 待支付 2 已支付 3 部分退费 4 已全退 5 已关闭
     */
    record ChargeOrderView(Long id, String chargeNo, int status, Money totalAmount, Money insuranceAmount,
                           Money payableAmount, String feePolicyCode, LocalDateTime expireTime) {
    }
}
