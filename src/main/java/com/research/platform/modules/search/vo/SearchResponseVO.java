package com.research.platform.modules.search.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能检索响应
 */
@Data
public class SearchResponseVO {

    /**
     * 实际执行的检索模式；语义不可用时会降级为 keyword
     */
    private String mode;

    /**
     * 耗时（毫秒）
     */
    private Integer took;

    /**
     * 结果条数
     */
    private Integer total;

    private List<SearchHitVO> records = new ArrayList<>();

    /**
     * 提示信息；语义检索降级或结果为空时给出说明
     */
    private String notice;
}
