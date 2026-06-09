package com.jimeng.dataserver.ai.plugingen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * AI 抽取出的「输出字段」规格（来自示例响应）。v1 只取扁平的顶层字段，
 * 嵌套结构可由用户在「输出参数 · 自动解析」里补全。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutputSpec {
    private String name;
    /** string | number | boolean | object | array */
    private String type;
    private String description;
}
