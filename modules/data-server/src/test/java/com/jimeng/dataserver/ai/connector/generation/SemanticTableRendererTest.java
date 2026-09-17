package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.persistence.entity.ConnectorSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTableRendererTest {

    @Test
    @DisplayName("MySQL 最大列数级别的宽表截断在线性预算内完成")
    void 超宽表截断不反复重建列前缀() {
        ConnectorSchema row = schemaWithFields(1_017, 1_000);
        SemanticTableRenderer renderer = new SemanticTableRenderer();

        SemanticTableRenderer.RenderedTable rendered = assertTimeoutPreemptively(
                Duration.ofMillis(1_500), () -> renderer.renderWithin(row, 16_000));

        assertTrue(rendered.columnsTruncated());
        assertEquals(1_017, rendered.totalColumns());
        assertTrue(rendered.renderedColumns() > 0);
        assertTrue(rendered.renderedColumns() < rendered.totalColumns());
        assertTrue(rendered.text().length() <= 16_000);
        assertTrue(rendered.text().endsWith("未列出的列不要写说明\n"));
    }

    @Test
    @DisplayName("预算只能保留零列时不误删表注释里的同名提示")
    void 零列截断保留表注释原文() {
        ConnectorSchema row = schemaWithFields(1, 1_000);
        row.setObjectComment("业务原注释（本表字段未取到，不要为它写字段或关系）仍须保留");

        SemanticTableRenderer.RenderedTable rendered =
                new SemanticTableRenderer().renderWithin(row, 300);

        assertTrue(rendered.columnsTruncated());
        assertEquals(0, rendered.renderedColumns());
        assertEquals("## max_width_table [TABLE] 业务原注释（本表字段未取到，不要为它写字段或关系）仍须保留\n"
                + "唯一键（主键在前）：PRIMARY(column_0)\n"
                + "本表共 1 列，只列出前 0 列；未列出的列不要写说明\n", rendered.text());
    }

    private static ConnectorSchema schemaWithFields(int count, int commentChars) {
        StringBuilder fields = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                fields.append(',');
            }
            fields.append("{\"name\":\"column_").append(i)
                    .append("\",\"type\":\"varchar(255)\",\"nullable\":true,\"comment\":\"")
                    .append("说".repeat(commentChars))
                    .append("\",\"extra\":\"\"}");
        }
        fields.append(']');

        ConnectorSchema row = new ConnectorSchema();
        row.setObjectName("max_width_table");
        row.setObjectType("TABLE");
        row.setObjectComment("最大宽表");
        row.setDetailJson("{\"fields\":" + fields
                + ",\"extra\":{\"unique_keys\":[{\"name\":\"PRIMARY\",\"primary\":true,"
                + "\"columns\":[\"column_0\"]}]}}");
        return row;
    }
}
