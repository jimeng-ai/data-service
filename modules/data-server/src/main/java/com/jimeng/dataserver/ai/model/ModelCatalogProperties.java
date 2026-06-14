package com.jimeng.dataserver.ai.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选模型目录（Nacos: ai.model-catalog.models）。单一真相源：
 * 调试台模型下拉、构建器注入、draft_agent 校验共用。加模型只改这里。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.model-catalog")
public class ModelCatalogProperties {

    /** 模型条目列表 */
    private List<ModelEntry> models = new ArrayList<>();

    @Data
    public static class ModelEntry {
        /** 模型 id（透传给上游的 value，如 claude-sonnet-4-6） */
        private String value;
        /** 展示名（如 Claude Sonnet 4.6） */
        private String label;
        /** 厂商：anthropic / openai */
        private String provider;
        /** temperature 上限：anthropic=1，openai=2 */
        private Double maxTemp;
        /** 适用场景（给构建器模型选型用的一句话提示） */
        private String description;
        /** 是否启用（下线模型置 false，不删配置） */
        private boolean enabled = true;
    }
}
