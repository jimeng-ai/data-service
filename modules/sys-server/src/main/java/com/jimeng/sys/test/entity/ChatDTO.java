package com.jimeng.sys.test.entity;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2025/8/30 12:52
 */

@Data
public class ChatDTO {

    private String model;
    private List<Map<String, Object>> messages;
    private double temperature;
    private Boolean stream = false;

}
