package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.Plugin;
import org.apache.ibatis.annotations.Mapper;

/**
 * Plugin Mapper
 */
@Mapper
public interface PluginMapper extends BaseMapper<Plugin> {
}
