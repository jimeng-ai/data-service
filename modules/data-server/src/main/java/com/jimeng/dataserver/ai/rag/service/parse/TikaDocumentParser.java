package com.jimeng.dataserver.ai.rag.service.parse;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 兜底 parser：用 Tika AutoDetectParser 提纯文本，按段落（双换行）拆 block。
 * 不识别 heading / page；只为非 PDF / Word / Markdown 的格式（HTML、TXT、RTF 等）兜底。
 *
 * <p>{@code @Order} 设为最低，确保 Registry 先匹配专用 parser。
 */
@Slf4j
@Component
@Order
public class TikaDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String mimeType, String filename) {
        // 兜底：除已被专用 parser 覆盖的格式外，其他都尝试
        return true;
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        parser.parse(stream, handler, metadata, new ParseContext());

        String fullText = handler.toString();
        if (StrUtil.isBlank(fullText)) {
            return ParsedDocument.builder()
                    .title(filename)
                    .sourceType(metadata.get(Metadata.CONTENT_TYPE))
                    .blocks(Collections.emptyList())
                    .build();
        }

        List<DocumentBlock> blocks = new ArrayList<>();
        for (String para : fullText.split("\\n{2,}")) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                blocks.add(DocumentBlock.text(Collections.emptyList(), null, trimmed));
            }
        }

        String title = metadata.get("dc:title");
        if (StrUtil.isBlank(title)) title = filename;

        log.info("Tika 解析完成：content-type={}, blocks={}",
                metadata.get(Metadata.CONTENT_TYPE), blocks.size());
        return ParsedDocument.builder()
                .title(title)
                .sourceType(metadata.get(Metadata.CONTENT_TYPE))
                .blocks(blocks)
                .build();
    }
}
