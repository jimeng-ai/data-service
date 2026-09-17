package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plans the next deterministic PENDING slice using the exact callback metadata renderer. */
@Component
@RequiredArgsConstructor
public class SemanticSlicePlanner {

    private static final int HARD_MAX_TABLES = 20;
    private static final int MIN_CHARS = 4_000;
    private static final int MAX_CHARS = 40_000;

    private static final Comparator<ConnectorSemanticGenerationTable> BY_PRIORITY =
            Comparator.comparing(ConnectorSemanticGenerationTable::getImportanceRank,
                            Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(ConnectorSemanticGenerationTable::getObjectName,
                            Comparator.nullsLast(String::compareTo))
                    .thenComparing(ConnectorSemanticGenerationTable::getId,
                            Comparator.nullsLast(Long::compareTo));

    private final ConnectorSemanticGenerationTableMapper tableMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final SemanticTableRenderer tableRenderer;

    public SemanticSlice plan(ConnectorSemanticGeneration generation, int sliceCap, int sliceMaxChars) {
        if (generation == null || generation.getId() == null || generation.getConnectorId() == null
                || generation.getTenantId() == null) {
            throw new IllegalArgumentException("persisted generation with tenant and connector is required");
        }
        int cap = Math.max(1, Math.min(HARD_MAX_TABLES, sliceCap));
        int maxChars = Math.max(MIN_CHARS, Math.min(MAX_CHARS, sliceMaxChars));
        List<ConnectorSemanticGenerationTable> pending = pendingRows(generation);
        int alreadyDispatched = generation.getCurrentSliceNo() == null
                ? 0 : Math.max(0, generation.getCurrentSliceNo());
        int estimated = alreadyDispatched + ceilDiv(pending.size(), cap);
        if (pending.isEmpty()) {
            return new SemanticSlice(List.of(), 0, estimated);
        }

        Map<TableKey, ConnectorSchema> schemas = snapshotRows(generation, pending);
        List<ConnectorSemanticGenerationTable> selected = new ArrayList<>();
        int chars = 0;
        for (ConnectorSemanticGenerationTable table : pending) {
            ConnectorSchema schema = schemas.get(TableKey.of(table));
            if (schema == null) {
                throw new IllegalStateException("pending table has no current snapshot row: " + table.getObjectName());
            }
            String rendered = tableRenderer.render(schema);
            if (rendered == null) {
                throw new IllegalStateException("renderer returned null for table: " + table.getObjectName());
            }
            int tableChars = rendered.length();
            if (!selected.isEmpty() && (selected.size() >= cap || (long) chars + tableChars > maxChars)) {
                break;
            }
            selected.add(table);
            chars = Math.addExact(chars, tableChars);
        }
        return new SemanticSlice(selected, chars, estimated);
    }

    private List<ConnectorSemanticGenerationTable> pendingRows(ConnectorSemanticGeneration generation) {
        List<ConnectorSemanticGenerationTable> rows = tableMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemanticGenerationTable>()
                        .eq(ConnectorSemanticGenerationTable::getTenantId, generation.getTenantId())
                        .eq(ConnectorSemanticGenerationTable::getGenerationId, generation.getId())
                        .eq(ConnectorSemanticGenerationTable::getStatus, "PENDING"));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> "PENDING".equals(row.getStatus()))
                .sorted(BY_PRIORITY)
                .toList();
    }

    private Map<TableKey, ConnectorSchema> snapshotRows(ConnectorSemanticGeneration generation,
                                                         List<ConnectorSemanticGenerationTable> pending) {
        List<String> names = pending.stream()
                .map(ConnectorSemanticGenerationTable::getObjectName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getTenantId, generation.getTenantId())
                .eq(ConnectorSchema::getConnectorId, generation.getConnectorId())
                .in(ConnectorSchema::getObjectName, names));
        Map<TableKey, ConnectorSchema> indexed = new HashMap<>();
        if (rows != null) {
            for (ConnectorSchema row : rows) {
                if (row != null) {
                    indexed.putIfAbsent(TableKey.of(row), row);
                }
            }
        }
        return indexed;
    }

    private static int ceilDiv(int count, int divisor) {
        return count == 0 ? 0 : 1 + (count - 1) / divisor;
    }

    private record TableKey(String type, String name) {
        static TableKey of(ConnectorSemanticGenerationTable table) {
            return new TableKey(table.getObjectType(), table.getObjectName());
        }

        static TableKey of(ConnectorSchema schema) {
            return new TableKey(schema.getObjectType(), schema.getObjectName());
        }
    }
}
