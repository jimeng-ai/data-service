package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SysAdmin;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysAdmin Mapper —— 管理后台账户。
 */
@Mapper
public interface SysAdminMapper extends BaseMapper<SysAdmin> {
}
