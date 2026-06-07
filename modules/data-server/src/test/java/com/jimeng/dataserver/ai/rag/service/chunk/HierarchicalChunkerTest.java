package com.jimeng.dataserver.ai.rag.service.chunk;

import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.Chunk;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定表格类文本（每行 "列名: 值 | 列名: 值"、行间 \n）经多窗口 + overlap 切片后，
 * 每一行仍保持完整、行首列名不丢、相邻行不被空格黏成一行。
 *
 * 历史 bug：L3 overlap 按字符取上一窗口尾巴并用空格拼接，导致 chunk 开头出现半行碎片
 * （"小类: 东方 | ..." 丢了 序号/NEW_TYPE 等前导列）、边界两行黏连（"...PetroChina 序号: 10"）。
 */
class HierarchicalChunkerTest {

    private HierarchicalChunker newChunker() {
        return new HierarchicalChunker(new RagProperties(), new TokenCounter());
    }

    /** 造一张足够大的表（每行自带表头），逼出多窗口 + overlap 路径。 */
    private ParsedDocument bigTable(int rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= rows; i++) {
            sb.append("序号: ").append(i)
                    .append(" | NEW_TYPE: 0101").append(String.format("%02d", i))
                    .append(" | 大类: 汽车服务 | 中类: 加油站 | 小类: 站点").append(i)
                    .append(" | Big Category: Auto Service | Mid Category: Filling Station")
                    .append(" | Sub Category: Sample Station ").append(i);
            if (i < rows) sb.append('\n');
        }
        DocumentBlock block = DocumentBlock.text(List.of("附录E POI分类与编码"), 1, sb.toString());
        return ParsedDocument.builder().title("poi.xlsx").sourceType("xlsx")
                .blocks(List.of(block)).build();
    }

    @Test
    void tableRowsStayIntactAcrossOverlappingWindows() {
        List<Chunk> chunks = newChunker().chunk(bigTable(80));

        // 必须真的切成了多片（否则没经过 overlap 路径，测试失去意义）
        assertThat(chunks).hasSizeGreaterThan(1);

        for (Chunk c : chunks) {
            List<String> lines = c.getContent().lines()
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            for (String line : lines) {
                // 每个非空行都应是一条完整的行：以前导列 "序号:" 开头
                assertThat(line)
                        .as("行首列名不应丢失（无半行碎片）：%s", line)
                        .startsWith("序号:");
                // 一行里不应出现两条记录（相邻行未被空格黏连）
                int count = line.split("序号:", -1).length - 1;
                assertThat(count)
                        .as("相邻行不应被黏成一行：%s", line)
                        .isEqualTo(1);
            }
        }
    }
}
