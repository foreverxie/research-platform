package com.research.platform.modules.document.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.document.service.IDocumentFavoriteService;
import com.research.platform.modules.document.vo.FavoriteResultVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * 文献收藏接口。
 *
 * <p>URL 保持为 /api/documents/{id}/favorite，以兼容现有前端。</p>
 */
@RestController
@RequestMapping("/api/documents")
public class FavoriteController {

    private final IDocumentFavoriteService favoriteService;

    public FavoriteController(IDocumentFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/{id}/favorite")
    public Result<FavoriteResultVO> toggleFavorite(@PathVariable("id") Long id) {
        return Result.success(favoriteService.toggleFavorite(id));
    }
}
