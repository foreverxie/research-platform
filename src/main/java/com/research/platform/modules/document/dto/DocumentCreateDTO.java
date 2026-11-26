
package com.research.platform.modules.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DocumentCreateDTO {

    /**
     * 论文标题
     */
    @NotBlank(message = "论文标题不能为空")
    @Size(max = 500, message = "论文标题长度不能超过500个字符")
    private String title;

    /**
     * 作者列表
     */
    @Size(max = 100, message = "作者数量不能超过100")
    private List<@NotBlank(message = "作者名称不能为空")
    @Size(max = 200, message = "作者名称长度不能超过200个字符") String> authors;

    /**
     * 论文摘要
     */
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
     * 文献语言
     */
    @Size(max = 30, message = "文献语言长度不能超过30个字符")
    private String language;

    /**
     * 文献类型
     */
    @Size(max = 50, message = "文献类型长度不能超过50个字符")
    private String documentType;

    /**
     * 分类ID
     */
    private Long categoryId;

    /**
     * 文献来源
     */
    @Size(max = 100, message = "文献来源长度不能超过100个字符")
    private String source;
}
