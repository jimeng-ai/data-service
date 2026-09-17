package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 单表提交结果。entries 刻意不回显接受项，避免宽表响应膨胀。 */
@Data
public class SubmitResultView {
    private String table;
    private String status;
    private int submitCount;
    private int maxSubmits;
    private Written written = new Written();
    private List<EntryOutcomeView> entries = new ArrayList<>();
    private Progress progress = new Progress();
    private List<String> sliceRemaining = new ArrayList<>();

    @Data
    public static class Written {
        private int object;
        private int fields;
        private int joins;
        private int ambiguities;
    }

    @Data
    public static class Progress {
        private int doneTables;
        private int totalTables;
    }
}
