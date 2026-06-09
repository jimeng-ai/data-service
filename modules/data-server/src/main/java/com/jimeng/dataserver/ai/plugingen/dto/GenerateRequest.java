package com.jimeng.dataserver.ai.plugingen.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 生成插件草稿的入参（JSON 形式）。四种输入择一：
 * 粘贴文本 {@link #text}、截图 {@link #imageBase64}、文档链接 {@link #docUrl}；
 * 上传文件走另一个 multipart 端点。
 */
@Data
public class GenerateRequest {
    /** 粘贴的文本 / Markdown / curl / OpenAPI */
    private String text;
    /** 截图的 base64（不含 data: 前缀） */
    private String imageBase64;
    /** 截图 MIME，如 image/png、image/jpeg */
    private String imageMediaType;
    /** 可访问的 API 文档链接（服务端抓取；若是 llms.txt 索引会自动跟进抓取其中的 api-*.md） */
    private String docUrl;
    /** 一批具体的接口文档链接（前端「自动跑完整份文档」分批时用，直接抓取这几个、不再做索引识别） */
    private List<String> docUrls;
}
