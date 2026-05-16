package com.jimeng.dataserver.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析后的文档基本单元。Parser 把整篇文档拆成有序 block 列表交给 chunker。
 *
 * - TEXT / TABLE / CODE：用 {@code text} 承载内容
 * - IMAGE：用 {@code imageBytes} + {@code imageMediaType} 承载像素数据（后续由 ImageDescriptionService 转描述）
 *
 * {@code headingPath} 是层级路径，比如 ["Ch.1 引言", "1.2 背景"]。chunker 会用它在 chunk 元数据里拼成
 * "Ch.1 引言 > 1.2 背景" 便于回引。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DocumentBlock {

    private BlockType type;
    private List<String> headingPath;
    private Integer pageNum;

    private String text;
    private String language;
    private byte[] imageBytes;
    private String imageMediaType;

    public static DocumentBlock text(List<String> headingPath, Integer pageNum, String text) {
        return DocumentBlock.builder()
                .type(BlockType.TEXT)
                .headingPath(headingPath == null ? new ArrayList<>() : headingPath)
                .pageNum(pageNum)
                .text(text)
                .build();
    }

    public static DocumentBlock table(List<String> headingPath, Integer pageNum, String text) {
        return DocumentBlock.builder()
                .type(BlockType.TABLE)
                .headingPath(headingPath == null ? new ArrayList<>() : headingPath)
                .pageNum(pageNum)
                .text(text)
                .build();
    }

    public static DocumentBlock code(List<String> headingPath, Integer pageNum, String text, String language) {
        return DocumentBlock.builder()
                .type(BlockType.CODE)
                .headingPath(headingPath == null ? new ArrayList<>() : headingPath)
                .pageNum(pageNum)
                .text(text)
                .language(language)
                .build();
    }

    public static DocumentBlock image(List<String> headingPath, Integer pageNum, byte[] bytes, String mediaType) {
        return DocumentBlock.builder()
                .type(BlockType.IMAGE)
                .headingPath(headingPath == null ? new ArrayList<>() : headingPath)
                .pageNum(pageNum)
                .imageBytes(bytes)
                .imageMediaType(mediaType)
                .build();
    }
}
