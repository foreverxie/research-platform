package com.research.platform.modules.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_file")
public class DocumentFile {

    /**
     * 文件ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 文件类型
     */
    @TableField("file_type")
    private String fileType;

    /**
     * 文件名
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 文件存储路径
     */
    @TableField("storage_path")
    private String storagePath;

    /**
     * 文件大小
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * 文件哈希
     */
    @TableField("file_hash")
    private String fileHash;

    /**
     * 文件版本
     */
    @TableField("version")
    private Integer version;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
