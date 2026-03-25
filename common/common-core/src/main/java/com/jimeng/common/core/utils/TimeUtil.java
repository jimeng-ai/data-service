package com.jimeng.common.core.utils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/3 20:46
 */

public class TimeUtil {

    /**
     * 获取指定格式的当前时间
     *
     * @param timeFormat
     * @return
     */
    public static String getCurrentTime(String timeFormat) {
        LocalTime currentTime = LocalTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(timeFormat);
        return currentTime.format(formatter);
    }

    /**
     * 计算时间戳差值
     *
     * @param startTime
     * @param endTime
     * @return
     */
    public static String calculateTimestampDiff(String startTime, String endTime) {
        Long start = Long.valueOf(startTime);
        Long end = Long.valueOf(endTime);
        return String.valueOf(end - start);
    }

}
