package io.github.ryancamus.clinic.event.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("outbox_message")
public class OutboxMessage {

    public static final int PENDING = 0;
    public static final int DELIVERED = 1;
    public static final int DEAD = 2;

    @TableId(type = IdType.INPUT)
    private Long id;
    private String eventType;
    private Long aggregateId;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
}
