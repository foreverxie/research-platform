package com.research.platform.modules.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 知识卡片创建参数
 */
@Data
public class KnowledgeCardCreateDTO {

    /**
     * 卡片标题
     */
    @NotBlank(message = "卡片标题不能为空")
    @Size(max = 255, message = "卡片标题长度不能超过255个字符")
    private String title;

    /**
     * 卡片内容
     */
    private String content;

    /**
     * 卡片类型：METHOD / MODEL / CONCEPT / CONCLUSION
     */
    @Pattern(
            regexp = "METHOD|MODEL|CONCEPT|CONCLUSION",
            message = "卡片类型只能是 METHOD、MODEL、CONCEPT 或 CONCLUSION"
    )
    private String cardType = "CONCEPT";

    /**
     * 关联文献ID列表
     */
    private List<Long> documentIds;

    /**
     * 关联标签名称列表
     */
    private List<String> tagNames;
}
