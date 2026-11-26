package com.research.platform.modules.report.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 报告导出文件
 *
 * <p>导出接口直接返回文件字节，不经过统一 {@code Result} 包装，
 * 因此这里同时携带下载文件名与 Content-Type。</p>
 */
@Getter
@AllArgsConstructor
public class ExportFile {

    /**
     * 下载文件名（含扩展名，可能包含中文）
     */
    private final String fileName;

    /**
     * HTTP Content-Type
     */
    private final String contentType;

    /**
     * 文件内容
     */
    private final byte[] content;
}
