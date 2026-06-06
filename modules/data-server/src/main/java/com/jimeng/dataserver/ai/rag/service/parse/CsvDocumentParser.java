package com.jimeng.dataserver.ai.rag.service.parse;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.detect.AutoDetectReader;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CSV / TSV 专用解析：与 {@link XlsxDocumentParser} 一致的「每个数据行带着表头一起入库」策略，
 * 而不是像 Tika 那样把整张表拍扁成无结构纯文本。
 *
 * <p>两个 CSV 特有的坑都处理了：
 * <ul>
 *   <li><b>编码</b>：Excel 导出的中文 CSV 常是 GBK/GB2312 而非 UTF-8，按 UTF-8 读会乱码。
 *       这里用 Tika 的 {@link AutoDetectReader} 自动探测字符集（失败回退 UTF-8）。</li>
 *   <li><b>带引号字段</b>：字段内含逗号 / 换行 / 转义引号，用内置最小 RFC-4180 解析器正确处理。</li>
 * </ul>
 *
 * <p>分隔符在逗号 / 制表符 / 分号间自动探测（.tsv 默认制表符）。表头识别、每行渲染成
 * {@code 表头: 值 | ...} 的规则与 xlsx 完全一致。
 */
@Slf4j
@Component
@Order(0)
public class CsvDocumentParser implements DocumentParser {

    private static final int HEADER_SCAN_LIMIT = 25;
    private static final int MIN_HEADER_CELLS = 2;
    private static final char[] DELIMITER_CANDIDATES = {',', '\t', ';'};

    @Override
    public boolean supports(String mimeType, String filename) {
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
                return true;
            }
        }
        return "text/csv".equalsIgnoreCase(mimeType) || "text/tab-separated-values".equalsIgnoreCase(mimeType);
    }

    @Override
    public ParsedDocument parse(InputStream stream, String filename) throws Exception {
        byte[] bytes = stream.readAllBytes();
        String text = decode(bytes);

        char delim = (filename != null && filename.toLowerCase().endsWith(".tsv"))
                ? '\t' : detectDelimiter(text);
        List<List<String>> rows = parseCsv(text, delim);

        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> heading = new ArrayList<>(); // CSV 无 sheet 概念，单表无 heading

        int headerIdx = findHeaderRow(rows);
        if (headerIdx < 0) {
            // 无可识别表头（单列 / 自由文本）：逐非空行成文本，至少不丢内容
            for (List<String> row : rows) {
                addIfNotBlank(blocks, heading, joinNonBlank(row));
            }
        } else {
            for (int r = 0; r < headerIdx; r++) {
                addIfNotBlank(blocks, heading, joinNonBlank(rows.get(r)));
            }
            List<String> headers = readHeaders(rows.get(headerIdx));
            for (int r = headerIdx + 1; r < rows.size(); r++) {
                addIfNotBlank(blocks, heading, renderRowWithHeaders(rows.get(r), headers));
            }
        }

        log.info("CSV 解析完成：file={}, delim={}, rows={}, blocks={}",
                filename, delim == '\t' ? "\\t" : delim, rows.size(), blocks.size());
        return ParsedDocument.builder()
                .title(filename)
                .sourceType("csv")
                .blocks(blocks)
                .build();
    }

    /* ------------------------------------------------------------------ encoding */

    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 解码字节为字符串。统计型字符集探测对短文本不可靠（中文短 CSV 常被误判成西文编码 → 乱码），
     * 因此优先用「严格解码 + 自校验」：
     * <ol>
     *   <li>识别 BOM（UTF-8 / UTF-16）；</li>
     *   <li>严格 UTF-8 解码——UTF-8 多字节结构强校验，GBK 中文字节基本不是合法 UTF-8，能可靠区分；</li>
     *   <li>严格 GBK 解码（中文非 UTF-8 的主力编码）；</li>
     *   <li>都不过再交给 Tika 统计探测（Big5 / Shift-JIS 等少数情况）；</li>
     *   <li>最后兜底 UTF-8 宽松解码。</li>
     * </ol>
     */
    private String decode(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        Charset bom = bomCharset(bytes);
        if (bom != null) {
            return new String(stripBom(bytes), bom);
        }
        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = strictDecode(bytes, GBK);
        if (gbk != null) {
            return gbk;
        }
        try (AutoDetectReader reader = new AutoDetectReader(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder(bytes.length);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("CSV 字符集探测失败，回退 UTF-8: {}", e.getMessage());
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 严格解码：遇到非法 / 不可映射字节即返回 null（用于把某编码当作「校验器」）。 */
    private String strictDecode(byte[] bytes, Charset cs) {
        try {
            CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private Charset bomCharset(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        return null;
    }

    private byte[] stripBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return Arrays.copyOfRange(b, 3, b.length);
        }
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF
                || (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)) {
            return Arrays.copyOfRange(b, 2, b.length);
        }
        return b;
    }

    /** 取第一条非空行，在候选分隔符里选出现次数最多者；都没有则默认逗号。 */
    private char detectDelimiter(String text) {
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            char best = ',';
            int bestCount = -1;
            for (char cand : DELIMITER_CANDIDATES) {
                int cnt = count(t, cand);
                if (cnt > bestCount) {
                    bestCount = cnt;
                    best = cand;
                }
            }
            return bestCount > 0 ? best : ',';
        }
        return ',';
    }

    private int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    /* ------------------------------------------------------------------ RFC-4180 parse */

    /** 最小 RFC-4180 解析：支持双引号包裹、字段内分隔符 / 换行、"" 转义引号、\r\n 与 \r / \n 行尾。 */
    private List<List<String>> parseCsv(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
            } else if (ch == delim) {
                cur.add(field.toString());
                field.setLength(0);
            } else if (ch == '\r') {
                // 行尾：\r\n 时跳过 \r，留给下一轮 \n 收尾；单独 \r 直接收尾
                if (i + 1 < n && text.charAt(i + 1) == '\n') {
                    continue;
                }
                cur.add(field.toString());
                field.setLength(0);
                rows.add(cur);
                cur = new ArrayList<>();
            } else if (ch == '\n') {
                cur.add(field.toString());
                field.setLength(0);
                rows.add(cur);
                cur = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        // 收尾最后一条记录（文件不以换行结尾时）
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }

    /* ------------------------------------------------------------------ header + render（与 xlsx 一致） */

    private int findHeaderRow(List<List<String>> rows) {
        int scanEnd = Math.min(rows.size(), HEADER_SCAN_LIMIT);
        for (int r = 0; r < scanEnd; r++) {
            if (countNonBlank(rows.get(r)) >= MIN_HEADER_CELLS) {
                return r;
            }
        }
        return -1;
    }

    private List<String> readHeaders(List<String> row) {
        List<String> headers = new ArrayList<>();
        for (int c = 0; c < row.size(); c++) {
            String v = sanitize(row.get(c));
            headers.add(StrUtil.isBlank(v) ? "列" + (c + 1) : v);
        }
        return headers;
    }

    private String renderRowWithHeaders(List<String> row, List<String> headers) {
        StringBuilder sb = new StringBuilder();
        int cols = Math.max(headers.size(), row.size());
        for (int c = 0; c < cols; c++) {
            String v = c < row.size() ? sanitize(row.get(c)) : "";
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

    private String joinNonBlank(List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (String cell : row) {
            String v = sanitize(cell);
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

    private int countNonBlank(List<String> row) {
        int count = 0;
        for (String cell : row) {
            if (StrUtil.isNotBlank(sanitize(cell))) {
                count++;
            }
        }
        return count;
    }

    /** 压掉字段内换行 / 制表符，保证「一行表格 = 一行文本」，否则切片器按 \n 硬切会把一行拆散。 */
    private String sanitize(String v) {
        if (v == null) {
            return "";
        }
        return v.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private void addIfNotBlank(List<DocumentBlock> out, List<String> heading, String text) {
        if (StrUtil.isNotBlank(text)) {
            out.add(DocumentBlock.text(new ArrayList<>(heading), null, text));
        }
    }
}
