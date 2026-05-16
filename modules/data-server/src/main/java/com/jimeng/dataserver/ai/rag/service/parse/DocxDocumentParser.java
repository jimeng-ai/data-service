package com.jimeng.dataserver.ai.rag.service.parse;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word (.docx) 解析：通过段落 style 识别 heading 层级，表格独立成 TableBlock。
 *
 * <p>识别规则：
 * <ul>
 *   <li>{@code Heading 1} / {@code Heading 2} ... 形式的 style → 对应层级</li>
 *   <li>{@code 标题 1} 中文 style → 对应层级</li>
 *   <li>其余 paragraph 视为普通 TEXT block，沿用当前 headingPath</li>
 * </ul>
 */
@Slf4j
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile(
            "(?:Heading|标题)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String mimeType, String filename) {
        if (filename != null && filename.toLowerCase().endsWith(".docx")) return true;
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mimeType);
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename) throws Exception {
        try (XWPFDocument docx = new XWPFDocument(stream)) {
            List<DocumentBlock> blocks = new ArrayList<>();
            String[] headingStack = new String[7];
            String title = null;

            for (var bodyElement : docx.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph p) {
                    String text = p.getText();
                    if (StrUtil.isBlank(text)) continue;

                    Integer level = headingLevelOf(p.getStyle());
                    if (level != null) {
                        int lv = Math.min(level, 6);
                        headingStack[lv] = text.trim();
                        for (int i = lv + 1; i < headingStack.length; i++) headingStack[i] = null;
                        if (lv == 1 && title == null) title = text.trim();
                    } else {
                        blocks.add(DocumentBlock.text(currentPath(headingStack), null, text.trim()));
                    }
                } else if (bodyElement instanceof XWPFTable table) {
                    String md = tableToMarkdown(table);
                    if (!md.isBlank()) {
                        blocks.add(DocumentBlock.table(currentPath(headingStack), null, md));
                    }
                }
            }

            log.info("DOCX 解析完成：blocks={}", blocks.size());
            return ParsedDocument.builder()
                    .title(title != null ? title : filename)
                    .sourceType("docx")
                    .blocks(blocks)
                    .build();
        }
    }

    private static Integer headingLevelOf(String style) {
        if (StrUtil.isBlank(style)) return null;
        Matcher m = HEADING_STYLE.matcher(style);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static List<String> currentPath(String[] stack) {
        List<String> path = new ArrayList<>();
        for (String h : stack) if (h != null) path.add(h);
        return path;
    }

    private static String tableToMarkdown(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            sb.append('|');
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(' ').append(cell.getText().replace('\n', ' ').trim()).append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int i = 0; i < row.getTableCells().size(); i++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
