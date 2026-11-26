package com.research.platform.modules.document.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起 AI 分析的请求体
 *
 */
@Data
public class AnalyzeRequestDTO {

    /**
     * 分析类型：FULL_ANALYSIS / SUMMARY / METHOD / INNOVATION / CONCLUSION / KEYWORD
     */
    @Size(max = 50, message = "分析类型长度不能超过50个字符")
    private String analysisType;
}
