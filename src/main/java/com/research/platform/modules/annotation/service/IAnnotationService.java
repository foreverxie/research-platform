package com.research.platform.modules.annotation.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.modules.annotation.dto.AnnotationCreateDTO;
import com.research.platform.modules.annotation.dto.AnnotationUpdateDTO;
import com.research.platform.modules.annotation.entity.Annotation;
import com.research.platform.modules.annotation.vo.AnnotationVO;

import java.util.List;

/**
 * 文献批注服务
 */
public interface IAnnotationService extends IService<Annotation> {

    /**
     * 查询某篇文献下的全部批注（按创建时间倒序）
     */
    List<AnnotationVO> listByDocument(Long documentId);

    /**
     * 新建批注
     */
    AnnotationVO create(AnnotationCreateDTO dto);

    /**
     * 修改批注
     */
    AnnotationVO update(Long id, AnnotationUpdateDTO dto);

    /**
     * 删除批注（逻辑删除）
     */
    void delete(Long id);
}
