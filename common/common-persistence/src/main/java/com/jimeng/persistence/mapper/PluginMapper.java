package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.Plugin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Plugin Mapper
 */
@Mapper
public interface PluginMapper extends BaseMapper<Plugin> {

    /**
     * 释放【被软删行】占用的同代号唯一键 uk_plugin_tenant_code(tenant_id, code)：
     * 逻辑删除(deleted=1)后死行仍占着 code，不释放就无法重建删过的插件代号。
     * 原生 SQL 绕过逻辑删除自动过滤以命中 deleted=1；租户条件由拦截器自动注入。
     */
    @Update("UPDATE plugin SET code = CONCAT('_deleted_', id) WHERE code = #{code} AND deleted = 1")
    int releaseDeletedCode(@Param("code") String code);
}
