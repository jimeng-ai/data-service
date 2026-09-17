package com.jimeng.dataserver.ai.connector.generation.callback;

import lombok.Data;

/** 提交中一条没有被接受的条目；只返回 rejected / dropped。 */
@Data
public class EntryOutcomeView {
    private String path;
    private String key;
    private String status;
    private String code;
    private String message;
}
