package com.research.platform.modules.document.service;

import java.util.Collection;
import java.util.List;
import com.research.platform.modules.document.vo.FavoriteResultVO;

/**
 * 文献收藏用例。
 */
public interface IDocumentFavoriteService {

    /**
     * 收藏或取消收藏当前用户拥有的文献。
     */
    FavoriteResultVO toggleFavorite(Long documentId);

    /**
     * 删除文献时清除它的收藏关系。
     */
    void removeForDocument(Long documentId);

    /**
     * 查询指定用户收藏的文献ID。
     */
    List<Long> listDocumentIds(Long userId);

    /**
     * 在给定文献范围内查询指定用户收藏的文献ID。
     */
    List<Long> listDocumentIds(Long userId, Collection<Long> documentIds);
}
