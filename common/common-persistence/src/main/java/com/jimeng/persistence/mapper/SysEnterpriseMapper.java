package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.SysEnterprise;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysEnterprise Mapper —— 企业（租户）。
 */
@Mapper
public interface SysEnterpriseMapper extends BaseMapper<SysEnterprise> {
}
