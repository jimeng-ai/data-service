package com.jimeng.dataserver.ai.plugingen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * AI 抽取出的单个工具规格 ≈ 前端编辑器的「一个工具」（HttpForm + 入参）。
 * 后端不直接落库，由前端转换成 FieldDef[]+HttpForm 后走现有 createTool。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolSpec {
    /** 函数名：英文 snake_case，供 LLM 调用 */
    private String name;
    /** 中文展示名（给人看） */
    private String title;
    private String description;
    /** GET | POST | PUT | DELETE | PATCH */
    private String method;
    /** 接口路径（不含域名），如 /api/v2/wecom/moment/result */
    private String path;
    private List<ParamSpec> params;
    private List<HeaderKV> headers;
    private String bodyContentType;
    private List<OutputSpec> outputs;
    /** 推断/不确定点 */
    private List<String> warnings;
}
