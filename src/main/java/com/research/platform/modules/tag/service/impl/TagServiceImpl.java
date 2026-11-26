package com.research.platform.modules.tag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.vo.TagVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentTag;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.mapper.DocumentTagMapper;
import com.research.platform.modules.tag.dto.TagCreateDTO;
import com.research.platform.modules.tag.dto.TagUpdateDTO;
import com.research.platform.modules.tag.entity.Tag;
import com.research.platform.modules.tag.mapper.TagMapper;
import com.research.platform.modules.tag.service.ITagService;
import com.research.platform.utils.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签服务实现
 *
 * <p>文献数量通过当前用户未删除文献与 document_tag 关联统计；
 * 删除标签前先物理删除关联记录，避免外键约束失败。</p>
 */
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements ITagService {

    private static final String DEFAULT_COLOR = "#3978e8";

    private final TagMapper tagMapper;
    private final DocumentMapper documentMapper;
    private final DocumentTagMapper documentTagMapper;

    public TagServiceImpl(
            TagMapper tagMapper,
            DocumentMapper documentMapper,
            DocumentTagMapper documentTagMapper
    ) {
        this.tagMapper = tagMapper;
        this.documentMapper = documentMapper;
        this.documentTagMapper = documentTagMapper;
    }

    /**
     * 查询当前用户的标签（含文献数量）
     */
    @Override
    public List<TagVO> listWithDocumentCount() {
        Long userId = CurrentUser.getCurrentUserId();

        List<Tag> tags = tagMapper.selectList(
                new QueryWrapper<Tag>()
                        .eq("user_id", userId)
                        .orderByAsc("created_at")
                        .orderByAsc("id")
        );
        if (tags.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, Long> counts = countDocumentsByTag(userId);
        List<TagVO> result = new ArrayList<>(tags.size());
        for (Tag tag : tags) {
            result.add(toVO(tag, counts.getOrDefault(tag.getId(), 0L)));
        }
        return result;
    }

    /**
     * 新建标签
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TagVO create(TagCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 规范化名称
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "标签名称不能为空");
        }

        // 2. 同名校验
        checkDuplicateName(userId, name, null);

        // 3. 落库
        Tag tag = new Tag();
        tag.setUserId(userId);
        tag.setName(name);
        tag.setColor(normalizeColor(dto.getColor()));
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            // 数据库唯一索引兜底
            throw new BusinessException(409, "标签已存在");
        }

        Tag saved = tagMapper.selectById(tag.getId());
        return toVO(saved, 0L);
    }

    /**
     * 修改标签
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TagVO update(Long id, TagUpdateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 归属校验
        Tag tag = getOwnedTag(userId, id);

        // 2. 名称修改与重名校验
        if (dto.getName() != null) {
            String name = dto.getName().trim();
            if (name.isEmpty()) {
                throw new BusinessException(400, "标签名称不能为空");
            }
            checkDuplicateName(userId, name, tag.getId());
            tag.setName(name);
        }

        // 3. 颜色修改
        if (dto.getColor() != null) {
            tag.setColor(normalizeColor(dto.getColor()));
        }

        try {
            tagMapper.updateById(tag);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "标签已存在");
        }

        // 4. 返回最新数据
        Tag saved = tagMapper.selectById(tag.getId());
        Map<Long, Long> counts = countDocumentsByTag(userId);
        return toVO(saved, counts.getOrDefault(saved.getId(), 0L));
    }

    /**
     * 删除标签，同时解除文献关联
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 归属校验
        Tag tag = getOwnedTag(userId, id);

        // 2. 先删除 document_tag 关联（外键约束要求）
        documentTagMapper.delete(
                new QueryWrapper<DocumentTag>()
                        .eq("tag_id", tag.getId())
        );

        // 3. 再删除标签
        tagMapper.deleteById(tag.getId());
    }

    /**
     * 查询当前用户拥有的标签，找不到按 404 处理
     */
    private Tag getOwnedTag(Long userId, Long id) {
        Tag tag = tagMapper.selectOne(
                new QueryWrapper<Tag>()
                        .eq("id", id)
                        .eq("user_id", userId)
        );
        if (tag == null) {
            throw new BusinessException(404, "标签不存在");
        }
        return tag;
    }

    /**
     * 同一用户下标签名称唯一
     */
    private void checkDuplicateName(Long userId, String name, Long excludeId) {
        Long count = tagMapper.selectCount(
                new QueryWrapper<Tag>()
                        .eq("user_id", userId)
                        .eq("name", name)
                        .ne(excludeId != null, "id", excludeId)
        );
        if (count != null && count > 0) {
            throw new BusinessException(409, "标签已存在");
        }
    }

    /**
     * 统计当前用户每个标签关联的未删除文献数量
     */
    private Map<Long, Long> countDocumentsByTag(Long userId) {
        // 1. 当前用户的未删除文献 ID（@TableLogic 自动追加 deleted = 0）
        List<Object> rawDocumentIds = documentMapper.selectObjs(
                new QueryWrapper<Document>()
                        .select("id")
                        .eq("user_id", userId)
        );
        if (rawDocumentIds == null || rawDocumentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> documentIds = new ArrayList<>(rawDocumentIds.size());
        for (Object raw : rawDocumentIds) {
            if (raw instanceof Number) {
                documentIds.add(((Number) raw).longValue());
            }
        }
        if (documentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2. 按标签分组统计关联数量
        List<Map<String, Object>> rows = documentTagMapper.selectMaps(
                new QueryWrapper<DocumentTag>()
                        .select("tag_id", "COUNT(*) AS cnt")
                        .in("document_id", documentIds)
                        .groupBy("tag_id")
        );

        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object tagId = columnValue(row, "tag_id", "tagId");
            Object count = columnValue(row, "cnt");
            if (tagId instanceof Number tagIdValue && count instanceof Number countValue) {
                counts.put(tagIdValue.longValue(), countValue.longValue());
            }
        }
        return counts;
    }

    /**
     * 按忽略大小写的方式读取查询结果列值（不同 MySQL 版本返回的列名大小写可能不同）
     */
    private Object columnValue(Map<String, Object> row, String... columnNames) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            for (String columnName : columnNames) {
                if (entry.getKey().equalsIgnoreCase(columnName)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Entity 转 VO
     */
    private TagVO toVO(Tag tag, Long documentCount) {
        if (tag == null) {
            return null;
        }
        TagVO vo = new TagVO();
        vo.setId(tag.getId());
        vo.setName(tag.getName());
        vo.setColor(tag.getColor());
        vo.setDocumentCount(documentCount == null ? 0L : documentCount);
        vo.setCreatedAt(tag.getCreatedAt());
        return vo;
    }

    /**
     * 规范化颜色，空值回退到默认品牌色
     */
    private String normalizeColor(String color) {
        if (color == null) {
            return DEFAULT_COLOR;
        }
        String trimmed = color.trim();
        return trimmed.isEmpty() ? DEFAULT_COLOR : trimmed;
    }
}
