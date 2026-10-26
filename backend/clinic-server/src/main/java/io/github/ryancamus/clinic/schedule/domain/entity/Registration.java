package io.github.ryancamus.clinic.schedule.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 挂号/预约。表中还有生成列 active_slot_id（不映射到实体），
 * 配合唯一索引保证“同一患者对同一号源最多一条有效预约”。
 */
@Getter
@Setter
@TableName("registration")
public class Registration extends BaseEntity {

    private String regNo;
    private Long patientId;
    private Long slotId;
    private Integer status;
    private Long chargeOrderId;
    private LocalDateTime expireTime;
}
