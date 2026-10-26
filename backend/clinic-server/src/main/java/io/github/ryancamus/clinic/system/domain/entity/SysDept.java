package io.github.ryancamus.clinic.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_dept")
public class SysDept extends BaseEntity {

    private Long campusId;
    private String deptCode;
    private String deptName;
    /** 1 临床科室 2 医技 3 药房 4 收费处 5 行政 */
    private Integer deptType;
    private Integer status;
    private Integer sort;
}
