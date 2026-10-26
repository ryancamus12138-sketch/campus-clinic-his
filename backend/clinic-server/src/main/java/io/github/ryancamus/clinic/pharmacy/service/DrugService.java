package io.github.ryancamus.clinic.pharmacy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.pharmacy.api.DrugQueryApi;
import io.github.ryancamus.clinic.pharmacy.domain.entity.Drug;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugBatch;
import io.github.ryancamus.clinic.pharmacy.domain.vo.DrugOptionVO;
import io.github.ryancamus.clinic.pharmacy.domain.vo.StockVO;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugBatchMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DrugService implements DrugQueryApi {

    private static final int OPTION_LIMIT = 30;

    private final DrugMapper drugMapper;
    private final DrugBatchMapper batchMapper;
    private final Clock clock;

    @Override
    public Map<Long, DrugSnapshot> findByIds(Collection<Long> drugIds) {
        if (drugIds.isEmpty()) {
            return Map.of();
        }
        return drugMapper.selectByIds(drugIds).stream()
                .collect(Collectors.toMap(Drug::getId, DrugService::toSnapshot));
    }

    public Map<String, Drug> findByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return drugMapper.selectList(Wrappers.<Drug>lambdaQuery().in(Drug::getDrugCode, codes)).stream()
                .collect(Collectors.toMap(Drug::getDrugCode, Function.identity()));
    }

    /** 开方时检索药品，附带本院区可用量 */
    public List<DrugOptionVO> options(Long campusId, String keyword) {
        List<Drug> drugs = searchDrugs(keyword, true);
        Map<Long, List<DrugBatch>> batches = batchesOf(campusId, drugs);
        LocalDate minExpire = LocalDate.now(clock).plusDays(StockService.NEAR_EXPIRY_DAYS);
        return drugs.stream().map(d -> new DrugOptionVO(d.getId(), d.getDrugCode(), d.getName(), d.getSpec(),
                        d.getUnit(), d.getGoodsType(), d.getRetailPrice(), d.getInsuranceClass() == 1,
                        batches.getOrDefault(d.getId(), List.of()).stream()
                                .filter(b -> b.getExpireDate().isAfter(minExpire))
                                .mapToInt(DrugBatch::available).sum()))
                .toList();
    }

    public List<StockVO> stock(Long campusId, String keyword) {
        List<Drug> drugs = searchDrugs(keyword, false);
        Map<Long, List<DrugBatch>> batches = batchesOf(campusId, drugs);
        LocalDate minExpire = LocalDate.now(clock).plusDays(StockService.NEAR_EXPIRY_DAYS);
        return drugs.stream().map(d -> {
            List<DrugBatch> list = batches.getOrDefault(d.getId(), List.of());
            int onHand = list.stream().mapToInt(DrugBatch::getQtyOnHand).sum();
            int locked = list.stream().mapToInt(DrugBatch::getQtyLocked).sum();
            int available = list.stream().filter(b -> b.getExpireDate().isAfter(minExpire))
                    .mapToInt(DrugBatch::available).sum();
            return new StockVO(d.getId(), d.getDrugCode(), d.getName(), d.getSpec(), d.getUnit(), d.getRetailPrice(),
                    onHand, locked, available, d.getSafetyStock(), available < d.getSafetyStock(),
                    list.stream().map(b -> new StockVO.BatchVO(b.getId(), b.getBatchNo(), b.getExpireDate(),
                            b.getQtyOnHand(), b.getQtyLocked(), !b.getExpireDate().isAfter(minExpire))).toList());
        }).toList();
    }

    private List<Drug> searchDrugs(String keyword, boolean enabledOnly) {
        String kw = keyword == null ? "" : keyword.trim();
        return drugMapper.selectList(Wrappers.<Drug>lambdaQuery()
                .eq(enabledOnly, Drug::getStatus, 1)
                .and(!kw.isEmpty(), w -> w.likeRight(Drug::getName, kw).or().likeRight(Drug::getDrugCode, kw))
                .orderByAsc(Drug::getDrugCode)
                .last("LIMIT " + OPTION_LIMIT));
    }

    private Map<Long, List<DrugBatch>> batchesOf(Long campusId, List<Drug> drugs) {
        if (drugs.isEmpty()) {
            return Map.of();
        }
        return batchMapper.selectList(Wrappers.<DrugBatch>lambdaQuery()
                        .eq(DrugBatch::getCampusId, campusId)
                        .in(DrugBatch::getDrugId, drugs.stream().map(Drug::getId).toList())
                        .gt(DrugBatch::getQtyOnHand, 0)
                        .orderByAsc(DrugBatch::getExpireDate))
                .stream().collect(Collectors.groupingBy(DrugBatch::getDrugId));
    }

    private static DrugSnapshot toSnapshot(Drug d) {
        return new DrugSnapshot(d.getId(), d.getDrugCode(), d.getName(), d.getSpec(), d.getUnit(), d.getGoodsType(),
                d.getRetailPrice(), d.getInsuranceClass() == 1, d.getTraceRequired() == 1, d.getStatus() == 1);
    }
}
