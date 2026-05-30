package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysUser Mapper —— 企业账号（超管/成员）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
