package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IngestionMessage implements Serializable {
    private Long docId;
    private Long kbId;
    private String traceId;
}
