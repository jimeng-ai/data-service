package com.jimeng.dataserver.ai.rag.service.parse;

import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CsvDocumentParser} 单测：编码探测(GBK/UTF-8)、引号字段、表头随行、前言、分隔符探测、TSV。 */
class CsvDocumentParserTest {

    private final CsvDocumentParser parser = new CsvDocumentParser();

    private ParsedDocument parse(byte[] bytes, String name) throws Exception {
        return parser.parse(new ByteArrayInputStream(bytes), name);
    }

    @Test
    void supports_byExtension() {
        assertTrue(parser.supports(null, "a.csv"));
        assertTrue(parser.supports(null, "b.TSV"));
        assertFalse(parser.supports(null, "c.xlsx"));
    }

    @Test
    void utf8_headerWithEachRow() throws Exception {
        String csv = "序号,编码,名称\n1,010000,汽车服务\n2,010100,加油站\n";
        ParsedDocument doc = parse(csv.getBytes(StandardCharsets.UTF_8), "t.csv");
        List<DocumentBlock> b = doc.getBlocks();
        assertEquals(2, b.size());
        assertEquals("序号: 1 | 编码: 010000 | 名称: 汽车服务", b.get(0).getText());
        assertEquals("序号: 2 | 编码: 010100 | 名称: 加油站", b.get(1).getText());
        assertEquals("csv", doc.getSourceType());
    }

    @Test
    void gbkEncoding_isAutoDetected() throws Exception {
        Charset gbk = Charset.forName("GBK");
        String csv = "名称,描述\n火锅,麻辣鲜香\n烤肉,炭火现烤\n";
        ParsedDocument doc = parse(csv.getBytes(gbk), "gbk.csv");
        List<DocumentBlock> b = doc.getBlocks();
        assertEquals(2, b.size());
        // 若编码探测失败按 UTF-8 读会乱码，断言中文正确即证明 GBK 被正确识别
        assertEquals("名称: 火锅 | 描述: 麻辣鲜香", b.get(0).getText());
        assertEquals("名称: 烤肉 | 描述: 炭火现烤", b.get(1).getText());
    }

    @Test
    void quotedFields_withCommaAndNewline() throws Exception {
        // 第2列字段含逗号；第3列字段含换行（引号包裹），换行应被压成空格
        String csv = "id,addr,note\n1,\"北京,海淀\",\"第一行\n第二行\"\n";
        ParsedDocument doc = parse(csv.getBytes(StandardCharsets.UTF_8), "q.csv");
        List<DocumentBlock> b = doc.getBlocks();
        assertEquals(1, b.size());
        String t = b.get(0).getText();
        assertTrue(t.contains("addr: 北京,海淀"), "字段内逗号应保留：" + t);
        assertTrue(t.contains("note: 第一行 第二行"), "字段内换行应压成空格：" + t);
        assertFalse(t.contains("\n"));
    }

    @Test
    void preambleBeforeHeader_isKept() throws Exception {
        String csv = "城市POI对照表\n序号,编码\n1,A001\n";
        ParsedDocument doc = parse(csv.getBytes(StandardCharsets.UTF_8), "p.csv");
        List<DocumentBlock> b = doc.getBlocks();
        assertEquals(2, b.size());
        assertEquals("城市POI对照表", b.get(0).getText(), "表头前单列标题应作前言");
        assertEquals("序号: 1 | 编码: A001", b.get(1).getText());
    }

    @Test
    void tsv_tabDelimited() throws Exception {
        String tsv = "a\tb\n1\t2\n";
        ParsedDocument doc = parse(tsv.getBytes(StandardCharsets.UTF_8), "x.tsv");
        assertEquals(1, doc.getBlocks().size());
        assertEquals("a: 1 | b: 2", doc.getBlocks().get(0).getText());
    }

    @Test
    void semicolonDelimited_autoDetected() throws Exception {
        String csv = "k1;k2;k3\nv1;v2;v3\n";
        ParsedDocument doc = parse(csv.getBytes(StandardCharsets.UTF_8), "s.csv");
        assertEquals(1, doc.getBlocks().size());
        assertEquals("k1: v1 | k2: v2 | k3: v3", doc.getBlocks().get(0).getText());
    }
}
