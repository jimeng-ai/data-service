package com.jimeng.dataserver.ai.rag.service.chunk;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.BlockType;
import com.jimeng.dataserver.ai.rag.model.Chunk;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 三层切片策略：
 * <ol>
 *   <li>L1：按 heading_path 自然边界切（同 heading 累积，path 变就 flush）；
 *       TABLE/CODE/IMAGE 立即独立 chunk 保留语义完整。</li>
 *   <li>L2：单段累积超 max-size-tokens（默认 800），按句号/换行二次切。</li>
 *   <li>L3：同源 text chunk 之间加 overlap-tokens（默认 80）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchicalChunker {

    private final RagProperties ragProperties;
    private final TokenCounter tokenCounter;

    public List<Chunk> chunk(ParsedDocument doc) {
        if (doc == null || doc.getBlocks() == null || doc.getBlocks().isEmpty()) {
            return new ArrayList<>();
        }
        RagProperties.Chunk cfg = ragProperties.getChunk();
        Pattern sentencePattern = Pattern.compile(cfg.getSentenceSplitter());

        List<Chunk> result = new ArrayList<>();
        Buffer buffer = new Buffer();

        for (DocumentBlock block : doc.getBlocks()) {
            if (block == null) continue;

            switch (block.getType()) {
                case TEXT -> {
                    if (StrUtil.isBlank(block.getText())) break;
                    boolean sameSection = buffer.isEmpty() ||
                            Objects.equals(buffer.headingPath, block.getHeadingPath());
                    if (!sameSection) {
                        flush(buffer, result, cfg, sentencePattern);
                    }
                    buffer.headingPath = block.getHeadingPath();
                    buffer.pageNum = block.getPageNum();
                    buffer.text.append(buffer.text.length() == 0 ? "" : "\n").append(block.getText().trim());
                }
                case TABLE, CODE, IMAGE -> {
                    flush(buffer, result, cfg, sentencePattern);
                    addStandaloneChunks(block, result, cfg);
                }
            }
        }
        flush(buffer, result, cfg, sentencePattern);

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setChunkIndex(i);
        }
        log.info("分片完成：blocks={} → chunks={}", doc.getBlocks().size(), result.size());
        return result;
    }

    /* ------------------------------------------------------- internals */

    /** 累积一个 section 的文本，flush 时按需走 L2/L3 二切 */
    private void flush(Buffer buffer, List<Chunk> out, RagProperties.Chunk cfg, Pattern sentencePattern) {
        if (buffer.isEmpty()) return;
        String text = buffer.text.toString().trim();
        int tokens = tokenCounter.count(text);

        if (tokens <= cfg.getMaxSizeTokens()) {
            out.add(buildTextChunk(text, buffer, tokens));
        } else {
            // L2：按句号拆，再按 target-size 凑窗口
            String[] sentences = sentencePattern.split(text);
            List<String> windows = packSentencesIntoWindows(sentences, cfg);
            // L3：windows 之间加 overlap
            for (int i = 0; i < windows.size(); i++) {
                String w = windows.get(i);
                if (i > 0 && cfg.getOverlapTokens() > 0) {
                    String overlap = tailTokens(windows.get(i - 1), cfg.getOverlapTokens());
                    // 用 \n 拼接 overlap 与本窗口：行结构文本（表格/代码）的 overlap 是整行，
                    // 换行拼接才能让边界行各自独立、不被空格黏成一行（如 "...BP PetroChina 序号: 10"）。
                    w = overlap + "\n" + w;
                }
                out.add(buildTextChunk(w, buffer, tokenCounter.count(w)));
            }
        }
        buffer.clear();
    }

    private List<String> packSentencesIntoWindows(String[] sentences, RagProperties.Chunk cfg) {
        List<String> windows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        for (String s : sentences) {
            if (StrUtil.isBlank(s)) continue;
            int t = tokenCounter.count(s);
            // 单句本身就超窗口（如电子表格/无句读长文：整片是一个「句子」）：
            // 不能原样塞进 window，否则该 chunk 会超过 embedding 模型上限导致整批向量化失败。
            // 先 flush 当前 window，再把这一句按 token 上限硬切成多片。
            if (t > cfg.getTargetSizeTokens()) {
                if (current.length() > 0) {
                    windows.add(current.toString().trim());
                    current.setLength(0);
                    currentTokens = 0;
                }
                windows.addAll(hardSplitByTokens(s, cfg.getTargetSizeTokens()));
                continue;
            }
            if (currentTokens + t > cfg.getTargetSizeTokens() && current.length() > 0) {
                windows.add(current.toString().trim());
                current.setLength(0);
                currentTokens = 0;
            }
            current.append(s).append(' ');
            currentTokens += t;
        }
        if (current.length() > 0) windows.add(current.toString().trim());
        return windows;
    }

    /**
     * 把一段超过 maxTokens 的文本硬切成若干 ≤ maxTokens 的片段：先按行（电子表格行 / 代码行 /
     * 表格行天然以换行分隔）凑窗口；遇到单行仍超限再按字符近似切。保证返回的每片都不超上限，
     * 从源头杜绝「单个 chunk 超过 embedding 模型 token 上限」。
     */
    private List<String> hardSplitByTokens(String text, int maxTokens) {
        maxTokens = Math.max(1, maxTokens); // 防御误配置（0/负）导致下游死循环
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTokens = 0;
        for (String line : text.split("\n", -1)) {
            int lt = tokenCounter.count(line);
            if (lt > maxTokens) {
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                    curTokens = 0;
                }
                out.addAll(splitLongLine(line, maxTokens));
                continue;
            }
            if (curTokens + lt > maxTokens && cur.length() > 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                curTokens = 0;
            }
            cur.append(line).append('\n');
            curTokens += lt;
        }
        if (cur.length() > 0) {
            String last = cur.toString().trim();
            if (!last.isEmpty()) out.add(last);
        }
        return out;
    }

    /**
     * 切一行超限文本：表格行渲染成「表头: 值 | 表头: 值」，优先按「 | 」边界切，尽量不把一对 header:value
     * 拦腰斩断（保住检索语义）；只有单个字段本身仍超限时才退化为按字符硬切。非表格行直接按字符切。
     */
    private List<String> splitLongLine(String line, int maxTokens) {
        if (!line.contains(" | ")) {
            return splitByChars(line, maxTokens);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTokens = 0;
        for (String seg : line.split(" \\| ", -1)) {
            int t = tokenCounter.count(seg);
            if (t > maxTokens) { // 单个字段就超限（巨型单元格）：flush 后按字符兜底
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    curTokens = 0;
                }
                out.addAll(splitByChars(seg, maxTokens));
                continue;
            }
            if (curTokens + t > maxTokens && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                curTokens = 0;
            }
            if (cur.length() > 0) {
                cur.append(" | ");
            }
            cur.append(seg);
            curTokens += t;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 按字符近似切到 token 上限以内（用 tokenCounter 收敛，确保每片真实 token ≤ maxTokens）。 */
    private List<String> splitByChars(String text, int maxTokens) {
        maxTokens = Math.max(1, maxTokens); // 防御误配置（0/负）导致死循环
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            // 起步按 maxTokens 个字符（中文最坏约 1 token/字符），再向下收敛到不超上限
            int end = Math.min(text.length(), i + maxTokens);
            while (end > i + 1 && tokenCounter.count(text.substring(i, end)) > maxTokens) {
                end -= Math.max(1, (end - i) / 8);
            }
            // 不要把 UTF-16 代理对（emoji 等 BMP 外字符）从中间劈开，否则产生孤立代理 → 乱码
            if (end < text.length() && end > i + 1
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            out.add(text.substring(i, end));
            i = end;
        }
        return out;
    }

    /** 取 text 末尾约 N 个 token 作为下一窗口前缀 */
    private String tailTokens(String text, int approxTokens) {
        if (StrUtil.isBlank(text) || approxTokens <= 0) return "";
        int totalTokens = tokenCounter.count(text);
        if (totalTokens <= approxTokens) return text;
        // 行结构文本（表格/代码：每行自带表头「列名: 值」）按【整行】取尾——从末尾逐行累加到 ≈approxTokens，
        // 绝不从行中间截断，否则 overlap 会丢掉行首列名（如只剩 "小类: 东方 | ..."，没了 序号/NEW_TYPE）。
        if (text.indexOf('\n') >= 0) {
            String[] lines = text.split("\n", -1);
            Deque<String> picked = new ArrayDeque<>();
            int acc = 0;
            for (int i = lines.length - 1; i >= 0; i--) {
                int lt = tokenCounter.count(lines[i]);
                if (acc + lt > approxTokens && !picked.isEmpty()) break;
                picked.addFirst(lines[i]);
                acc += lt;
            }
            return String.join("\n", picked);
        }
        // 纯文本（无行结构）：字符近似取尾。cl100k 中文约 1 token≈1.5 字符、英文≈4，保守按 3 字符/token。
        int approxChars = approxTokens * 3;
        int start = Math.max(0, text.length() - approxChars);
        return text.substring(start);
    }

    private Chunk buildTextChunk(String text, Buffer buffer, int tokens) {
        return Chunk.builder()
                .type(BlockType.TEXT)
                .content(text)
                .headingPath(buffer.headingPath)
                .pageNum(buffer.pageNum)
                .tokenCount(tokens)
                .build();
    }

    /**
     * 输出独立 chunk（TABLE/CODE/IMAGE）。内容超 max-size-tokens 时按 token 上限硬切成多片，
     * 每片保留原 block 类型与定位信息——否则一张大表 / 大代码块会成为一个超限 chunk，向量化失败。
     */
    private void addStandaloneChunks(DocumentBlock block, List<Chunk> out, RagProperties.Chunk cfg) {
        String content = block.getText() != null ? block.getText().trim() : "";
        if (content.isEmpty()) return;
        if (tokenCounter.count(content) <= cfg.getMaxSizeTokens()) {
            out.add(buildStandaloneChunk(block, content));
            return;
        }
        for (String piece : hardSplitByTokens(content, cfg.getTargetSizeTokens())) {
            if (!StrUtil.isBlank(piece)) out.add(buildStandaloneChunk(block, piece));
        }
    }

    private Chunk buildStandaloneChunk(DocumentBlock block, String content) {
        return Chunk.builder()
                .type(block.getType())
                .content(content)
                .headingPath(block.getHeadingPath())
                .pageNum(block.getPageNum())
                .tokenCount(tokenCounter.count(content))
                .build();
    }

    private static class Buffer {
        StringBuilder text = new StringBuilder();
        List<String> headingPath;
        Integer pageNum;

        boolean isEmpty() {
            return text.length() == 0;
        }

        void clear() {
            text.setLength(0);
        }
    }
}
