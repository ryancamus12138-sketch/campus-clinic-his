package io.github.ryancamus.clinic.pharmacy.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库存流水，只追加。记录变化量和变化后的余额，任何时点的库存都能由流水重算核对。
 */
@Getter
@Setter
@TableName("stock_flow")
public class StockFlow {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long batchId;
    private Long drugId;
    private Integer flowType;
    private Integer deltaOnHand;
    private Integer deltaLocked;
    private Integer afterOnHand;
    private Integer afterLocked;
    private Integer bizType;
    private String bizNo;
    private Long operatorId;
    private LocalDateTime createTime;
}
