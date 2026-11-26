package com.research.platform.modules.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("document_tag")
public class DocumentTag {

    /**
     * 关联ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 标签ID
     */
    @TableField("tag_id")
    private Long tagId;
}
