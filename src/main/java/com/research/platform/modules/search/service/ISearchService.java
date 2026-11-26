package com.research.platform.modules.search.service;

import com.research.platform.modules.search.dto.SearchRequestDTO;
import com.research.platform.modules.search.vo.SearchResponseVO;

/**
 * 智能检索服务
 */
public interface ISearchService {

    /**
     * 关键词 / 语义 / 混合检索（语义不可用时自动降级为关键词检索）
     */
    SearchResponseVO search(SearchRequestDTO dto);
}
