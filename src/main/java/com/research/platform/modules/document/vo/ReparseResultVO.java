package com.research.platform.modules.document.vo;

import lombok.Data;

/**
 * 重新解析结果
 */
@Data
public class ReparseResultVO {

    /**
     * 新建的 PDF 解析任务ID
     */
    private Long taskId;

    /**
     * 新建的解析记录ID
     */
    private Long parseRecordId;
}
