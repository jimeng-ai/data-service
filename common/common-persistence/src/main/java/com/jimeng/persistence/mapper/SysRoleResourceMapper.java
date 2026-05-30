package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SysRoleResource;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysRoleResource Mapper —— 角色-资源授权。
 */
@Mapper
public interface SysRoleResourceMapper extends BaseMapper<SysRoleResource> {
}
