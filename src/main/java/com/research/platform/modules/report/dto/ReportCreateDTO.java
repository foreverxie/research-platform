package com.research.platform.modules.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 报告创建参数
 */
@Data
public class ReportCreateDTO {

    /**
     * 报告标题
     */
    @NotBlank(message = "报告标题不能为空")
    @Size(max = 255, message = "报告标题长度不能超过255个字符")
    private String title;

    /**
     * 报告类型：LITERATURE_ANALYSIS / TOPIC_REVIEW / COMPARE / CUSTOM
     */
    @Pattern(
            regexp = "LITERATURE_ANALYSIS|TOPIC_REVIEW|COMPARE|CUSTOM",
            message = "报告类型只能是 LITERATURE_ANALYSIS、TOPIC_REVIEW、COMPARE 或 CUSTOM"
    )
    private String reportType = "LITERATURE_ANALYSIS";

    /**
     * 报告覆盖的文献ID列表
     */
    @NotEmpty(message = "请至少选择一篇文献")
    private List<Long> documentIds;
}
