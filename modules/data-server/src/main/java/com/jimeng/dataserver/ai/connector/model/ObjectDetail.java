package com.jimeng.dataserver.ai.connector.model;

import java.util.List;
import java.util.Map;

/**
 * 「看结构」的返回：某一个对象的细节。
 *
 * @param name    对象名
 * @param type    子类型
 * @param comment 对象级说明
 * @param fields  字段
 * @param extra   类型特有的补充（索引、约束、示例请求体…）
 */
public record ObjectDetail(String name, String type, String comment, List<FieldDetail> fields, Map<String, Object> extra) {}
