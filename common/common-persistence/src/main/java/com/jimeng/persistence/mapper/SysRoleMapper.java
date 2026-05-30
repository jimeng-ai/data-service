package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysRole Mapper —— 企业自定义角色。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
