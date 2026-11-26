package com.research.platform.modules.note.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.vo.NoteVO;
import com.research.platform.modules.note.dto.NoteCreateDTO;
import com.research.platform.modules.note.dto.NoteQueryDTO;
import com.research.platform.modules.note.dto.NoteUpdateDTO;
import com.research.platform.modules.note.entity.Note;

/**
 * 笔记服务
 */
public interface INoteService extends IService<Note> {

    /**
     * 分页查询当前用户的笔记
     */
    PageResult<NoteVO> page(NoteQueryDTO query);

    /**
     * 新建笔记
     */
    NoteVO create(NoteCreateDTO dto);

    /**
     * 修改笔记
     */
    NoteVO update(Long id, NoteUpdateDTO dto);

    /**
     * 删除笔记（逻辑删除）
     */
    void delete(Long id);
}
