package io.github.ryancamus.clinic.pharmacy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.pharmacy.domain.dto.DrugSaveDTO;
import io.github.ryancamus.clinic.pharmacy.domain.entity.Drug;
import io.github.ryancamus.clinic.pharmacy.domain.vo.DrugDetailVO;
import io.github.ryancamus.clinic.pharmacy.enums.PharmacyErrorCode;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 药品目录维护。改零售价只影响之后的划价：已生成的收费明细保存了当时的单价。
 * 停用的药品不能再开具，在库批次照常可以发药、退药。
 */
@Service
@RequiredArgsConstructor
public class DrugAdminService {

    private final DrugMapper drugMapper;

    public PageResult<DrugDetailVO> page(String keyword, long page, long size) {
        String kw = keyword == null ? "" : keyword.trim();
        Page<Drug> p = drugMapper.selectPage(Page.of(page, size), Wrappers.<Drug>lambdaQuery()
                .and(!kw.isEmpty(), w -> w.likeRight(Drug::getDrugCode, kw).or().like(Drug::getName, kw)
                        .or().eq(Drug::getInsuranceCode, kw))
                .orderByAsc(Drug::getDrugCode));
        return new PageResult<>(p.getRecords().stream().map(DrugAdminService::toVO).toList(), p.getTotal(),
                p.getCurrent(), p.getSize());
    }

    public DrugDetailVO create(DrugSaveDTO dto) {
        Drug drug = apply(new Drug(), dto);
        try {
            drugMapper.insert(drug);
        } catch (DuplicateKeyException e) {
            throw new BizException(PharmacyErrorCode.DRUG_CODE_EXISTS);
        }
        return toVO(drug);
    }

    public DrugDetailVO update(Long id, DrugSaveDTO dto) {
        Drug drug = drugMapper.selectById(id);
        BizException.throwIf(drug == null, PharmacyErrorCode.DRUG_NOT_FOUND);
        try {
            drugMapper.updateById(apply(drug, dto));
        } catch (DuplicateKeyException e) {
            throw new BizException(PharmacyErrorCode.DRUG_CODE_EXISTS);
        }
        return toVO(drugMapper.selectById(id));
    }

    static DrugDetailVO toVO(Drug d) {
        return new DrugDetailVO(d.getId(), d.getDrugCode(), d.getInsuranceCode(), d.getName(), d.getSpec(), d.getUnit(),
                d.getGoodsType(), d.getRetailPrice(), d.getInsuranceClass(), d.getTraceRequired(), d.getManufacturer(),
                d.getApprovalNo(), d.getSafetyStock(), d.getStatus());
    }

    private static Drug apply(Drug d, DrugSaveDTO dto) {
        d.setDrugCode(dto.drugCode());
        d.setInsuranceCode(blankToNull(dto.insuranceCode()));
        d.setName(dto.name().trim());
        d.setSpec(dto.spec().trim());
        d.setUnit(dto.unit().trim());
        d.setGoodsType(dto.goodsType());
        d.setRetailPrice(Money.of(dto.retailPrice()));
        d.setInsuranceClass(dto.insuranceClass());
        d.setTraceRequired(dto.traceRequired());
        d.setManufacturer(blankToNull(dto.manufacturer()));
        d.setApprovalNo(blankToNull(dto.approvalNo()));
        d.setSafetyStock(dto.safetyStock());
        d.setStatus(dto.status());
        return d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
