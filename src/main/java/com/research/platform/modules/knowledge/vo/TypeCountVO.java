package com.research.platform.modules.knowledge.vo;

import lombok.Data;

/**
 * 实体类型统计项
 */
@Data
public class TypeCountVO {

    /**
     * 实体类型
     */
    private String name;

    /**
     * 该类型的实体数量
     */
    private Integer value;

    public TypeCountVO() {
    }

    public TypeCountVO(String name, Integer value) {
        this.name = name;
        this.value = value;
    }
}
