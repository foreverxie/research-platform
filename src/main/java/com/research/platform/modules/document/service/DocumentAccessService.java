package com.research.platform.modules.document.service;

import com.research.platform.modules.document.entity.Document;

import java.util.Collection;
import java.util.List;

/**
 * 文献访问服务（跨模块契约）
 *
 * <p>所有涉及文献的接口都必须经过归属校验，禁止仅凭文献ID访问他人数据
 * （需求 4.4 / 数据库设计 10.1）。其他模块统一通过本接口读取与校验文献。</p>
 */
public interface DocumentAccessService {

    /**
     * 校验当前登录用户拥有该文献
     */
    Document requireOwned(Long documentId);

    /**
     * 校验指定用户拥有该文献
     */
    Document requireOwned(Long userId, Long documentId);

    /**
     * 查询指定用户可访问的文献（自动过滤无权访问的ID）
     */
    List<Document> listOwned(Long userId, Collection<Long> documentIds);

    /**
     * 查询指定用户的全部文献
     */
    List<Document> listOwned(Long userId);

    /**
     * 取文献标题，不存在时返回 null
     */
    String titleOf(Long documentId);
}
