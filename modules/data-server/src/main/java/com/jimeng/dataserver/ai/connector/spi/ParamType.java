package com.jimeng.dataserver.ai.connector.spi;

/** 连接器参数的类型。同时决定后端校验方式与前端表单控件。 */
public enum ParamType {
    STRING,
    /** 敏感值。前端渲染成密码框，后端加密存储，永不回读。 */
    PASSWORD,
    INT,
    BOOL,
    /** 单选，取值来自 {@link ParamField#options()}。 */
    ENUM,
    /** 多行文本（长 SQL 片段、PEM 证书）。 */
    TEXTAREA,
    /** 字符串数组（允许的 HTTP 方法、路径 glob）。 */
    STRING_LIST
}
