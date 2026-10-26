package io.github.ryancamus.clinic.finance.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.finance.domain.entity.EInvoice;
import io.github.ryancamus.clinic.finance.domain.vo.InvoiceVO;
import io.github.ryancamus.clinic.finance.enums.FinanceErrorCode;
import io.github.ryancamus.clinic.finance.invoice.FiscalInvoiceClient;
import io.github.ryancamus.clinic.finance.mapper.EInvoiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 医疗收费电子票据。
 * <ul>
 *   <li>付清：开正票（票面为原价合计，注明医保统筹与个人自付）；</li>
 *   <li>退费：开红票全额冲抵当前正票，再按剩余金额重开正票；全部退完只开红票。</li>
 * </ul>
 * 由发件箱事件驱动，平台失败时抛出异常让事件稍后重试；biz_key 唯一，重复触发也只开一张。
 * <p>
 * 财政电子票据平台未接入时（没有 {@link FiscalInvoiceClient} 实现），票据记录停在“待开具”，界面显示“未接入，待人工开具”；
 * 财务在票据平台网页或原有票据系统开具后，把票号登记进来（记录登记人）。本系统不会自行生成票号。
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final EInvoiceMapper invoiceMapper;
    private final ObjectProvider<FiscalInvoiceClient> fiscalClient;
    private final Clock clock;

    public void onPaid(JsonNode p) {
        EInvoice blue = getOrCreate("PAY:" + p.get("chargeOrderId").asString(), p, EInvoice.BLUE, null,
                Money.of(p.get("totalAmount").asString()), Money.of(p.get("selfAmount").asString()),
                Money.of(p.get("insuranceAmount").asString()));
        issue(blue, null);
    }

    public void onRefunded(JsonNode p) {
        long chargeOrderId = p.get("chargeOrderId").asLong();
        String refundNo = p.get("refundNo").asString();
        EInvoice red = invoiceMapper.selectByBizKey("RED:" + refundNo);
        if (red == null) {
            EInvoice blue = invoiceMapper.selectActiveBlue(chargeOrderId);
            if (blue == null) {
                // 发件箱保证同一收费单的事件按序消费，正票必然先于退费处理；仍找不到说明数据异常，留给重试与告警
                throw new IllegalStateException("没有可冲红的正票 chargeOrderId=" + chargeOrderId);
            }
            red = getOrCreate("RED:" + refundNo, p, EInvoice.RED, blue.getId(), blue.getAmount(),
                    blue.getSelfAmount(), blue.getInsuranceAmount());
        }
        EInvoice original = invoiceMapper.selectById(red.getOriginalInvoiceId());
        issue(red, original.getInvoiceNo());

        Money remaining = Money.of(p.get("totalAmount").asString());
        if (!remaining.isZero()) {
            EInvoice reissue = getOrCreate("RE:" + refundNo, p, EInvoice.BLUE, null, remaining,
                    Money.of(p.get("selfAmount").asString()), Money.of(p.get("insuranceAmount").asString()));
            issue(reissue, null);
        }
    }

    /** 平台是否已接入（未接入时界面显示“待人工开具”） */
    public boolean connected() {
        return fiscalClient.getIfAvailable() != null;
    }

    /**
     * 未接入平台时登记人工开具的票号。红票须在原正票登记之后登记。
     */
    public InvoiceVO registerManual(Long invoiceId, String invoiceNo, Long operatorId) {
        EInvoice inv = invoiceMapper.selectById(invoiceId);
        BizException.throwIf(inv == null, FinanceErrorCode.INVOICE_NOT_FOUND);
        BizException.throwIf(connected(), FinanceErrorCode.INVOICE_STATE_CHANGED, "已接入财政电子票据平台，票据由平台开具");
        if (inv.getInvoiceType() == EInvoice.RED) {
            EInvoice original = invoiceMapper.selectById(inv.getOriginalInvoiceId());
            BizException.throwIf(original.getStatus() != EInvoice.ISSUED, FinanceErrorCode.INVOICE_STATE_CHANGED,
                    "请先登记被冲红的原票");
        }
        BizException.throwIf(invoiceMapper.registerManual(invoiceId, invoiceNo.trim(), operatorId,
                LocalDateTime.now(clock)) == 0, FinanceErrorCode.INVOICE_STATE_CHANGED);
        return toVO(invoiceMapper.selectById(invoiceId));
    }

    public PageResult<InvoiceVO> page(String chargeNo, long page, long size) {
        Page<EInvoice> p = invoiceMapper.selectPage(Page.of(page, size), Wrappers.<EInvoice>lambdaQuery()
                .eq(chargeNo != null && !chargeNo.isBlank(), EInvoice::getChargeNo, chargeNo)
                .orderByDesc(EInvoice::getId));
        return new PageResult<>(p.getRecords().stream().map(InvoiceService::toVO).toList(), p.getTotal(),
                p.getCurrent(), p.getSize());
    }

    private EInvoice getOrCreate(String bizKey, JsonNode p, int type, Long originalId, Money amount, Money self,
                                 Money insurance) {
        EInvoice existing = invoiceMapper.selectByBizKey(bizKey);
        if (existing != null) {
            return existing;
        }
        EInvoice inv = new EInvoice();
        inv.setBizKey(bizKey);
        inv.setChargeOrderId(p.get("chargeOrderId").asLong());
        inv.setPatientId(p.get("patientId").asLong());
        inv.setChargeNo(p.get("chargeNo").asString());
        inv.setInvoiceType(type);
        inv.setOriginalInvoiceId(originalId);
        inv.setAmount(amount);
        inv.setSelfAmount(self);
        inv.setInsuranceAmount(insurance);
        inv.setStatus(EInvoice.PENDING);
        inv.setRetryCount(0);
        try {
            invoiceMapper.insert(inv);
            return inv;
        } catch (DuplicateKeyException e) {
            return invoiceMapper.selectByBizKey(bizKey);
        }
    }

    private void issue(EInvoice inv, String originalInvoiceNo) {
        if (inv.getStatus() == EInvoice.ISSUED) {
            return;
        }
        FiscalInvoiceClient client = fiscalClient.getIfAvailable();
        if (client == null) {
            // 未接入：保持“待开具”，等待人工开具后登记
            return;
        }
        // 平台失败时异常抛出，本次消费事务（包括刚建的票据记录）整体回滚，由发件箱退避重试；
        // 平台以业务键幂等，重试不会多开票
        FiscalInvoiceClient.Issued result = client.issue(inv.getBizKey(), inv.getInvoiceType(), inv.getAmount(),
                originalInvoiceNo);
        invoiceMapper.markIssued(inv.getId(), result.invoiceNo(), result.pdfUrl(), LocalDateTime.now(clock));
    }

    private static InvoiceVO toVO(EInvoice i) {
        return new InvoiceVO(i.getId(), i.getInvoiceNo(), i.getChargeNo(), i.getInvoiceType(), i.getAmount(),
                i.getSelfAmount(), i.getInsuranceAmount(), i.getStatus(), i.getPdfUrl(), i.getIssueTime(),
                i.getIssueMode(), i.getIssuedBy());
    }
}
