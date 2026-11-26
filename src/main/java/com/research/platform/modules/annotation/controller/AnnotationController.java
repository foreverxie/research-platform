package com.research.platform.modules.annotation.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.annotation.dto.AnnotationCreateDTO;
import com.research.platform.modules.annotation.dto.AnnotationUpdateDTO;
import com.research.platform.modules.annotation.service.IAnnotationService;
import com.research.platform.modules.annotation.vo.AnnotationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文献批注接口
 *
 * <p>只能操作当前用户拥有的文献与批注。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnnotationController {

    private final IAnnotationService annotationService;

    /**
     * 某篇文献下的批注列表（按创建时间倒序）
     */
    @GetMapping("/documents/{id}/annotations")
    public Result<List<AnnotationVO>> listByDocument(@PathVariable("id") Long id) {
        return Result.success(annotationService.listByDocument(id));
    }

    /**
     * 新建批注
     */
    @PostMapping("/annotations")
    public Result<AnnotationVO> create(@Valid @RequestBody AnnotationCreateDTO dto) {
        return Result.success(annotationService.create(dto));
    }

    /**
     * 修改批注
     */
    @PutMapping("/annotations/{id}")
    public Result<AnnotationVO> update(
            @PathVariable Long id,
            @Valid @RequestBody AnnotationUpdateDTO dto
    ) {
        return Result.success(annotationService.update(id, dto));
    }

    /**
     * 删除批注（逻辑删除）
     */
    @DeleteMapping("/annotations/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        annotationService.delete(id);
        return Result.success(null);
    }
}
