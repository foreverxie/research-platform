package com.research.platform.modules.annotation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.platform.modules.annotation.entity.Annotation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnnotationMapper extends BaseMapper<Annotation> {
}
