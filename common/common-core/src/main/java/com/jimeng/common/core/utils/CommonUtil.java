package com.jimeng.common.core.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.*;

/**
 * @Author Moonlight
 * @Description 通用工具类
 * @Date 2024/8/5 19:26
 */

public class CommonUtil {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 将对象转成Map
     *
     * @param t
     * @param <T>
     * @return
     */
    public static <T> Map<String, Object> objectToMap(T t) {
        if (t == null) {
            return new HashMap<>();
        }
        if (t instanceof String) {
            String raw = (String) t;
            if (raw.isBlank()) {
                return new HashMap<>();
            }
            try {
                return OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to parse string response to map.", e);
            }
        }
        return OBJECT_MAPPER.convertValue(t, MAP_TYPE);
    }

    /**
     * 获取类的所有字段（包括父类字段）
     *
     * @param clazz 类对象
     * @return 所有字段列表
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();

        // 递归获取所有父类的字段
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }

        return fields;
    }

    /**
     * 获取没有间隔符的uuid
     *
     * @return
     */
    public static String getUUID() {
        String uuid = UUID.randomUUID().toString();
        String[] split = uuid.split("-");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < split.length; i++) {
            result.append(split[i]);
        }
        return result.toString();
    }

    /**
     * 下划线或横杠命名法转换成驼峰命名法
     *
     * @param underscoreName
     * @return
     */
    public static String convertToCamelCase(String underscoreName) {
        StringBuilder result = new StringBuilder();
        boolean nextUpperCase = false;
        for (int i = 0; i < underscoreName.length(); i++) {
            char currentChar = underscoreName.charAt(i);
            if (currentChar == '_' || currentChar == '-') {
                nextUpperCase = true;
            } else {
                if (nextUpperCase) {
                    result.append(Character.toUpperCase(currentChar));
                    nextUpperCase = false;
                } else {
                    result.append(Character.toLowerCase(currentChar));
                }
            }
        }
        return result.toString();
    }

    /**
     * value 非空时才放入 map
     *
     * @param params 参数map
     * @param key 参数key
     * @param value 参数值
     */
    public static void putIfNotEmpty(Map<String, Object> params, String key, Object value) {
        if (params == null || key == null || value == null) {
            return;
        }
        if (value instanceof CharSequence && ((CharSequence) value).toString().isBlank()) {
            return;
        }
        if (value instanceof Collection<?> && ((Collection<?>) value).isEmpty()) {
            return;
        }
        if (value instanceof Map<?, ?> && ((Map<?, ?>) value).isEmpty()) {
            return;
        }
        if (value.getClass().isArray() && Array.getLength(value) == 0) {
            return;
        }
        params.put(key, value);
    }

}
