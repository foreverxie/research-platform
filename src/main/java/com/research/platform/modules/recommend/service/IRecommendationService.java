package com.research.platform.modules.recommend.service;

import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.modules.recommend.vo.TopicVO;

import java.util.List;

/**
 * 文献推荐服务
 */
public interface IRecommendationService {

    /**
     * 相似文献推荐
     *
     * @param documentId 目标文献；为空时取当前用户最近更新的一篇文献
     */
    List<RecommendationItemVO> recommendations(Long documentId);

    /**
     * 主题推荐（基于当前用户文献关键词频次）
     */
    List<TopicVO> topics();
}
