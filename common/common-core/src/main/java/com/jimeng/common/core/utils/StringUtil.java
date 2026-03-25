package com.jimeng.common.core.utils;

import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/8/4 13:41
 */

public class StringUtil {

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
     * 将给定的字符串转换为JSON格式的字符串。
     *
     * @param inputStr 给定的字符串
     * @return 转换后的JSON格式的字符串
     */
    public static String convertStringToJson(String inputStr) {
        // 使用正则表达式分割字符串
        String[] pairs = inputStr.split(", ");
        Map<String, Object> map = new HashMap<>();

        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            String key = keyValue[0];
            String value = keyValue[1];

            // 将键值对添加到Map中
            map.put(convertToCamelCase(key), value);
        }

        // 将Map转换为JSON对象
        JSONObject jsonObject = new JSONObject(map);

        // 将JSON对象转换为JSON字符串
        return jsonObject.toJSONString();
    }

    /**
     * 随机生成指定位数的随机字符串
     * @param length
     * @return
     */
    public static String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        return ThreadLocalRandom.current()
                .ints(length, 0, chars.length())
                .mapToObj(chars::charAt)
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
    }

}
