package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** {@code POST /tables} 的响应 data。 */
@Data
public class TableListView {
    private Integer total;
    private Integer offset;
    private Integer limit;
    private Integer nextOffset;
    private String ordering;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private String name;
        private String type;
        private String comment;
        private Integer columnCount;
        private boolean described;
        private Integer importanceRank;
        private boolean inCurrentSlice;
        private String batchStatus;
    }
}
