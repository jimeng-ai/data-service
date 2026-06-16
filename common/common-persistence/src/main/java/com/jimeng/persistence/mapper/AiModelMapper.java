package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.AiModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 可选模型目录 Mapper（平台级，不在租户白名单）。
 */
@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {
}
