package io.github.ryancamus.clinic.charge.service;

import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.charge.pay.PayChannelRegistry;
import io.github.ryancamus.clinic.finance.api.ChannelBillPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 收费模块为对账提供数据：本系统成功收款来自支付单，渠道账单来自渠道客户端。
 */
@Component
@RequiredArgsConstructor
public class ChannelBillAdapter implements ChannelBillPort {

    /** 对账差异中代表“医保基金”的渠道编号，与支付渠道编号不重叠 */
    public static final int INSURANCE = ChannelBillPort.INSURANCE;

    private final PayOrderMapper payOrderMapper;
    private final PayChannelRegistry channels;
    private final InsuranceSettlementService insuranceSettlements;
    private final ObjectProvider<InsuranceGateway> insuranceGateway;
    private final ObjectProvider<CampusCardGateway> campusCard;

    @Override
    public List<Integer> reconcilableChannels() {
        List<Integer> list = new ArrayList<>(Arrays.stream(PayChannel.values()).filter(PayChannel::thirdParty)
                .map(PayChannel::code).filter(c -> channels.find(c).isPresent()).toList());
        // 校园卡与一卡通平台的商户日流水对账
        if (campusCard.getIfAvailable() != null) {
            list.add(PayChannel.CAMPUS_CARD.code());
        }
        // 医保基金支付与医保中心对明细账（≈3202），按结算流水号逐笔比对；未接入医保平台时由医保管理员在终端上核对
        if (insuranceGateway.getIfAvailable() != null) {
            list.add(INSURANCE);
        }
        return list;
    }

    @Override
    public List<Entry> ourPayments(int channel, LocalDate date) {
        if (channel == INSURANCE) {
            return insuranceSettlements.settledOn(date).stream()
                    .map(s -> new Entry(s.getBizNo(), s.getSetlId(), s.getFundPay(), s.getSettleTime())).toList();
        }
        return payOrderMapper.selectSucceeded(channel, date.atStartOfDay(), date.plusDays(1).atStartOfDay()).stream()
                .map(p -> new Entry(p.getPayNo(), p.getChannelTradeNo(), p.getAmount(), p.getSuccessTime()))
                .toList();
    }

    @Override
    public List<Entry> channelBill(int channel, LocalDate date) {
        if (channel == INSURANCE) {
            return insuranceGateway.getObject().statement(date).stream()
                    .map(s -> new Entry(s.bizNo(), s.setlId(), s.fundPay(), s.settledAt())).toList();
        }
        if (channel == PayChannel.CAMPUS_CARD.code()) {
            return campusCard.getObject().statement(date).stream()
                    .map(d -> new Entry(d.bizNo(), d.tradeNo(), d.amount(), d.at())).toList();
        }
        return channels.require(channel).downloadBill(channel, date).stream()
                .map(t -> new Entry(t.payNo(), t.tradeNo(), t.amount(), t.paidAt()))
                .toList();
    }
}
