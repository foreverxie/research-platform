package com.research.platform.modules.note.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 笔记创建参数
 */
@Data
public class NoteCreateDTO {

    /**
     * 笔记标题，缺省为「未命名笔记」
     */
    @Size(max = 100, message = "笔记标题长度不能超过100个字符")
    private String title;

    /**
     * 笔记内容
     */
    private String content;

    /**
     * 关联文献ID，可为空
     */
    private Long documentId;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNumber;

    /**
     * 关联文本块业务标识，可为空
     */
    @Size(max = 64, message = "文本块标识长度不能超过64个字符")
    private String chunkId;
}
