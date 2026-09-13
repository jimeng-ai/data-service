package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorSchema;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 连接器自描述缓存 Mapper。
 */
@Mapper
public interface ConnectorSchemaMapper extends BaseMapper<ConnectorSchema> {

    /**
     * 物理删除某条连接的全部自描述行。刷新 = 先物理删、再整批重插。
     *
     * <p><b>为什么必须物理删除：</b>{@code BaseEntity} 全局 {@code @TableLogic}，逻辑删除只是
     * {@code UPDATE deleted=1}，死行仍然占着
     * {@code uk_connector_schema_object(tenant_id, connector_id, object_type, object_name)}，
     * 下一次刷新重插同名对象就撞键。仓库里这个坑踩过两次——{@code uk_connection_tenant_name} 与
     * {@code uk_agent_connection_*} 都是「不含 deleted 的唯一键 + 逻辑删除」，删了再建直接报错。
     * 把 deleted 塞进唯一键也不解（{@code uk_sys_enterprise_tenant} 那样第二次软删还是撞）。
     *
     * <p><b>⚠ 不要指望租户拦截器兜底。</b>定时刷新这类后台任务通常跑在
     * {@code TenantContext.runAsSystem(...)} 里，那时 {@code ignoreTable} 直接返回 true，
     * 这条 DELETE 上<b>不会有任何 tenant_id 条件</b>，传错一个 connectorId 就抹掉别的租户的缓存。
     * <b>service 层必须先按 connectorId 查出 connection、确认它属于当前租户，再调本方法。</b>
     */
    @Delete("DELETE FROM connector_schema WHERE connector_id = #{connectorId}")
    int physicalDeleteByConnector(@Param("connectorId") Long connectorId);
}
