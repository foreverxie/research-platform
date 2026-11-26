package com.research.platform.modules.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.platform.modules.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
