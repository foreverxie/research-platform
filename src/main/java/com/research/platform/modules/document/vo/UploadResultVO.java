package com.research.platform.modules.document.vo;

import lombok.Data;

/**
 * 上传结果
 */
@Data
public class UploadResultVO {

    /**
     * 新建文献
     */
    private DocumentVO document;

    /**
     * 新建的 PDF 解析任务ID
     */
    private Long taskId;

    /**
     * 新建的解析记录ID
     */
    private Long parseRecordId;
}
