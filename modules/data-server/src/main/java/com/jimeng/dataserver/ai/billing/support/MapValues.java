package com.jimeng.dataserver.ai.billing.support;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 从松散 {@code Map}（请求体/请求头解析结果）里按 key 取出类型化值的纯函数工具。
 * 解析失败只记日志、返回兜底值，绝不抛异常——记账落库不能因脏数据中断主流程。
 * 从 {@link com.jimeng.dataserver.ai.billing.AiModelCallRecordService} 抽出（阶段 3.1）。
 */
@Slf4j
public final class MapValues {

    private MapValues() {
    }

    /** 取字符串：缺失/空白回落到 {@code defaultValue}。 */
    public static String getString(Map<String, ?> map, String key, String defaultValue) {
        if (map == null || StrUtil.isBlank(key)) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return StrUtil.isBlank(text) ? defaultValue : text;
    }

    /** 取整数：兼容 {@link Number} 与可解析字符串；解析失败记日志返回 null。 */
    public static Integer getInteger(Map<String, ?> map, String key) {
        if (map == null || StrUtil.isBlank(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (StrUtil.isNotBlank(String.valueOf(value))) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception e) {
                log.warn("整数解析失败, key={}, value={}", key, value);
            }
        }
        return null;
    }

    /** 取小数：兼容 {@link Number} 与可解析字符串；解析失败记日志返回 null。 */
    public static BigDecimal getDecimal(Map<String, ?> map, String key) {
        if (map == null || StrUtil.isBlank(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (StrUtil.isNotBlank(String.valueOf(value))) {
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (Exception e) {
                log.warn("小数解析失败, key={}, value={}", key, value);
            }
        }
        return null;
    }

    /** 取布尔：缺失返回 {@code false}；兼容 {@link Boolean} 与字符串。 */
    public static Boolean getBoolean(Map<String, ?> map, String key) {
        if (map == null || StrUtil.isBlank(key)) {
            return false;
        }
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /** 返回第一个非空白字符串，全空返回 null。 */
    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /** 返回第一个非 null 值，全 null 返回 null。 */
    @SafeVarargs
    public static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
