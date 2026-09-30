package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计文档 §4 的性能验收：一条连接 200 张表、200 条 JOIN 行。投影是接口里唯一的重活（另外只有两条按 connector_id
 * 的查询），这里单测它：预热后连续 50 次，P95 < 300 ms。
 */
class DataGraphProjectorPerformanceTest {

    @Test
    @DisplayName("200 张表（每张 30 列）、200 条 JOIN 行：预热后连续 50 次，P95 < 300 ms")
    void 两百张表() {
        List<ConnectorSchema> rows = new ArrayList<>();
        List<ConnectorSemantic> joins = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String[] columns = new String[30];
            columns[0] = "id|主键";
            columns[1] = "parent_id|上级";
            for (int c = 2; c < columns.length; c++) {
                columns[c] = "col_" + c + "|第 " + c + " 列";
            }
            rows.add(table(name(i), "表" + i, i, List.of(pk("id")), columns));
            if (i > 0) {
                joins.add(join(name(i), "parent_id", name((i - 1) / 4), "id"));
            }
        }
        joins.add(join(name(150), "col_2", name(10), "id", "CONFIRMED", Map.of()));

        for (int i = 0; i < 5; i++) {
            DataGraphProjector.system(connection(), rows, joins);
        }
        long[] nanos = new long[50];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            SystemGraph g = DataGraphProjector.system(connection(), rows, joins);
            nanos[i] = System.nanoTime() - start;
            assertEquals(200, g.getRelations().size());
        }
        Arrays.sort(nanos);
        long p95Millis = nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000;
        assertTrue(p95Millis < 300, "P95 = " + p95Millis + " ms");
        System.out.println("数据星图投影 200 表 P95 = " + p95Millis + " ms");
    }

    private static String name(int i) {
        return String.format("t_%03d", i);
    }
}
