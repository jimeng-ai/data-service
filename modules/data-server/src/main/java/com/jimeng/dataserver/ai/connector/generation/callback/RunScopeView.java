package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** {@code POST /run-scope} 的出网 DTO。 */
@Data
public class RunScopeView {
    private String connectorName;
    private String connectorKind;
    private String mode;
    private Integer sliceNo;
    private Integer sliceCountEstimate;
    private Progress progress;
    private List<TableItem> tables = new ArrayList<>();
    private List<String> answeredTerms = new ArrayList<>();
    private List<String> openTerms = new ArrayList<>();
    private Limits limits;
    private List<Rule> rules = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

    @Data
    public static class Progress {
        private Integer doneTables;
        private Integer totalTables;
        private Integer gaveUpTables;
    }

    @Data
    public static class TableItem {
        private String name;
        private Integer importanceRank;
        private String status;
        private Integer submitCount;
        private String lastRejectReason;
        private Integer columnCount;
        private boolean described;
    }

    @Data
    public static class Limits {
        private Integer metadataMaxTables;
        private Integer listMaxLimit;
        private Integer submitMaxFields;
        private Integer submitMaxJoins;
        private Integer submitMaxAmbiguities;
        private Integer maxSubmitsPerTable;
    }

    @Data
    public static class Rule {
        private String code;
        private String summary;
    }
}
