package com.research.platform.modules.task.dto;

import com.research.platform.common.dto.PageQueryDTO;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 任务分页查询参数
 *
 * <p>对应前端 {@code TaskQuery}：page、size、status、taskType、keyword。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskQueryDTO extends PageQueryDTO {

    /**
     * 任务状态：PENDING/PROCESSING/SUCCESS/FAILED/CANCELLED
     */
    @Size(max = 30, message = "任务状态长度不能超过30个字符")
    private String status;

    /**
     * 任务类型：PDF_PARSE/AI_ANALYSIS 等
     */
    @Size(max = 50, message = "任务类型长度不能超过50个字符")
    private String taskType;

    /**
     * 关键字：匹配任务编号或关联文献标题
     */
    @Size(max = 100, message = "关键字长度不能超过100个字符")
    private String keyword;
}
