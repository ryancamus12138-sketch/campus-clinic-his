package io.github.ryancamus.clinic.finance.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@TableName("recon_diff")
public class ReconDiff extends BaseEntity {

    public static final int STATUS_OPEN = 0;

    private LocalDate reconDate;
    private Integer channel;
    /** 1 长款 2 短款 3 金额不符 */
    private Integer diffType;
    private String payNo;
    private String channelTradeNo;
    private Money ourAmount;
    private Money channelAmount;
    private Integer status;
    private String remark;
}
