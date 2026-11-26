package com.research.platform.modules.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.category.dto.CategoryCreateDTO;
import com.research.platform.modules.category.dto.CategoryUpdateDTO;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.mapper.CategoryMapper;
import com.research.platform.modules.category.service.ICategoryService;
import com.research.platform.modules.category.vo.CategoryVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.utils.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分类服务实现
 *
 * <p>分类树一次查询后在内存中组装，文献数量通过一次 group by 统计，
 * 所有读写都限定在当前登录用户范围内。</p>
 */
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category>
        implements ICategoryService {

    private final CategoryMapper categoryMapper;
    private final DocumentMapper documentMapper;
    private final JdbcTemplate jdbcTemplate;

    public CategoryServiceImpl(
            CategoryMapper categoryMapper,
            DocumentMapper documentMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.categoryMapper = categoryMapper;
        this.documentMapper = documentMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询当前用户的分类树
     */
    @Override
    public List<CategoryVO> listTree() {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 一次查出当前用户的全部分类
        List<Category> categories = categoryMapper.selectList(
                new QueryWrapper<Category>()
                        .eq("user_id", userId)
                        .orderByAsc("created_at")
                        .orderByAsc("id")
        );

        if (categories.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 一次 group by 统计各分类的直属文献数量
        Map<Long, Long> documentCounts = countDocumentsByCategory(userId);

        // 3. 按父分类分组
        Map<Long, List<Category>> childrenMap = new HashMap<>();
        Set<Long> categoryIds = new HashSet<>();
        for (Category category : categories) {
            categoryIds.add(category.getId());
        }
        for (Category category : categories) {
            Long parentId = category.getParentId() == null ? 0L : category.getParentId();
            childrenMap.computeIfAbsent(parentId, key -> new ArrayList<>()).add(category);
        }

        // 4. 根节点：parentId 为 0，或父节点已不存在（容错历史数据）
        List<Category> roots = new ArrayList<>();
        for (Category category : categories) {
            Long parentId = category.getParentId();
            if (parentId == null || parentId == 0L || !categoryIds.contains(parentId)) {
                roots.add(category);
            }
        }

        // 5. 递归组装，visited 防止异常数据形成环时无限递归
        Set<Long> visited = new HashSet<>();
        List<CategoryVO> tree = new ArrayList<>();
        for (Category root : roots) {
            CategoryVO node = buildNode(root, childrenMap, documentCounts, visited);
            if (node != null) {
                tree.add(node);
            }
        }
        // 6. 兜底：环状异常数据中的节点也尽量展示，避免静默丢失
        for (Category category : categories) {
            if (!visited.contains(category.getId())) {
                CategoryVO node = buildNode(category, childrenMap, documentCounts, visited);
                if (node != null) {
                    tree.add(node);
                }
            }
        }
        return tree;
    }

    /**
     * 新建分类
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryVO create(CategoryCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 规范化名称
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空");
        }

        // 2. 父分类校验
        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        requireParent(userId, parentId);

        // 3. 同一父分类下重名校验
        checkDuplicateName(userId, parentId, name, null);

        // 4. 落库
        Category category = new Category();
        category.setUserId(userId);
        category.setName(name);
        category.setParentId(parentId);
        category.setDescription(normalizeNullable(dto.getDescription()));
        categoryMapper.insert(category);

        Category saved = categoryMapper.selectById(category.getId());
        return toVO(saved, 0L, new ArrayList<>());
    }

    /**
     * 修改分类（改名、移动、描述）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryVO update(Long id, CategoryUpdateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 归属校验（跨用户一律按不存在处理）
        Category category = getOwnedCategory(userId, id);

        // 2. 计算目标名称与父分类
        String targetName = dto.getName() == null
                ? category.getName()
                : dto.getName().trim();
        if (targetName == null || targetName.isEmpty()) {
            throw new BusinessException(400, "分类名称不能为空");
        }

        Long targetParentId = dto.getParentId() == null
                ? (category.getParentId() == null ? 0L : category.getParentId())
                : dto.getParentId();

        // 3. 父分类必须存在且属于当前用户
        requireParent(userId, targetParentId);

        // 4. 不能移动到自身或后代下
        checkNotSelfOrDescendant(userId, category.getId(), targetParentId);

        // 5. 重名校验
        checkDuplicateName(userId, targetParentId, targetName, category.getId());

        // 6. 更新（使用 UpdateWrapper 以支持 description 显式清空）
        UpdateWrapper<Category> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", category.getId())
                .eq("user_id", userId)
                .set("name", targetName)
                .set("parent_id", targetParentId);
        if (dto.isDescriptionPresent()) {
            String description = normalizeNullable(dto.getDescription());
            if (description == null) {
                wrapper.setSql("description = NULL");
            } else {
                wrapper.set("description", description);
            }
        }
        categoryMapper.update(null, wrapper);

        // 7. 返回最新数据
        Category saved = categoryMapper.selectById(category.getId());
        Long documentCount = documentMapper.selectCount(
                new QueryWrapper<Document>()
                        .eq("user_id", userId)
                        .eq("category_id", category.getId())
        );
        return toVO(saved, documentCount == null ? 0L : documentCount, new ArrayList<>());
    }

    /**
     * 删除分类（物理删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 归属校验
        Category category = getOwnedCategory(userId, id);

        // 2. 存在子分类时拒绝
        Long childCount = categoryMapper.selectCount(
                new QueryWrapper<Category>()
                        .eq("user_id", userId)
                        .eq("parent_id", category.getId())
        );
        if (childCount != null && childCount > 0) {
            throw new BusinessException(409, "该分类下存在子分类，请先处理子分类");
        }

        // 3. 仍有未删除文献引用时拒绝（document 表 @TableLogic 自动过滤已删除文献）
        Long documentCount = documentMapper.selectCount(
                new QueryWrapper<Document>()
                        .eq("user_id", userId)
                        .eq("category_id", category.getId())
        );
        if (documentCount != null && documentCount > 0) {
            throw new BusinessException(
                    409,
                    "该分类下还有 " + documentCount + " 篇文献，请先迁移文献或将其移出分类"
            );
        }

        // 4. 逻辑删除的文献仍保留 document 行，会通过外键阻止分类物理删除。
        //    这里用 JdbcTemplate 统计「包含 deleted = 1」的全部引用，并解除这些引用。
        Long totalDocumentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document WHERE category_id = ?",
                Long.class,
                category.getId()
        );
        if (totalDocumentCount != null && totalDocumentCount > 0) {
            // 不加 deleted 条件，否则逻辑删除的文献仍会持有外键
            jdbcTemplate.update(
                    "UPDATE document SET category_id = NULL WHERE category_id = ?",
                    category.getId()
            );
        }

        // 5. 物理删除
        categoryMapper.deleteById(category.getId());
    }

    /**
     * 查询当前用户拥有的分类，找不到按 404 处理
     */
    private Category getOwnedCategory(Long userId, Long id) {
        Category category = categoryMapper.selectOne(
                new QueryWrapper<Category>()
                        .eq("id", id)
                        .eq("user_id", userId)
        );
        if (category == null) {
            throw new BusinessException(404, "分类不存在");
        }
        return category;
    }

    /**
     * 校验父分类存在且属于当前用户；0 表示根分类
     */
    private void requireParent(Long userId, Long parentId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        Category parent = categoryMapper.selectOne(
                new QueryWrapper<Category>()
                        .eq("id", parentId)
                        .eq("user_id", userId)
        );
        if (parent == null) {
            throw new BusinessException(404, "上级分类不存在");
        }
    }

    /**
     * 同一父分类下名称唯一
     */
    private void checkDuplicateName(Long userId, Long parentId, String name, Long excludeId) {
        Long count = categoryMapper.selectCount(
                new QueryWrapper<Category>()
                        .eq("user_id", userId)
                        .eq("parent_id", parentId)
                        .eq("name", name)
                        .ne(excludeId != null, "id", excludeId)
        );
        if (count != null && count > 0) {
            throw new BusinessException(409, "同一父分类下已存在同名分类");
        }
    }

    /**
     * 校验目标父分类不是自身或自身的后代
     */
    private void checkNotSelfOrDescendant(Long userId, Long categoryId, Long targetParentId) {
        if (targetParentId == null || targetParentId == 0L) {
            return;
        }
        if (Objects.equals(categoryId, targetParentId)) {
            throw new BusinessException(400, "不能将分类移动到自身或其子分类下");
        }

        // 从目标父分类逐级向上查找，若遇到自身则说明目标是自身的后代
        Set<Long> visited = new HashSet<>();
        Long cursor = targetParentId;
        while (cursor != null && cursor != 0L) {
            if (!visited.add(cursor)) {
                break;
            }
            if (Objects.equals(categoryId, cursor)) {
                throw new BusinessException(400, "不能将分类移动到自身或其子分类下");
            }
            Category parent = categoryMapper.selectById(cursor);
            if (parent == null || !Objects.equals(parent.getUserId(), userId)) {
                break;
            }
            cursor = parent.getParentId();
        }
    }

    /**
     * 一次 group by 统计当前用户各分类的直属文献数量
     */
    private Map<Long, Long> countDocumentsByCategory(Long userId) {
        List<Map<String, Object>> rows = documentMapper.selectMaps(
                new QueryWrapper<Document>()
                        .select("category_id", "COUNT(*) AS cnt")
                        .eq("user_id", userId)
                        .isNotNull("category_id")
                        .groupBy("category_id")
        );

        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object categoryId = columnValue(row, "category_id", "categoryId");
            Object count = columnValue(row, "cnt");
            if (categoryId instanceof Number categoryIdValue
                    && count instanceof Number countValue) {
                counts.put(categoryIdValue.longValue(), countValue.longValue());
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
     * 递归组装分类节点
     */
    private CategoryVO buildNode(
            Category category,
            Map<Long, List<Category>> childrenMap,
            Map<Long, Long> documentCounts,
            Set<Long> visited
    ) {
        if (category.getId() == null || !visited.add(category.getId())) {
            return null;
        }

        List<CategoryVO> children = new ArrayList<>();
        List<Category> childCategories = childrenMap.getOrDefault(
                category.getId(),
                Collections.emptyList()
        );
        for (Category child : childCategories) {
            CategoryVO childNode = buildNode(child, childrenMap, documentCounts, visited);
            if (childNode != null) {
                children.add(childNode);
            }
        }

        return toVO(
                category,
                documentCounts.getOrDefault(category.getId(), 0L),
                children
        );
    }

    /**
     * Entity 转 VO
     */
    private CategoryVO toVO(Category category, Long documentCount, List<CategoryVO> children) {
        if (category == null) {
            return null;
        }
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setUserId(category.getUserId());
        vo.setName(category.getName());
        vo.setParentId(category.getParentId());
        vo.setDescription(category.getDescription());
        vo.setDocumentCount(documentCount == null ? 0L : documentCount);
        vo.setCreatedAt(category.getCreatedAt());
        vo.setChildren(children == null ? new ArrayList<>() : children);
        return vo;
    }

    /**
     * 去除首尾空白，空串按 null 处理
     */
    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
