package com.research.platform.modules.note.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 笔记修改参数
 *
 * <p>除标题、内容外，可空的关联字段需要支持显式清空，
 * 因此额外记录字段是否出现在请求中，避免把「未传」误判为「清空」。</p>
 */
@Data
public class NoteUpdateDTO {

    /**
     * 笔记标题
     */
    @Size(max = 100, message = "笔记标题长度不能超过100个字符")
    private String title;

    /**
     * 笔记内容
     */
    private String content;

    /**
     * 关联文献ID
     */
    private Long documentId;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNumber;

    /**
     * 关联文本块业务标识
     */
    @Size(max = 64, message = "文本块标识长度不能超过64个字符")
    private String chunkId;

    @JsonIgnore
    private boolean documentIdPresent;

    @JsonIgnore
    private boolean pageNumberPresent;

    @JsonIgnore
    private boolean chunkIdPresent;

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
        this.documentIdPresent = true;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
        this.pageNumberPresent = true;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
        this.chunkIdPresent = true;
    }

    @JsonIgnore
    public boolean isDocumentIdPresent() {
        return documentIdPresent;
    }

    @JsonIgnore
    public boolean isPageNumberPresent() {
        return pageNumberPresent;
    }

    @JsonIgnore
    public boolean isChunkIdPresent() {
        return chunkIdPresent;
    }
}
