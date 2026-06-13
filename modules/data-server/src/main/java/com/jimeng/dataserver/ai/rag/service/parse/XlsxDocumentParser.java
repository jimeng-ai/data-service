package com.jimeng.dataserver.ai.rag.service.parse;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel (.xlsx / .xls / .xlsm) 专用解析：按「表」的行列语义切，而不是像 Tika 那样把整张表拍扁成无结构纯文本。
 *
 * <p>核心策略——<b>每个数据行带着表头一起入库</b>：
 * <ul>
 *   <li>每个 sheet 作为一层 heading（{@code headingPath=[sheetName]}），多 sheet 互不串。</li>
 *   <li>自动识别表头行：扫描前若干行，取第一行「非空单元格 ≥ 2」的作为表头；其上的行（如标题/说明）当前言 TEXT。
 *       <b>表头识别只看单元格原始值（不做合并填充）</b>，避免横向合并的标题带被误判成表头。</li>
 *   <li>每个数据行渲染成 {@code 表头1: 值1 | 表头2: 值2 | ...}（跳过空单元格），产出为 TEXT block。
 *       数据行取值会对<b>合并单元格做填充</b>：被合并覆盖的单元格回取合并区左上角锚点值，使纵向合并的
 *       「大类」等分类列在每行都重复出现，保住每行自描述。</li>
 * </ul>
 *
 * <p>产出按行产出 TEXT block 后，交由 {@code HierarchicalChunker} 按 token 自动合并成窗口（行多合并、
 * 超限按行 / 按「 | 」边界硬切），所以即便某片只命中一行，也因行内自带表头而语义完整——这正是表格类知识库
 * （价目表、编码表、参数对照表）检索质量的关键。
 *
 * <p>已知限制：单 sheet 内堆叠多张表（中途出现第二个表头）会沿用首个表头；如有需要再单独支持。
 */
@Slf4j
@Component
@Order(0)
public class XlsxDocumentParser implements DocumentParser {

    /** 识别表头时最多向下扫描的行数：表头通常在文件很靠前的位置。 */
    private static final int HEADER_SCAN_LIMIT = 25;
    /** 判定为「表头行」所需的最少非空单元格数。 */
    private static final int MIN_HEADER_CELLS = 2;

    @Override
    public boolean supports(String mimeType, String filename) {
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm")) {
                return true;
            }
        }
        if (mimeType == null) {
            return false;
        }
        String m = mimeType.toLowerCase();
        return m.contains("spreadsheetml") || m.equals("application/vnd.ms-excel");
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename) throws Exception {
        return parse(stream, filename, false);
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename, boolean rowPerChunk) throws Exception {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(stream)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = safeEvaluator(workbook);

            int sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < sheetCount; s++) {
                if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) {
                    continue;
                }
                parseSheet(workbook.getSheetAt(s), formatter, evaluator, blocks, rowPerChunk);
            }
        }

        log.info("XLSX 解析完成：file={}, blocks={}", filename, blocks.size());
        return ParsedDocument.builder()
                .title(filename)
                .sourceType("xlsx")
                .blocks(blocks)
                .build();
    }

    /* ------------------------------------------------------------------ per-sheet */

    private void parseSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                            List<DocumentBlock> out, boolean rowPerChunk) {
        String sheetName = sheet.getSheetName();
        List<String> heading = List.of(sheetName == null ? "Sheet" : sheetName);

        int first = sheet.getFirstRowNum();
        int last = sheet.getLastRowNum();
        if (last < first) {
            return; // 空 sheet
        }

        List<CellRangeAddress> merges = sheet.getMergedRegions();
        int headerRowIdx = findHeaderRow(sheet, formatter, evaluator, first, last);

        if (headerRowIdx < 0) {
            // 没有可识别的表头（自由文本 / 单列）：逐非空行直接成文本，至少不丢内容。
            for (int r = first; r <= last; r++) {
                addIfNotBlank(out, heading, renderRowAsText(sheet.getRow(r), formatter, evaluator));
            }
            return;
        }

        // 表头之前的行：当作前言/说明，原样保留为文本（如「附录E XXX分类与编码」标题行）。
        for (int r = first; r < headerRowIdx; r++) {
            addIfNotBlank(out, heading, renderRowAsText(sheet.getRow(r), formatter, evaluator));
        }

        List<String> headers = readHeaders(sheet.getRow(headerRowIdx), formatter, evaluator);

        // 数据行：每行渲染成「表头: 值 | ...」，带上表头语义；取值对合并单元格做填充。
        // rowPerChunk=true 时产出 TABLE block（分块阶段一行一 chunk，FAQ 表场景）；否则 TEXT（按 token 合并）。
        for (int r = headerRowIdx + 1; r <= last; r++) {
            addDataRow(out, heading,
                    renderRowWithHeaders(sheet, merges, r, headers, formatter, evaluator), rowPerChunk);
        }
    }

    private void addIfNotBlank(List<DocumentBlock> out, List<String> heading, String text) {
        if (StrUtil.isNotBlank(text)) {
            out.add(DocumentBlock.text(heading, null, text));
        }
    }

    /** 数据行落 block：逐行切片开关打开 → TABLE（独立成片）；否则 TEXT（可被合并）。 */
    private void addDataRow(List<DocumentBlock> out, List<String> heading, String text, boolean rowPerChunk) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        out.add(rowPerChunk ? DocumentBlock.table(heading, null, text)
                            : DocumentBlock.text(heading, null, text));
    }

    /** 在前 {@value #HEADER_SCAN_LIMIT} 行内找第一行「非空单元格 ≥ {@value #MIN_HEADER_CELLS}」作为表头（看原始值，不做合并填充）。 */
    private int findHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator,
                              int first, int last) {
        int scanEnd = Math.min(last, first + HEADER_SCAN_LIMIT);
        for (int r = first; r <= scanEnd; r++) {
            if (countNonBlankCells(sheet.getRow(r), formatter, evaluator) >= MIN_HEADER_CELLS) {
                return r;
            }
        }
        return -1;
    }

    private List<String> readHeaders(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> headers = new ArrayList<>();
        if (row == null) {
            return headers;
        }
        int lastCol = row.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            String v = cellValue(row.getCell(c), formatter, evaluator);
            // 表头空列给个占位名，保证后续「列名: 值」对齐
            headers.add(StrUtil.isBlank(v) ? "列" + (c + 1) : v);
        }
        return headers;
    }

    /** 把一行渲染成「表头: 值 | 表头: 值」，跳过空单元格；合并单元格回取锚点值；整行全空返回空串。 */
    private String renderRowWithHeaders(Sheet sheet, List<CellRangeAddress> merges, int r,
                                        List<String> headers, DataFormatter formatter,
                                        FormulaEvaluator evaluator) {
        Row row = sheet.getRow(r);
        // 列数取「表头列数」与「本行实际列数」的较大者：既覆盖表头列，也保留多出来的列。
        int cols = headers.size();
        if (row != null) {
            cols = Math.max(cols, row.getLastCellNum());
        }
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            String v = filledCellValue(sheet, merges, r, c, formatter, evaluator);
            if (StrUtil.isBlank(v)) {
                continue;
            }
            String name = c < headers.size() ? headers.get(c) : "列" + (c + 1);
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(name).append(": ").append(v);
        }
        return sb.toString();
    }

    /** 把一行所有非空单元格用空格拼起来（无表头场景 / 前言行；不做合并填充，保持原貌）。 */
    private String renderRowAsText(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int lastCol = row.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            String v = cellValue(row.getCell(c), formatter, evaluator);
            if (StrUtil.isBlank(v)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private int countNonBlankCells(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return 0;
        }
        int count = 0;
        int lastCol = row.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            if (StrUtil.isNotBlank(cellValue(row.getCell(c), formatter, evaluator))) {
                count++;
            }
        }
        return count;
    }

    /** 取单元格值，并对合并区做填充：若本格为空且落在某合并区内，回取该区左上角锚点值（纵向合并的分类列因此在每行重复）。 */
    private String filledCellValue(Sheet sheet, List<CellRangeAddress> merges, int r, int c,
                                   DataFormatter formatter, FormulaEvaluator evaluator) {
        Row row = sheet.getRow(r);
        String v = row == null ? "" : cellValue(row.getCell(c), formatter, evaluator);
        if (StrUtil.isNotBlank(v) || merges.isEmpty()) {
            return v;
        }
        for (CellRangeAddress reg : merges) {
            if (reg.isInRange(r, c)) {
                Row anchorRow = sheet.getRow(reg.getFirstRow());
                Cell anchor = anchorRow == null ? null : anchorRow.getCell(reg.getFirstColumn());
                return cellValue(anchor, formatter, evaluator);
            }
        }
        return v;
    }

    /**
     * 取单元格显示值：DataFormatter 按单元格格式还原（日期、百分比、保留前导零的编码等）；公式求值失败回退缓存值。
     * 同时把单元格内的换行 / 制表符压成空格——保证「一行表格 = 一行文本」，否则后续切片器按 \n 硬切会把一行拆散。
     */
    private String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        String v;
        try {
            v = formatter.formatCellValue(cell, evaluator);
        } catch (Exception e) {
            // 公式求值异常（外部链接、不支持的函数等）：退回不求值的格式化，至少拿到缓存值
            try {
                v = formatter.formatCellValue(cell);
            } catch (Exception ignore) {
                return "";
            }
        }
        if (v == null) {
            return "";
        }
        return v.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private FormulaEvaluator safeEvaluator(Workbook workbook) {
        try {
            return workbook.getCreationHelper().createFormulaEvaluator();
        } catch (Exception e) {
            log.warn("创建公式求值器失败，将用缓存值: {}", e.getMessage());
            return null;
        }
    }
}
