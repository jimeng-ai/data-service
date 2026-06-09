package com.jimeng.dataserver.ai.plugingen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * AI 抽取出的「入参」规格，与前端编辑器的 FieldDef 几乎一一对应。
 * 枚举不是独立类型：用 {@code type=string} + {@link #enumValues} 表达。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParamSpec {
    private String name;
    /** string | number | boolean | object | array */
    private String type;
    private String description;
    private Boolean required;
    /** query | body | path（对象/数组强制 body） */
    private String location;
    /** 仅 type=string 有意义 */
    private List<String> enumValues;
    /** type=object 的子字段 */
    private List<ParamSpec> fields;
    /** type=array 的元素类型：string | number | boolean | object */
    private String itemType;
    /** 元素为 object 时的子字段 */
    private List<ParamSpec> itemFields;
}
