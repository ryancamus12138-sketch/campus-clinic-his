package io.github.ryancamus.clinic.finance.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.finance.api.ChannelBillPort.Entry;
import io.github.ryancamus.clinic.finance.api.ChannelBillPort;
import io.github.ryancamus.clinic.finance.api.ReconDiffApi;
import io.github.ryancamus.clinic.finance.domain.entity.ReconDiff;
import io.github.ryancamus.clinic.finance.domain.vo.ReconDiffVO;
import io.github.ryancamus.clinic.finance.domain.vo.ReconResultVO;
import io.github.ryancamus.clinic.finance.enums.FinanceErrorCode;
import io.github.ryancamus.clinic.finance.mapper.ReconDiffMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T+1 渠道对账：以渠道交易号为键，全量比对本系统成功收款与渠道账单。
 * <ul>
 *   <li>渠道有、本系统没有 → 长款（钱收了但没入账，需补记或原路退回）；</li>
 *   <li>本系统有、渠道没有 → 短款（入了账但钱没到，需核查）；</li>
 *   <li>两边都有但金额不同 → 金额不符。</li>
 * </ul>
 * 差异写入 recon_diff，唯一键保证重复对账不会重复登记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconService {

    private final ChannelBillPort billPort;
    private final ReconDiffApi reconDiffApi;
    private final ReconDiffMapper diffMapper;

    public ReconResultVO reconcile(LocalDate date) {
        int ours = 0;
        int theirs = 0;
        int matched = 0;
        int longs = 0;
        int shorts = 0;
        int mismatches = 0;
        List<Integer> channels = billPort.reconcilableChannels();
        for (int channel : channels) {
            Map<String, Entry> our = index(billPort.ourPayments(channel, date));
            Map<String, Entry> bill = index(billPort.channelBill(channel, date));
            ours += our.size();
            theirs += bill.size();
            for (Entry b : bill.values()) {
                Entry o = our.get(b.tradeNo());
                if (o == null) {
                    longs++;
                    record(date, channel, ReconDiffApi.LONG, b.payNo(), b.tradeNo(), null, b);
                } else if (!o.amount().equals(b.amount())) {
                    mismatches++;
                    record(date, channel, ReconDiffApi.AMOUNT_MISMATCH, o.payNo(), b.tradeNo(), o, b);
                } else {
                    matched++;
                }
            }
            for (Entry o : our.values()) {
                if (!bill.containsKey(o.tradeNo())) {
                    shorts++;
                    record(date, channel, ReconDiffApi.SHORT, o.payNo(), o.tradeNo(), o, null);
                }
            }
        }
        log.info("渠道对账 {}：本系统 {} 笔，渠道 {} 笔，一致 {}，长款 {}，短款 {}，金额不符 {}", date, ours, theirs,
                matched, longs, shorts, mismatches);
        return new ReconResultVO(date, ours, theirs, matched, longs, shorts, mismatches, List.copyOf(channels));
    }

    public PageResult<ReconDiffVO> diffs(LocalDate date, Integer status, long page, long size) {
        Page<ReconDiff> p = diffMapper.selectPage(Page.of(page, size), Wrappers.<ReconDiff>lambdaQuery()
                .eq(date != null, ReconDiff::getReconDate, date)
                .eq(status != null, ReconDiff::getStatus, status)
                .orderByDesc(ReconDiff::getId));
        return new PageResult<>(p.getRecords().stream().map(ReconService::toVO).toList(), p.getTotal(),
                p.getCurrent(), p.getSize());
    }

    public void resolve(Long diffId, String remark) {
        ReconDiff diff = diffMapper.selectById(diffId);
        BizException.throwIf(diff == null, FinanceErrorCode.DIFF_NOT_FOUND);
        diff.setStatus(1);
        diff.setRemark(remark);
        diffMapper.updateById(diff);
    }

    private void record(LocalDate date, int channel, int type, String payNo, String tradeNo, Entry ours,
                        Entry theirs) {
        reconDiffApi.record(new ReconDiffApi.Diff(date, channel, type, payNo, tradeNo,
                ours == null ? null : ours.amount(), theirs == null ? null : theirs.amount()));
    }

    private static Map<String, Entry> index(List<Entry> entries) {
        Map<String, Entry> map = new HashMap<>();
        entries.forEach(e -> map.put(e.tradeNo(), e));
        return map;
    }

    private static ReconDiffVO toVO(ReconDiff d) {
        return new ReconDiffVO(d.getId(), d.getReconDate(), d.getChannel(), d.getDiffType(), d.getPayNo(),
                d.getChannelTradeNo(), d.getOurAmount(), d.getChannelAmount(), d.getStatus(), d.getRemark(),
                d.getCreateTime());
    }
}
