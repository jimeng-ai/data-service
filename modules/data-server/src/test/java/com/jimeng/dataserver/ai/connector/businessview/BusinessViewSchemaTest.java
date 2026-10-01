package com.jimeng.dataserver.ai.connector.businessview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据星图 v3 的两张新表：实体、DDL、租户白名单三处必须对得上。
 *
 * <p>这个项目没有 Flyway，DDL 是手工执行的：实体多映射一列、DDL 里没有，应用照常启动，碰这张表的每个查询都
 * {@code Unknown column}；表没进租户白名单，别的租户的业务名称就会漏出来。两种错都不在编译期报。
 */
class BusinessViewSchemaTest {

    private static final String DDL = "db/migration/V20261001__data_graph_business_view.sql";

    private static String ddl() throws Exception {
        try (InputStream in = BusinessViewSchemaTest.class.getClassLoader().getResourceAsStream(DDL)) {
            assertNotNull(in, DDL + " 不在 classpath 上");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 某张表的 CREATE TABLE 语句正文。 */
    private static String createTable(String ddl, String table) {
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + table + "` \\((.*?)\\) ENGINE", Pattern.DOTALL)
                .matcher(ddl);
        assertTrue(m.find(), "DDL 里没有 " + table);
        return m.group(1);
    }

    private static List<String> columnsOf(Class<?> entity) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfo info = TableInfoHelper.initTableInfo(assistant, entity);
        List<String> out = new ArrayList<>();
        out.add(info.getKeyColumn());
        for (TableFieldInfo f : info.getFieldList()) {
            out.add(f.getColumn());
        }
        return out;
    }

    @Test
    @DisplayName("★ 两张新表都在租户白名单里：少一张，别的租户的业务名称与运行状态就会漏出来")
    void 租户隔离() {
        JimengTenantLineHandler handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
        assertFalse(handler.ignoreTable("connector_business_view"));
        assertFalse(handler.ignoreTable("connector_enrichment_state"));
    }

    @Test
    @DisplayName("实体映射的每一列 DDL 里都有")
    void 实体与DDL一致() throws Exception {
        String ddl = ddl();
        for (Class<?> entity : List.of(ConnectorBusinessView.class, ConnectorEnrichmentState.class)) {
            String table = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    entity).getTableName();
            String body = createTable(ddl, table);
            for (String column : columnsOf(entity)) {
                assertTrue(body.contains("`" + column + "`"), table + " 的 DDL 缺列 " + column);
            }
        }
    }

    /** 唯一键不含 deleted 的前提是「不产生软删死行」：MODEL 行物理删除，HUMAN 行只原地更新。 */
    @Test
    @DisplayName("唯一键刻意不含 deleted")
    void 唯一键不含deleted() throws Exception {
        String ddl = ddl();
        Matcher uk = Pattern.compile("UNIQUE KEY `(\\w+)` \\(([^)]*)\\)").matcher(ddl);
        int n = 0;
        while (uk.find()) {
            n++;
            assertFalse(uk.group(2).contains("deleted"), uk.group(1));
            assertTrue(uk.group(2).startsWith("`tenant_id`, `connector_id`"), "唯一键以租户、连接打头：" + uk.group(1));
        }
        assertEquals(2, n);
    }
}
