package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.ai.connector.ConnectorAdminController;
import com.jimeng.dataserver.ai.connector.generation.ConnectorSemanticGenerationService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理台「删掉一行语义」。
 *
 * <h3>★ 这个类存在的首要理由：把「物理删除」钉死</h3>
 * {@code uk_connector_semantic (tenant_id, connector_id, scope, object_name, field_name, term)}
 * <b>不含 deleted</b>，而 {@code BaseEntity} 是全局 {@code @TableLogic}。任何人顺手把这里改成
 * {@code BaseMapper.deleteById} 都能编过、跑过、界面上那行也确实消失了——
 * 代价要到很久以后才显形，而且<b>伪装成另一回事</b>：
 * <ol>
 *   <li>软删死行永久占着那个键位；</li>
 *   <li>{@code defineMetric} 的 selectOne 被 {@code @TableLogic} 加上 {@code deleted=0}，读不到死行；</li>
 *   <li>于是走插入 → 撞唯一键 → 三轮 attempt 完全相同 → 抛「口径『X』正在被同时修改」。</li>
 * </ol>
 * 「这条断言<b>永远</b>写不进去」被说成「并发冲突」，顺着那句话排查的人永远查不到真因。
 * 所以这里不满足于「行没了」，而是直接断言发出去的 SQL 是 {@code DELETE}、且 mapper 上没有第二次调用。
 */
class ConnectorSemanticRowDeleteTest {

    private static final Long CONNECTOR_ID = 41L;
    private static final Long ROW_ID = 900L;
    private static final String TENANT = "tenant-a";

    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection());
    }

    // ------------------------------------------------------------------ 物理删除

    @Test
    @DisplayName("★ 走的是物理 DELETE：SQL 以 DELETE 开头、不碰 deleted 列，mapper 上没有第二次调用")
    void 必须物理删除而不是软删() throws Exception {
        Delete sql = ConnectorSemanticMapper.class
                .getMethod("physicalDeleteRow", String.class, Long.class, Long.class)
                .getAnnotation(Delete.class);

        assertEquals(1, sql.value().length, "只应该有一条语句");
        String text = sql.value()[0].trim();
        assertTrue(text.toUpperCase().startsWith("DELETE FROM CONNECTOR_SEMANTIC"),
                "必须是物理 DELETE。软删（UPDATE deleted=1）留下的死行会永久占住 uk_connector_semantic，"
                        + "此后同一条断言再也写不进去，而报出来的是「正在被同时修改」这种假并发错。实际 SQL：" + text);
        assertFalse(text.toLowerCase().contains("deleted"),
                "不该出现 deleted：出现它意味着有人试图把逻辑删除塞回来。实际 SQL：" + text);

        when(semanticMapper.physicalDeleteRow(TENANT, CONNECTOR_ID, ROW_ID)).thenReturn(1);

        assertEquals(1, service.deleteRow(CONNECTOR_ID, ROW_ID));

        verify(semanticMapper).physicalDeleteRow(TENANT, CONNECTOR_ID, ROW_ID);
        // mapper 上只该有这一次调用：deleteById / update（软删的两种写法）一次都不能发生。
        verifyNoMoreInteractions(semanticMapper);
    }

    @Test
    @DisplayName("SQL 的三个参数都传了：租户、连接、行 —— 少一个就能删到别人的行")
    void 三个参数都传() {
        when(semanticMapper.physicalDeleteRow(anyString(), any(), any())).thenReturn(1);

        service.deleteRow(CONNECTOR_ID, ROW_ID);

        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> connector = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> row = ArgumentCaptor.forClass(Long.class);
        verify(semanticMapper).physicalDeleteRow(tenant.capture(), connector.capture(), row.capture());

        // tenantId 取自 requireOwned 查出来的连接行，而不是入参：这条 SQL 跑在 runAsSystem 下时
        // 租户拦截器不会追加任何条件（ignoreTable 直接 true），三列里少写一列就是一次跨行删除。
        assertEquals(TENANT, tenant.getValue(), "tenant_id 必须显式传，不能指望租户拦截器兜底");
        assertEquals(CONNECTOR_ID, connector.getValue(),
                "connector_id 必须显式传：只按 rowId 定位的话，拿 A 连接的路径 id 配 B 连接的 rowId 就能删到别的连接");
        assertEquals(ROW_ID, row.getValue());
    }

    // ------------------------------------------------------------------ 边界

    @Test
    @DisplayName("连接对当前租户不可见时抛 NOT_FOUND，并且一条 DELETE 都不发")
    void 租户不可见抛NOT_FOUND() {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.deleteRow(CONNECTOR_ID, ROW_ID));

        assertEquals(ExceptionCode.NOT_FOUND.getResultCode(), error.getRespCode());
        // ★ 归属校验必须在 SQL 之前：先删再校验等于已经删掉了才发现不该删。
        verifyNoInteractions(semanticMapper);
    }

    @Test
    @DisplayName("删 0 行返回 0，不抛 —— 重复点击、并发里被别人先删掉都是正常结局")
    void 删零行不抛() {
        when(semanticMapper.physicalDeleteRow(TENANT, CONNECTOR_ID, ROW_ID)).thenReturn(0);

        assertEquals(0, service.deleteRow(CONNECTOR_ID, ROW_ID),
                "幂等：把「这行本来就不在」做成错误，会让重试和并发都变成需要解释的异常");
    }

    @Test
    @DisplayName("rowId 为空按参数错误挡下，不发 SQL")
    void 空rowId挡下() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.deleteRow(CONNECTOR_ID, null));

        assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), error.getRespCode());
        verifyNoInteractions(semanticMapper);
    }

    // ------------------------------------------------------------------ 控制器：审计摘要

    /**
     * ★ 审计摘要只放定位信息。
     *
     * <p>{@code connector_audit.error_detail} 是会被展示给客户看的那一列，而第 3 档采到的
     * <b>客户库真实取值</b>恰好就嵌在 {@code gloss}（care_reason 里带着 {@code = '取值'}）和
     * {@code detail_json}（判别值、取值域样本）里面。把它们抄进摘要，等于把刚从语义层删掉的真实取值
     * 原样换个地方再存一份，而且存进了一张保留期更长、更公开的表。
     */
    @Test
    @DisplayName("★ 删除留痕只放 scope/对象/字段/词条/来源，绝不放 gloss 与 detail")
    void 审计摘要不含gloss与detail() {
        ConnectorSemanticService semanticService = mock(ConnectorSemanticService.class);
        ConnectorAuditService auditService = mock(ConnectorAuditService.class);
        ConnectorAdminController controller = controller(semanticService, auditService);

        ConnectorSemantic row = new ConnectorSemantic();
        row.setId(ROW_ID);
        row.setScope(ConnectorSemanticService.SCOPE_JOIN);
        row.setObjectName("t_ord_mst");
        row.setFieldName("owner_id");
        row.setTerm("");
        row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        row.setGloss("多态外键：owner_type = 'VIP_CUSTOMER' 时指向 t_customer");
        row.setDetailJson("{\"discriminator_value\":\"VIP_CUSTOMER\"}");

        when(semanticService.requireOwned(CONNECTOR_ID)).thenReturn(connection());
        when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(row));
        when(semanticService.deleteRow(CONNECTOR_ID, ROW_ID)).thenReturn(1);

        Map<String, Object> out = controller.deleteSemanticRow(CONNECTOR_ID, ROW_ID);

        assertEquals(Boolean.TRUE, out.get("deleted"));
        assertEquals(1, out.get("removed"));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAdminAction(eq(CONNECTOR_ID), eq(TENANT), eq("shop-db"),
                eq(ConnectorAuditService.OP_SEMANTIC_ROW_DELETE), summary.capture(), eq(true), eq(null));

        String text = summary.getValue();
        assertTrue(text.contains("JOIN") && text.contains("t_ord_mst") && text.contains("owner_id")
                        && text.contains(ConnectorSemanticService.SOURCE_INFERRED),
                "摘要要能定位到被删的是哪一条，否则留痕等于没留。实际：" + text);
        assertFalse(text.contains("VIP_CUSTOMER"),
                "gloss / detail 里嵌着客户库的真实取值，绝不能进审计。实际：" + text);
        assertFalse(text.contains("多态外键"), "gloss 原文不进审计。实际：" + text);
    }

    @Test
    @DisplayName("删 0 行不记审计：没发生的事不该在审计表里留下一行")
    void 删零行不记审计() {
        ConnectorSemanticService semanticService = mock(ConnectorSemanticService.class);
        ConnectorAuditService auditService = mock(ConnectorAuditService.class);
        ConnectorAdminController controller = controller(semanticService, auditService);

        when(semanticService.requireOwned(CONNECTOR_ID)).thenReturn(connection());
        when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
        when(semanticService.deleteRow(CONNECTOR_ID, ROW_ID)).thenReturn(0);

        Map<String, Object> out = controller.deleteSemanticRow(CONNECTOR_ID, ROW_ID);

        assertEquals(Boolean.FALSE, out.get("deleted"));
        assertEquals(0, out.get("removed"));
        verify(auditService, never()).recordAdminAction(any(), any(), any(), anyString(),
                anyString(), anyBoolean(), any());
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * ★ 9 参构造器逐个传 mock，与 {@code ConnectorAdminControllerGenerationTest} 同形：
     * 这一串位置参数就是「构造器不准再长」这条约束的钉子，加依赖会在这里先炸。
     */
    private static ConnectorAdminController controller(ConnectorSemanticService semanticService,
                                                       ConnectorAuditService auditService) {
        return new ConnectorAdminController(mock(ConnectorService.class), auditService,
                mock(ConnectorSchemaService.class), semanticService,
                mock(ConnectorSemanticDeriveService.class), mock(ConnectorSemanticGenerationService.class),
                mock(PendingWriteService.class), mock(GrantScriptService.class), mock(SuperAdminGuard.class));
    }

    private static Connection connection() {
        Connection c = new Connection();
        c.setId(CONNECTOR_ID);
        c.setTenantId(TENANT);
        c.setName("shop-db");
        return c;
    }
}
