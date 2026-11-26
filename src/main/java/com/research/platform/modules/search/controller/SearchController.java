package com.research.platform.modules.search.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.search.dto.SearchRequestDTO;
import com.research.platform.modules.search.service.ISearchService;
import com.research.platform.modules.search.vo.SearchResponseVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能检索接口
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final ISearchService searchService;

    /**
     * 关键词 / 语义 / 混合检索
     */
    @PostMapping
    public Result<SearchResponseVO> search(@Valid @RequestBody SearchRequestDTO dto) {
        return Result.success(searchService.search(dto));
    }
}
