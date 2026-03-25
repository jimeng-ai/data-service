package com.jimeng.common.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/22 22:17
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Prompt {
    private String userPrompt;
    private String systemPrompt;
}
