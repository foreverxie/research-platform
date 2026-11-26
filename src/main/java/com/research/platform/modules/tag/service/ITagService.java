package com.research.platform.modules.tag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.common.vo.TagVO;
import com.research.platform.modules.tag.dto.TagCreateDTO;
import com.research.platform.modules.tag.dto.TagUpdateDTO;
import com.research.platform.modules.tag.entity.Tag;

import java.util.List;

/**
 * 标签服务
 */
public interface ITagService extends IService<Tag> {

    /**
     * 查询当前用户的标签（含文献数量）
     */
    List<TagVO> listWithDocumentCount();

    /**
     * 新建标签
     */
    TagVO create(TagCreateDTO dto);

    /**
     * 修改标签
     */
    TagVO update(Long id, TagUpdateDTO dto);

    /**
     * 删除标签，同时解除文献关联
     */
    void delete(Long id);
}
