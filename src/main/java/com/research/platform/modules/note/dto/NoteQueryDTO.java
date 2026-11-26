package com.research.platform.modules.note.dto;

import com.research.platform.common.dto.PageQueryDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 笔记分页查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NoteQueryDTO extends PageQueryDTO {

    /**
     * 关键词，模糊匹配标题、内容或关联文献标题
     */
    private String keyword;

    /**
     * 按关联文献筛选
     */
    private Long documentId;
}
