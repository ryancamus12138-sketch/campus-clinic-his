package io.github.ryancamus.clinic.finance.service;

import io.github.ryancamus.clinic.finance.api.ReconDiffApi;
import io.github.ryancamus.clinic.finance.domain.entity.ReconDiff;
import io.github.ryancamus.clinic.finance.mapper.ReconDiffMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconDiffService implements ReconDiffApi {

    private final ReconDiffMapper diffMapper;

    @Override
    public void record(Diff diff) {
        ReconDiff row = new ReconDiff();
        row.setReconDate(diff.reconDate());
        row.setChannel(diff.channel());
        row.setDiffType(diff.diffType());
        row.setPayNo(diff.payNo());
        row.setChannelTradeNo(diff.channelTradeNo());
        row.setOurAmount(diff.ourAmount());
        row.setChannelAmount(diff.channelAmount());
        row.setStatus(ReconDiff.STATUS_OPEN);
        try {
            diffMapper.insert(row);
            log.warn("登记资金差异 type={} payNo={} tradeNo={} our={} channel={}", diff.diffType(), diff.payNo(),
                    diff.channelTradeNo(), diff.ourAmount(), diff.channelAmount());
        } catch (DuplicateKeyException e) {
            // 重复对账，同一笔差异已登记过
            log.debug("资金差异已登记 payNo={}", diff.payNo());
        }
    }
}
