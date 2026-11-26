package com.research.platform.modules.citation.service;

import com.research.platform.modules.citation.vo.CitationVO;

/**
 * 文献引用服务
 */
public interface ICitationService {

    /**
     * 生成单篇文献的引用文本
     *
     * @param documentId 文献ID（校验归属，不存在按 404 处理）
     * @param style      引用格式，当前仅支持 gb7714（缺省）
     * @return 引用文本 + 格式
     */
    CitationVO buildCitation(Long documentId, String style);
}
