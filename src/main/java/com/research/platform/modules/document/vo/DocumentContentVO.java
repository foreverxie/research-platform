package com.research.platform.modules.document.vo;

import lombok.Data;

import java.util.List;

/**
 * 解析正文视图
 *
 * <p>同时返回按章节合并的 sections 与原始 chunks，阅读器可任选其一渲染。</p>
 */
@Data
public class DocumentContentVO {

    private Long documentId;

    private Long parseRecordId;

    private Integer pageCount;

    private List<DocumentSectionVO> sections;

    private List<DocumentChunkVO> chunks;
}
