package com.jimeng.dataserver.ai.rag.service.parse;

import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PDF 解析：按页提取文本，每页一个 TEXT block，headingPath 留空交给后续启发式识别。
 *
 * <p>当前是 baseline 实现，足以覆盖一般 PDF。后续优化方向：
 * <ul>
 *   <li>按字号/字体启发式识别标题层级（PDFBox 提供 PDStream 字体信息）</li>
 *   <li>表格抽取（pdfbox-text-stripper-by-area + 基于坐标聚类）</li>
 *   <li>图片提取（PDImageXObject）</li>
 * </ul>
 */
@Slf4j
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String mimeType, String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) return true;
        return "application/pdf".equalsIgnoreCase(mimeType);
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(stream))) {
            int total = pdf.getNumberOfPages();
            List<DocumentBlock> blocks = new ArrayList<>(total);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).trim();
                if (!text.isEmpty()) {
                    blocks.add(DocumentBlock.text(Collections.emptyList(), page, text));
                }
            }

            String title = filename;
            PDDocumentInformation info = pdf.getDocumentInformation();
            if (info != null && info.getTitle() != null && !info.getTitle().isBlank()) {
                title = info.getTitle();
            }

            log.info("PDF 解析完成：pages={}, blocks={}", total, blocks.size());
            return ParsedDocument.builder()
                    .title(title)
                    .sourceType("pdf")
                    .blocks(blocks)
                    .build();
        }
    }
}
