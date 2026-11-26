package com.research.platform.modules.task.vo;

import com.research.platform.common.vo.TaskVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminTaskVO extends TaskVO {
    private Long userId;
    private String ownerUsername;
}
