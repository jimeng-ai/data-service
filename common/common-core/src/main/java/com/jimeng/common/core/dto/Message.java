package com.jimeng.common.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/22 21:53
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Message {

    private String role;
    private String content;

}
