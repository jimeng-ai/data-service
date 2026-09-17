package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;

import java.util.List;

/** A bounded, ordered slice plus its current total-slice estimate. */
public record SemanticSlice(
        List<ConnectorSemanticGenerationTable> tables,
        int renderedChars,
        int estimatedTotalSlices
) {
    public SemanticSlice {
        tables = tables == null ? List.of() : List.copyOf(tables);
        if (renderedChars < 0 || estimatedTotalSlices < 0) {
            throw new IllegalArgumentException("slice counts must not be negative");
        }
    }

    public int size() {
        return tables.size();
    }

    public boolean isEmpty() {
        return tables.isEmpty();
    }
}
