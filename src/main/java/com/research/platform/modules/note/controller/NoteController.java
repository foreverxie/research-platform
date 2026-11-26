package com.research.platform.modules.note.controller;

import com.research.platform.common.result.PageResult;
import com.research.platform.common.result.Result;
import com.research.platform.common.vo.NoteVO;
import com.research.platform.modules.note.dto.NoteCreateDTO;
import com.research.platform.modules.note.dto.NoteQueryDTO;
import com.research.platform.modules.note.dto.NoteUpdateDTO;
import com.research.platform.modules.note.service.INoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 科研笔记接口
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final INoteService noteService;

    public NoteController(INoteService noteService) {
        this.noteService = noteService;
    }

    /**
     * 分页查询笔记
     */
    @GetMapping
    public Result<PageResult<NoteVO>> page(@ModelAttribute NoteQueryDTO query) {
        return Result.success(noteService.page(query));
    }

    /**
     * 新建笔记
     */
    @PostMapping
    public Result<NoteVO> create(@Valid @RequestBody NoteCreateDTO dto) {
        return Result.success(noteService.create(dto));
    }

    /**
     * 修改笔记
     */
    @PutMapping("/{id}")
    public Result<NoteVO> update(
            @PathVariable Long id,
            @Valid @RequestBody NoteUpdateDTO dto
    ) {
        return Result.success(noteService.update(id, dto));
    }

    /**
     * 删除笔记（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noteService.delete(id);
        return Result.success(null);
    }
}
