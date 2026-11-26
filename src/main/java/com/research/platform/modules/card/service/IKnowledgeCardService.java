package com.research.platform.modules.card.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.modules.card.dto.KnowledgeCardCreateDTO;
import com.research.platform.modules.card.dto.KnowledgeCardUpdateDTO;
import com.research.platform.modules.card.entity.KnowledgeCard;
import com.research.platform.modules.card.vo.KnowledgeCardVO;

import java.util.List;

/**
 * 知识卡片服务
 */
public interface IKnowledgeCardService extends IService<KnowledgeCard> {

    /**
     * 查询当前用户的知识卡片
     */
    List<KnowledgeCardVO> listCards();

    /**
     * 新建知识卡片
     */
    KnowledgeCardVO create(KnowledgeCardCreateDTO dto);

    /**
     * 修改知识卡片
     */
    KnowledgeCardVO update(Long id, KnowledgeCardUpdateDTO dto);

    /**
     * 删除知识卡片（逻辑删除）
     */
    void delete(Long id);
}
