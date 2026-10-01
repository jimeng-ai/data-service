package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.objectView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.relationView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 性能验收（设计文档 §12，v2 审查 #8）：量的是服务层的 {@code /systems/{id}}——查询结果合并业务视图、投影、再序列化成 JSON，
 * 而不只是投影本身。一条连接 200 张表（每张 30 列）、200 条 JOIN 行、400 行业务视图：预热后连续 50 次，P95 < 300 ms。
 * 数据库往返是按 connector_id 的四条查询，不在这里量。
 */
class DataGraphPerformanceTest {

    @Test
    @DisplayName("200 张表、200 条关系、400 行业务视图：服务层 + 序列化，P95 < 300 ms")
    void 两百张表() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Connection.class, ConnectorSchema.class, ConnectorSemantic.class,
                ConnectorBusinessView.class, ConnectorEnrichmentState.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
        List<ConnectorSchema> rows = new ArrayList<>();
        List<ConnectorSemantic> joins = new ArrayList<>();
        List<ConnectorBusinessView> views = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String[] columns = new String[30];
            columns[0] = "id|主键";
            columns[1] = "parent_id|上级";
            for (int c = 2; c < columns.length; c++) {
                columns[c] = "col_" + c + "|第 " + c + " 列";
            }
            rows.add(table(name(i), "表" + i, i, List.of(pk("id")), columns));
            views.add(objectView(name(i), "对象" + i, "一条记录是对象" + i + "。", "领域" + (i % 8)));
            if (i > 0) {
                joins.add(join(name(i), "parent_id", name((i - 1) / 4), "id"));
                views.add(relationView(name(i), "parent_id", "上级对象"));
            }
        }
        joins.add(join(name(150), "col_2", name(10), "id", "CONFIRMED", Map.of()));

        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        ConnectorSchemaMapper schemaMapper = mock(ConnectorSchemaMapper.class);
        ConnectorSemanticMapper semanticMapper = mock(ConnectorSemanticMapper.class);
        ConnectorBusinessViewMapper viewMapper = mock(ConnectorBusinessViewMapper.class);
        ConnectorEnrichmentStateMapper stateMapper = mock(ConnectorEnrichmentStateMapper.class);
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(rows);
        when(semanticMapper.selectList(any())).thenReturn(joins);
        when(viewMapper.selectList(any())).thenReturn(views);
        DataGraphService service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper, viewMapper,
                stateMapper, new ConnectorProperties());

        for (int i = 0; i < 5; i++) {
            CommonUtil.getObjectMapper().writeValueAsString(service.system("7"));
        }
        long[] nanos = new long[50];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            SystemGraph g = service.system("7");
            CommonUtil.getObjectMapper().writeValueAsString(g);
            nanos[i] = System.nanoTime() - start;
            assertEquals(200, g.getRelations().size());
            assertEquals("对象1", g.getTables().get(1).getDisplayName());
        }
        Arrays.sort(nanos);
        long p95Millis = nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000;
        assertTrue(p95Millis < 300, "P95 = " + p95Millis + " ms");
        System.out.println("数据星图 /systems/{id} 200 表（服务层 + 序列化）P95 = " + p95Millis + " ms");
    }

    private static String name(int i) {
        return String.format("t_%03d", i);
    }
}
