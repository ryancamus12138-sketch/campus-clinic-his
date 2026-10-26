package io.github.ryancamus.clinic.finance.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日报预聚合：按 日期 × 院区 × 项目类型 × 渠道 × 患者类型 汇总资金流水。
 * 历史日期查预聚合表，当天直接扫当天流水，月报不再扫百万级明细。
 */
@Getter
@Setter
@TableName("report_daily_agg")
public class ReportDailyAgg {

    @TableId(type = IdType.INPUT)
    private Long id;
    private LocalDate statDate;
    private Long campusId;
    private Integer itemType;
    private Integer channel;
    private Integer patientType;
    private Integer orderCount;
    private BigDecimal totalAmount;
    private BigDecimal insuranceAmount;
    private BigDecimal selfAmount;
    private BigDecimal refundAmount;
    private LocalDateTime updateTime;
}
