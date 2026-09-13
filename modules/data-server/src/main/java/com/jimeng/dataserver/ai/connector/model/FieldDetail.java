package com.jimeng.dataserver.ai.connector.model;

/**
 * 对象里的一个字段：表的列 / 接口的参数。
 *
 * @param name     字段名
 * @param type     原生类型
 * @param nullable 可空
 * @param comment  业务说明（来自列注释；语义层可覆盖）
 * @param extra    补充（主键 / 自增 / 默认值 / 是否必填）
 */
public record FieldDetail(String name, String type, boolean nullable, String comment, String extra) {}
