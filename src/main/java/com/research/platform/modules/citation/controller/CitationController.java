package com.research.platform.modules.citation.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.citation.service.ICitationService;
import com.research.platform.modules.citation.vo.CitationVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文献引用接口
 */
@RestController
@RequestMapping("/api/documents")
public class CitationController {

    private final ICitationService citationService;

    public CitationController(ICitationService citationService) {
        this.citationService = citationService;
    }

    /**
     * 单篇文献引用
     *
     * <p>{@code style} 缺省为 gb7714，目前仅支持 gb7714，其它取值返回 400。</p>
     */
    @GetMapping("/{id}/citation")
    public Result<Map<String, Object>> citation(
            @PathVariable("id") Long id,
            @RequestParam(value = "style", required = false, defaultValue = "gb7714") String style) {

        CitationVO citation = citationService.buildCitation(id, style);

        Map<String, Object> data = new LinkedHashMap<>(2);
        data.put("style", citation.getStyle());
        data.put("text", citation.getText());
        return Result.success(data);
    }
}
