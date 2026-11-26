package com.research.platform.modules.recommend.controller;

import com.research.platform.common.result.Result;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.modules.recommend.service.IRecommendationService;
import com.research.platform.modules.recommend.vo.TopicVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文献推荐接口
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final IRecommendationService recommendationService;

    public RecommendationController(IRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * 相似文献推荐；不传 documentId 时按最近更新的文献推荐
     */
    @GetMapping
    public Result<List<RecommendationItemVO>> recommendations(
            @RequestParam(value = "documentId", required = false) Long documentId) {
        return Result.success(recommendationService.recommendations(documentId));
    }

    /**
     * 主题推荐
     */
    @GetMapping("/topics")
    public Result<List<TopicVO>> topics() {
        return Result.success(recommendationService.topics());
    }
}
