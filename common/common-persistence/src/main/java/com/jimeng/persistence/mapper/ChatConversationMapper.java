package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
