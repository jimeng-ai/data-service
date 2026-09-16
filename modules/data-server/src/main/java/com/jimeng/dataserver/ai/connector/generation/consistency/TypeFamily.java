package com.jimeng.dataserver.ai.connector.generation.consistency;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 列类型的族（设计文档 7.9 规则 2）。关系两端「能不能存同一种值」按族判，不按原始类型串判：
 * {@code int(11)} 与 {@code bigint unsigned} 是同一种东西，{@code varchar(32)} 与 {@code datetime} 不是。
 *
 * <p>输入是快照里的原生类型串（MySQL 的 {@code COLUMN_TYPE}，如 {@code bigint(20) unsigned}、{@code enum('a','b')}），
 * 取开头那个单词归类，大小写不敏感。认不出来的一律 {@link #UNKNOWN}——规则对未知放行：把一个没见过的类型名判成不兼容，
 * 退回的是一条可能完全正确的关系，而 agent 没有任何办法改对它。
 */
public enum TypeFamily {
    /** tinyint 到 bigint。{@code tinyint(1)} 是 MySQL 的布尔别名，但存的仍是整数，归这里。 */
    INTEGER,
    /** decimal、numeric。 */
    DECIMAL,
    /** float、double。 */
    FLOAT,
    /** char、varchar、text 系、enum、set。 */
    STRING,
    /** date、datetime、timestamp、time、year。 */
    TEMPORAL,
    /** binary、varbinary、blob 系。 */
    BINARY,
    /** bit、bool、boolean。 */
    BIT_BOOL,
    JSON,
    /** geometry、point 等空间类型。 */
    SPATIAL,
    /** 类型缺失或认不出。 */
    UNKNOWN;

    private static final Pattern BASE = Pattern.compile("^([a-z]+)");

    private static final Map<String, TypeFamily> BY_BASE = new HashMap<>();

    static {
        register(INTEGER, "tinyint", "smallint", "mediumint", "int", "integer", "bigint");
        register(DECIMAL, "decimal", "numeric", "dec", "fixed");
        register(FLOAT, "float", "double", "real");
        register(STRING, "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set",
                "nchar", "nvarchar", "character");
        register(TEMPORAL, "date", "datetime", "timestamp", "time", "year");
        register(BINARY, "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob");
        register(BIT_BOOL, "bit", "bool", "boolean");
        register(JSON, "json");
        register(SPATIAL, "geometry", "point", "linestring", "polygon", "multipoint", "multilinestring",
                "multipolygon", "geometrycollection", "geomcollection");
    }

    private static void register(TypeFamily family, String... bases) {
        for (String b : List.of(bases)) {
            BY_BASE.put(b, family);
        }
    }

    /** {@code BIGINT(20) UNSIGNED} → INTEGER；{@code double precision} → FLOAT；{@code null} / 空白 / 没见过 → UNKNOWN。 */
    public static TypeFamily of(String nativeType) {
        if (nativeType == null || nativeType.isBlank()) {
            return UNKNOWN;
        }
        Matcher m = BASE.matcher(nativeType.trim().toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return UNKNOWN;
        }
        return BY_BASE.getOrDefault(m.group(1), UNKNOWN);
    }
}
