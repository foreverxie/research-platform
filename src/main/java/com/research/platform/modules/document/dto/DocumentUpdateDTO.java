package com.research.platform.modules.document.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修改文献元数据的请求体
 *
 * <p>前端既可能一次提交完整元数据，也可能只提交标签（文献详情页单独修改标签），
 * 因此所有字段都按“可选更新”处理：未出现的字段保持原值。</p>
 *
 * <p>journal / doi / publicationYear / categoryId 允许显式传 null 来清空，
 * 仅靠字段是否为 null 无法区分“未提交”与“清空”，所以用 {@code *Present}
 * 标记记录请求体中是否真正出现过该字段。</p>
 */
@Data
public class DocumentUpdateDTO {

    /**
     * 论文标题
     */
    @Size(max = 500, message = "论文标题长度不能超过500个字符")
    private String title;

    /**
     * 作者列表
     */
    @Size(max = 100, message = "作者数量不能超过100")
    private List<@NotBlank(message = "作者名称不能为空")
    @Size(max = 200, message = "作者名称长度不能超过200个字符") String> authors;

    /**
     * 论文摘要（前端字段名为 abstract）
     */
    @JsonProperty("abstract")
    @Size(max = 20000, message = "论文摘要长度不能超过20000个字符")
    private String abstractText;

    /**
     * 关键词列表
     */
    @Size(max = 100, message = "关键词数量不能超过100")
    private List<@NotBlank(message = "关键词不能为空")
    @Size(max = 100, message = "关键词长度不能超过100个字符") String> keywords;

    /**
     * 发表年份
     */
    @Min(value = 1000, message = "发表年份不合法")
    @Max(value = 2100, message = "发表年份不合法")
    private Integer publicationYear;

    /**
     * 期刊或会议
     */
    @Size(max = 255, message = "期刊或会议名称不能超过255个字符")
    private String journal;

    /**
     * DOI
     */
    @Size(max = 255, message = "DOI长度不能超过255个字符")
    private String doi;

    /**
     * 文献类型
     */
    @Size(max = 50, message = "文献类型长度不能超过50个字符")
    private String documentType;

    /**
     * 分类ID，传 null 表示移出分类
     */
    private Long categoryId;

    /**
     * 标签ID集合，提交后重建 document_tag；不提交则保持原标签
     */
    private List<Long> tagIds;

    /**
     * 请求体是否包含 publicationYear
     */
    @JsonIgnore
    private boolean publicationYearPresent;

    /**
     * 请求体是否包含 journal
     */
    @JsonIgnore
    private boolean journalPresent;

    /**
     * 请求体是否包含 doi
     */
    @JsonIgnore
    private boolean doiPresent;

    /**
     * 请求体是否包含 categoryId
     */
    @JsonIgnore
    private boolean categoryIdPresent;

    public void setPublicationYear(Integer publicationYear) {
        this.publicationYear = publicationYear;
        this.publicationYearPresent = true;
    }

    public void setJournal(String journal) {
        this.journal = journal;
        this.journalPresent = true;
    }

    public void setDoi(String doi) {
        this.doi = doi;
        this.doiPresent = true;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
        this.categoryIdPresent = true;
    }
}
