package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 语义层 Mapper。
 */
@Mapper
public interface ConnectorSemanticMapper extends BaseMapper<ConnectorSemantic> {

    /**
     * 物理删除某条连接下<b>机器推断</b>的语义行。重新推导 = 先物理删 INFERRED、再整批重插。
     *
     * <p><b>为什么必须物理删除：</b>{@code BaseEntity} 全局 {@code @TableLogic}，逻辑删除只是
     * {@code UPDATE deleted=1}，死行仍然占着 {@code uk_connector_semantic}，
     * 下一次推导重插同一条断言就撞键。这个坑在本仓库已经咬过三次
     * （{@code uk_connection_tenant_name} / {@code uk_agent_connection_*} / {@code uk_sys_enterprise_tenant}），
     * 把 deleted 塞进唯一键也不解——第二次软删照样撞。
     *
     * <p><b>为什么只删 INFERRED：</b>{@code source='HUMAN'} 的行是人在对话里答出来的口径，
     * <b>永不被推断覆盖</b>。周期性重新推断会覆盖人工确认过的口径，这是设计里明确不做的事；
     * 这条 SQL 的 {@code source = 'INFERRED'} 条件就是那条纪律的物理保证。
     * {@code IMPORTED}（直接采信客户库注释）同样不删——它的依据是一手事实，重推不会更好。
     *
     * <p><b>⚠ 不要指望租户拦截器兜底。</b>推导跑在后台线程，通常在
     * {@code TenantContext.runAsSystem(...)} 里，那时 {@code ignoreTable} 直接返回 true，
     * 这条 DELETE 上<b>不会有任何 tenant_id 条件</b>，传错一个 connectorId 就抹掉别的租户的语义层。
     * <b>service 层必须先按 connectorId 查出 connection、确认它属于当前租户，再调本方法。</b>
     */
    @Delete("DELETE FROM connector_semantic WHERE connector_id = #{connectorId} AND source = 'INFERRED'")
    int physicalDeleteInferred(@Param("connectorId") Long connectorId);
}
