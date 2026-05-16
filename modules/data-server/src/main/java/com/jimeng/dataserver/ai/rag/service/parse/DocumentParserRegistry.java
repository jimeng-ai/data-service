package com.jimeng.dataserver.ai.rag.service.parse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 按 mime+filename 选 parser。专用 parser（PDF/DOCX/MD）排前，{@link TikaDocumentParser} 兜底。
 * 顺序由 Spring 的 {@code @Order} / bean 类型决定 —— Tika 一定最后。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;
    private final TikaDocumentParser tikaFallback;

    public DocumentParser resolve(String mimeType, String filename) {
        for (DocumentParser p : parsers) {
            if (p == tikaFallback) continue;
            if (p.supports(mimeType, filename)) {
                log.debug("选择 parser: {} for {} ({})", p.getClass().getSimpleName(), filename, mimeType);
                return p;
            }
        }
        log.debug("fallback Tika parser for {} ({})", filename, mimeType);
        return tikaFallback;
    }
}
