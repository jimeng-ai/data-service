package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysUserRole Mapper —— 成员-角色绑定。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
