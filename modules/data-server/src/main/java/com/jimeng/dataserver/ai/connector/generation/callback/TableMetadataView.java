package com.jimeng.dataserver.ai.connector.generation.callback;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** {@code POST /table-metadata} 的响应 data。 */
@Data
public class TableMetadataView {
    private List<TableItem> tables = new ArrayList<>();
    private List<NotFound> notFound = new ArrayList<>();
    private List<String> deferred = new ArrayList<>();

    @Data
    public static class TableItem {
        private String name;
        private String type;
        private boolean described;
        private boolean inCurrentSlice;
        private String batchStatus;
        private Integer columnCount;
        private String structureStamp;
        private boolean uniqueKeysKnown;
        private List<HumanKey> humanKeys = new ArrayList<>();
        private boolean columnsTruncated;
        private String text;
    }

    /** 表名由父对象给出，所以人工键只需 scope + field；OBJECT 的 field 为 null。 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HumanKey {
        private String scope;
        private String field;
    }

    @Data
    public static class NotFound {
        private String name;
        private List<String> suggestions = new ArrayList<>();
    }
}
