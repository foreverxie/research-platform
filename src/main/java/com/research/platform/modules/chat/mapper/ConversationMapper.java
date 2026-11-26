package com.research.platform.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.platform.modules.chat.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
