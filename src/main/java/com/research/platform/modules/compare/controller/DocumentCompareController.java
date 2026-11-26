package com.research.platform.modules.compare.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.compare.dto.CompareRequestDTO;
import com.research.platform.modules.compare.service.IDocumentCompareService;
import com.research.platform.modules.compare.vo.CompareResultVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文献对比接口
 *
 * <p>路径与前端约定为 POST /api/documents/compare，属于对比模块，
 * 与文献模块的 /api/documents/{id} 无冲突。</p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentCompareController {

    private final IDocumentCompareService documentCompareService;

    public DocumentCompareController(IDocumentCompareService documentCompareService) {
        this.documentCompareService = documentCompareService;
    }

    /**
     * 多篇文献对比
     */
    @PostMapping("/compare")
    public Result<CompareResultVO> compare(@Valid @RequestBody CompareRequestDTO dto) {
        return Result.success(documentCompareService.compare(dto));
    }
}
