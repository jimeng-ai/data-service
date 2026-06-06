package com.jimeng.dataserver.ai.rag.service.parse;

import com.jimeng.dataserver.ai.rag.model.BlockType;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link XlsxDocumentParser} 单测：在内存造工作簿（前言行 / 表头 / 数据行 / 空行 / 公式 / 多 sheet），
 * 验证「每行带表头、表头前的行当前言、空行跳过、多 sheet 分 heading」等关键行为。
 */
class XlsxDocumentParserTest {

    private final XlsxDocumentParser parser = new XlsxDocumentParser();

    @Test
    void supports_byExtension() {
        assertTrue(parser.supports(null, "a.xlsx"));
        assertTrue(parser.supports(null, "A.XLS"));
        assertTrue(parser.supports(null, "b.xlsm"));
        assertFalse(parser.supports(null, "c.pdf"));
        assertFalse(parser.supports(null, "d.docx"));
    }

    @Test
    void parsesHeaderWithEachRow_skipsPreambleAndEmptyRows() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("分类表");
            // row0: 前言标题（仅 1 个非空单元格）→ 应作前言，不当表头
            setRow(sheet, 0, "附录E 分类与编码");
            // row1: 表头（>=2 非空）
            setRow(sheet, 1, "序号", "编码", "名称");
            // row2: 数据
            setRow(sheet, 2, "1", "010000", "汽车服务");
            // row3: 空行 → 跳过
            sheet.createRow(3);
            // row4: 数据（缺中间列，空单元格应被跳过）
            setRow(sheet, 4, "2", "", "加油站");
            bytes = toBytes(wb);
        }

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(bytes), "t.xlsx");
        List<DocumentBlock> blocks = doc.getBlocks();

        // 前言1 + 数据行2（空行跳过、表头行不单独成块）
        assertEquals(3, blocks.size(), "应为：前言 + 2 条数据行");
        assertEquals("xlsx", doc.getSourceType());

        DocumentBlock preamble = blocks.get(0);
        assertEquals(BlockType.TEXT, preamble.getType());
        assertEquals("附录E 分类与编码", preamble.getText());
        assertEquals(List.of("分类表"), preamble.getHeadingPath(), "heading 应为 sheet 名");

        DocumentBlock row1 = blocks.get(1);
        assertEquals("序号: 1 | 编码: 010000 | 名称: 汽车服务", row1.getText(),
                "每行应带表头；文本存储的前导零应保留");

        DocumentBlock row2 = blocks.get(2);
        assertEquals("序号: 2 | 名称: 加油站", row2.getText(), "空单元格应被跳过，不输出「编码: 」");
    }

    @Test
    void multipleSheets_getSeparateHeadings() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s1 = wb.createSheet("Sheet A");
            setRow(s1, 0, "col1", "col2");
            setRow(s1, 1, "a1", "a2");
            Sheet s2 = wb.createSheet("Sheet B");
            setRow(s2, 0, "k1", "k2");
            setRow(s2, 1, "b1", "b2");
            bytes = toBytes(wb);
        }

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(bytes), "multi.xlsx");
        List<DocumentBlock> blocks = doc.getBlocks();
        assertEquals(2, blocks.size());
        assertEquals(List.of("Sheet A"), blocks.get(0).getHeadingPath());
        assertEquals("col1: a1 | col2: a2", blocks.get(0).getText());
        assertEquals(List.of("Sheet B"), blocks.get(1).getHeadingPath());
        assertEquals("k1: b1 | k2: b2", blocks.get(1).getText());
    }

    @Test
    void formulaAndNumber_renderViaDataFormatter() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("calc");
            setRow(sheet, 0, "a", "b", "sum");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(2);
            data.createCell(1).setCellValue(3);
            data.createCell(2).setCellFormula("A2+B2");
            bytes = toBytes(wb);
        }

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(bytes), "calc.xlsx");
        List<DocumentBlock> blocks = doc.getBlocks();
        assertEquals(1, blocks.size());
        // 公式应被求值为 5（DataFormatter + FormulaEvaluator）
        assertTrue(blocks.get(0).getText().contains("sum: 5"),
                "公式应求值：" + blocks.get(0).getText());
    }

    @Test
    void mergedCells_verticalFilledDown_horizontalTitleNotMistakenAsHeader() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("merge");
            // row0: 横向合并的标题带（仅锚点有值）→ 应作前言，不当表头
            setRow(sheet, 0, "汇总表");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            // row1: 表头
            setRow(sheet, 1, "大类", "明细");
            // row2: 纵向合并 col0（餐饮 跨 row2-row3）
            setRow(sheet, 2, "餐饮", "火锅");
            Row r3 = sheet.createRow(3);
            r3.createCell(1).setCellValue("烤肉"); // col0 空，被合并覆盖
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            bytes = toBytes(wb);
        }

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(bytes), "m.xlsx");
        List<DocumentBlock> blocks = doc.getBlocks();

        assertEquals(3, blocks.size(), "前言(标题带) + 2 条数据行");
        assertEquals("汇总表", blocks.get(0).getText(), "横向合并标题应作前言");
        assertEquals("大类: 餐饮 | 明细: 火锅", blocks.get(1).getText());
        assertEquals("大类: 餐饮 | 明细: 烤肉", blocks.get(2).getText(),
                "纵向合并的分类列应在第二行被填充重复");
    }

    @Test
    void cellWithEmbeddedNewlineOrTab_collapsedToSingleLine() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("nl");
            setRow(sheet, 0, "名称", "说明");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("行内\n换行");
            data.createCell(1).setCellValue("含\t制表");
            bytes = toBytes(wb);
        }

        ParsedDocument doc = parser.parse(new ByteArrayInputStream(bytes), "nl.xlsx");
        String text = doc.getBlocks().get(0).getText();
        assertFalse(text.contains("\n"), "不应残留换行：" + text);
        assertFalse(text.contains("\t"), "不应残留制表符：" + text);
        assertEquals("名称: 行内 换行 | 说明: 含 制表", text);
    }

    /* ----------------------------------------------------------- helpers */

    private static void setRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < values.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(values[c]);
        }
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws Exception {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
