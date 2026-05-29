package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
