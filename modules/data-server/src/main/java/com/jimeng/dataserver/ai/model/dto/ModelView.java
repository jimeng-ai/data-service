package com.jimeng.dataserver.ai.model.dto;

import lombok.Data;

/**
 * GET /data/admin/models 出参。
 * 用 @Data class 而非 record：Jackson 序列化 record 含非空集合会 500（见 jm-jackson-no-record-dto）。
 */
@Data
public class ModelView {
    private String value;
    private String label;
    private String provider;
    private Double maxTemp;
    private String description;
}
