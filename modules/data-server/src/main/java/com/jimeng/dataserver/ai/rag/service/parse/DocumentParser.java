package com.jimeng.dataserver.ai.rag.service.parse;

import com.jimeng.dataserver.ai.rag.model.ParsedDocument;

import java.io.InputStream;

public interface DocumentParser {

    /** 是否能解析该 mime/filename */
    boolean supports(String mimeType, String filename);

    /** 解析为有序 block 列表 */
    ParsedDocument parse(InputStream stream, String filename) throws Exception;
}
