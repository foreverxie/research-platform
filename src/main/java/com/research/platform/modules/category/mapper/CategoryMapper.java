package com.research.platform.modules.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.platform.modules.category.entity.Category;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
