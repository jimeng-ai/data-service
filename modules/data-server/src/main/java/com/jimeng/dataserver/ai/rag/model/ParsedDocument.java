package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ParsedDocument {
    private String title;
    private String sourceType;
    private List<DocumentBlock> blocks;
}
