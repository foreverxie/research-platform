package com.research.platform.modules.compare.service;

import com.research.platform.modules.compare.dto.CompareRequestDTO;
import com.research.platform.modules.compare.vo.CompareResultVO;

/**
 * 文献对比服务
 */
public interface IDocumentCompareService {

    /**
     * 对比 2-6 篇文献的研究问题、方法、模型、数据集、结果、贡献与局限性
     */
    CompareResultVO compare(CompareRequestDTO dto);
}
