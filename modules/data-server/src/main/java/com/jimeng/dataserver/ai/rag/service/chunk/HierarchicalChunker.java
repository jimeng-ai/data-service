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

import java.util.ArrayList;
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
                    result.add(buildStandaloneChunk(block, result.size()));
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
                    w = overlap + " " + w;
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

    /** 取 text 末尾约 N 个 token 的子串作为下一窗口前缀 */
    private String tailTokens(String text, int approxTokens) {
        if (StrUtil.isBlank(text) || approxTokens <= 0) return "";
        int totalTokens = tokenCounter.count(text);
        if (totalTokens <= approxTokens) return text;
        // 字符近似：cl100k 中文约 1 token ≈ 1.5 字符，英文 ≈ 4 字符。取保守 3 字符/token 估算
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

    private Chunk buildStandaloneChunk(DocumentBlock block, int index) {
        String content = block.getText() != null ? block.getText() : "";
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
