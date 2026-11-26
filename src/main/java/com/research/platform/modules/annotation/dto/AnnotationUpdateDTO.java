package com.research.platform.modules.annotation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批注修改参数
 *
 * <p>只更新请求中出现的字段：可空字段（引用原文、页码、颜色）需要支持显式清空，
 * 因此额外记录字段是否出现在请求中，避免把「未传」误判为「清空」。</p>
 */
@Data
public class AnnotationUpdateDTO {

    /**
     * 被批注的原文片段
     */
    @Size(max = 2000, message = "引用原文长度不能超过2000个字符")
    private String quote;

    /**
     * 批注内容
     */
    @Size(max = 5000, message = "批注内容长度不能超过5000个字符")
    private String content;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNumber;

    /**
     * 批注颜色
     */
    @Size(max = 20, message = "批注颜色长度不能超过20个字符")
    private String color;

    @JsonIgnore
    private boolean quotePresent;

    @JsonIgnore
    private boolean contentPresent;

    @JsonIgnore
    private boolean pageNumberPresent;

    @JsonIgnore
    private boolean colorPresent;

    public void setQuote(String quote) {
        this.quote = quote;
        this.quotePresent = true;
    }

    public void setContent(String content) {
        this.content = content;
        this.contentPresent = true;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
        this.pageNumberPresent = true;
    }

    public void setColor(String color) {
        this.color = color;
        this.colorPresent = true;
    }

    @JsonIgnore
    public boolean isQuotePresent() {
        return quotePresent;
    }

    @JsonIgnore
    public boolean isContentPresent() {
        return contentPresent;
    }

    @JsonIgnore
    public boolean isPageNumberPresent() {
        return pageNumberPresent;
    }

    @JsonIgnore
    public boolean isColorPresent() {
        return colorPresent;
    }
}
