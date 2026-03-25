package com.jimeng.common.core.utils;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/9/17 10:30
 */

public class MathUtil {

    // 计算相似度
    public static double computeSimilarity(Double[] vectorA, Double[] vectorB) {
        // 计算点积
        double dotProduct = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
        }
        // 计算向量A的范数
        double normA = 0.0;
        for (double value : vectorA) {
            normA += value * value;
        }
        normA = Math.sqrt(normA);
        // 计算向量B的范数
        double normB = 0.0;
        for (double value : vectorB) {
            normB += value * value;
        }
        normB = Math.sqrt(normB);
        // 计算余弦相似度
        if (normA != 0 && normB != 0) {
            return dotProduct / (normA * normB);
        } else {
            return 0.0; // 处理零向量的情况
        }
    }

    // 将List转换为Double数组
    public static Double[] convertToDoubleArray(List<Object> list) {
        Double[] result = new Double[list.size()];
        for (int i=0;i<list.size();i++){
            Object value = list.get(i);
            if (value instanceof Double) {
                result[i] = (Double) value;
            } else if (value instanceof Integer) {
                result[i] = Double.valueOf(((Integer) value).doubleValue());
            }
        }
        return result;
    }

}
