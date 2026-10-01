package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConnectorBusinessViewMapper extends BaseMapper<ConnectorBusinessView> {

    /**
     * 物理删除一行（对象或关系已经从快照、语义层里消失的 MODEL 行）。
     *
     * <p>为什么是物理删除：唯一键不含 deleted，{@code BaseMapper.delete} 在全局 {@code @TableLogic} 下是软删，
     * 死行会占着唯一键，同一个对象再出现时就写不进去。
     *
     * <p>显式写死租户与连接：{@code runAsSystem} 下租户拦截器整个不生效，只按 id 定位就能删到别的租户。
     * 调用方必须在真实租户上下文里、先确认连接属于当前租户。
     */
    @Delete("DELETE FROM connector_business_view WHERE tenant_id = #{tenantId} "
            + "AND connector_id = #{connectorId} AND id = #{id}")
    int physicalDeleteRow(@Param("tenantId") String tenantId,
                          @Param("connectorId") Long connectorId,
                          @Param("id") Long id);
}
