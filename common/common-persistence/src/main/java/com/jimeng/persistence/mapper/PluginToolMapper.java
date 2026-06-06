package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.PluginTool;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * PluginTool Mapper
 */
@Mapper
public interface PluginToolMapper extends BaseMapper<PluginTool> {

    /**
     * 释放【被软删行】占用的同工具名唯一键 uk_plugin_tool_tenant_name(tenant_id, name)：
     * 逻辑删除(deleted=1)后死行仍占着 name，不释放就无法重建删过的工具名。
     * 原生 SQL 绕过逻辑删除自动过滤以命中 deleted=1；租户条件由拦截器自动注入。
     */
    @Update("UPDATE plugin_tool SET name = CONCAT('_deleted_', id) WHERE name = #{name} AND deleted = 1")
    int releaseDeletedName(@Param("name") String name);
}
