package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
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

    // ================================================================ 语义层生成 agent
    //
    // ★ 下面三条与上面那条的差别：WHERE 里【显式写 tenant_id = #{tenantId}】，不靠租户拦截器兜底。
    //   两个原因——
    //   (1) 在 runAsSystem 下拦截器根本不加租户条件（ignoreTable 直接 true），传错一个 connectorId 就删到别的租户；
    //   (2) INSERT ... SELECT 的 SELECT 部分拦截器从不改写：列清单里已有 tenant_id 时 processInsert 直接 return
    //       （MyBatis-Plus 3.5.5，已反编译 TenantLineInnerInterceptor 核实）。
    //   tenantId 取自 requireOwned 返回的连接行；调用方必须先 requireOwned、并运行在真实租户上下文里。
    //   非系统模式下拦截器可能再追加一个相同条件，重复无害。
    //   physicalDeleteInferredForPromote 与 moveStagedIn 在 dev-mysql 上用合成数据做过回滚式演练
    //   （设计文档 13.3 的 D2：核对结果恰好 6 行）。

    /**
     * 按表替换（DIRECT）：只删这张表上机器推断的 OBJECT / FIELD / JOIN。不碰 CAVEAT / METRIC，不碰 HUMAN / IMPORTED。
     *
     * <p>JOIN 归左表（{@code object_name} 是左表），所以「删本表 INFERRED 再插」不会越界改到别的表的行。
     * CAVEAT 属于整条连接、按 term 合并，按表替换时从不删它——一张表重交就把别的表也提过的口径问题删掉，是错的。
     */
    @Delete("DELETE FROM connector_semantic WHERE tenant_id = #{tenantId} AND connector_id = #{connectorId} "
            + "AND source = 'INFERRED' AND scope IN ('OBJECT','FIELD','JOIN') AND object_name = #{objectName}")
    int physicalDeleteInferredOfObject(@Param("tenantId") String tenantId,
                                       @Param("connectorId") Long connectorId,
                                       @Param("objectName") String objectName);

    /**
     * STAGED 收尾：删该连接的机器推断行，但保留本批 SKIPPED 表名下的 OBJECT / FIELD / JOIN 旧行。
     * GAVE_UP 表只在 max-gave-up-ratio 被调大后才会带到收尾（默认 0 时收尾前已没有放弃表，见设计文档 6.10），届时同样保留
     * （新一轮没写出来的表，保留上一版说明比变成空白好）。CAVEAT 一律删掉，由暂存行整体替换。
     *
     * <p>子查询读的是另一张表，不触发 MySQL 的 ERROR 1093（DELETE 的子查询不能引用被删的那张表）。
     */
    @Delete("DELETE FROM connector_semantic WHERE tenant_id = #{tenantId} AND connector_id = #{connectorId} "
            + "AND source = 'INFERRED' AND NOT (scope IN ('OBJECT','FIELD','JOIN') AND object_name IN ("
            + "  SELECT g.object_name FROM connector_semantic_generation_table g "
            + "  WHERE g.tenant_id = #{tenantId} AND g.generation_id = #{generationId} AND g.status IN ('GAVE_UP','SKIPPED')))")
    int physicalDeleteInferredForPromote(@Param("tenantId") String tenantId, @Param("connectorId") Long connectorId,
                                         @Param("generationId") Long generationId);

    /**
     * STAGED 收尾：把暂存行搬进主表。两个 LEFT JOIN 反连接：
     * <ul>
     *   <li>{@code t}：与已有行（此刻只剩 HUMAN / IMPORTED，以及保留下来的 SKIPPED 表、调大比例后的 GAVE_UP 表旧行）
     *       撞唯一键的跳过；</li>
     *   <li>{@code m}：已有人答过的口径（METRIC + CONFIRMED）同名的 CAVEAT 跳过，
     *       防止暂存期间有人答了口径，搬入后「问题」和「答案」同时注入。</li>
     * </ul>
     * 目标表只出现在 FROM 子句里，这是 MySQL 允许的 INSERT ... SELECT 形态（已在 dev-mysql 上实跑）。
     *
     * <p><b>不能用 INSERT IGNORE</b>：严格模式下它会把「超长」等错误降级成警告并静默截断，违背「键列只丢不截」。
     * 暂存行的 id 原样作为主表 id：SQL 侧拿不到新的雪花 id，暂存行的 id 全局唯一且在同一事务里删除。
     *
     * @return 实际插入的行数；跳过数 = 暂存行数 - 返回值，由调用方写日志
     */
    @Insert("INSERT INTO connector_semantic (id, tenant_id, connector_id, scope, object_name, field_name, term, gloss, "
            + "detail_json, source, evidence, confidence, verified, status, anchor_kind, anchor_hash, deleted, create_time, update_time) "
            + "SELECT s.id, s.tenant_id, s.connector_id, s.scope, s.object_name, s.field_name, s.term, s.gloss, "
            + "s.detail_json, 'INFERRED', s.evidence, s.confidence, s.verified, s.status, s.anchor_kind, s.anchor_hash, 0, NOW(), NOW() "
            + "FROM connector_semantic_staged s "
            + "LEFT JOIN connector_semantic t ON t.tenant_id = s.tenant_id AND t.connector_id = s.connector_id "
            + "  AND t.scope = s.scope AND t.object_name = s.object_name AND t.field_name = s.field_name AND t.term = s.term "
            + "LEFT JOIN connector_semantic m ON s.scope = 'CAVEAT' AND m.tenant_id = s.tenant_id AND m.connector_id = s.connector_id "
            + "  AND m.scope = 'METRIC' AND m.status = 'CONFIRMED' AND m.object_name = '' AND m.field_name = '' AND m.term = s.term "
            + "WHERE s.tenant_id = #{tenantId} AND s.connector_id = #{connectorId} AND s.generation_id = #{generationId} "
            + "AND t.id IS NULL AND m.id IS NULL")
    int moveStagedIn(@Param("tenantId") String tenantId, @Param("connectorId") Long connectorId,
                     @Param("generationId") Long generationId);
}
