package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 连接器使用留痕 Mapper。
 *
 * <p><b>只用 insert 和 select，不要加 update / delete。</b>审计是仅追加的：能改的审计等于没有审计。
 * 表只增不减，清理靠将来的归档任务（现在没有，先记着）。
 */
@Mapper
public interface ConnectorAuditMapper extends BaseMapper<ConnectorAudit> {
}
