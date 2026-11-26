package com.research.platform.modules.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.document.entity.Favorite;
import com.research.platform.modules.document.mapper.FavoriteMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.document.service.IDocumentFavoriteService;
import com.research.platform.modules.document.vo.FavoriteResultVO;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Service
public class DocumentFavoriteServiceImpl implements IDocumentFavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final DocumentAccessService documentAccessService;

    public DocumentFavoriteServiceImpl(FavoriteMapper favoriteMapper,
                                       DocumentAccessService documentAccessService) {
        this.favoriteMapper = favoriteMapper;
        this.documentAccessService = documentAccessService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FavoriteResultVO toggleFavorite(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        documentAccessService.requireOwned(userId, documentId);

        Favorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getDocumentId, documentId)
                .last("LIMIT 1"));

        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return new FavoriteResultVO(false);
        } else {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setDocumentId(documentId);
            favoriteMapper.insert(favorite);
            return new FavoriteResultVO(true);
        }
    }

    @Override
    public void removeForDocument(Long documentId) {
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getDocumentId, documentId));
    }

    @Override
    public List<Long> listDocumentIds(Long userId) {
        return favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .select(Favorite::getDocumentId))
                .stream()
                .map(Favorite::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<Long> listDocumentIds(Long userId, Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .in(Favorite::getDocumentId, documentIds)
                        .select(Favorite::getDocumentId))
                .stream()
                .map(Favorite::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
