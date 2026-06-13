package com.jimeng.dataserver.ai.rag.service.parse;

import com.jimeng.dataserver.ai.rag.model.ParsedDocument;

import java.io.InputStream;

public interface DocumentParser {

    /** 是否能解析该 mime/filename */
    boolean supports(String mimeType, String filename);

    /** 解析为有序 block 列表 */
    ParsedDocument parse(InputStream stream, String filename) throws Exception;

    /**
     * 带选项的解析。{@code rowPerChunk=true} 时，表格类解析器（xlsx/csv）把每个数据行产出成
     * 独立的 TABLE block，使其在分块阶段「一行一 chunk」（FAQ 表场景）；非表格解析器忽略该标志。
     * 默认委托回两参版本，故仅 xlsx/csv 解析器需要 override。
     */
    default ParsedDocument parse(InputStream stream, String filename, boolean rowPerChunk) throws Exception {
        return parse(stream, filename);
    }
}
