package com.jimeng.dataserver.ai.plugingen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** 固定请求头键值对（如 Content-Type: application/json）。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeaderKV {
    private String key;
    private String value;
}
