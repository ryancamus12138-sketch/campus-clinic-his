package io.github.ryancamus.clinic.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_campus")
public class SysCampus extends BaseEntity {

    private String name;
    private String address;
}
