package com.jimeng.dataserver.ai.provider.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 顶层 AI 开关：ai.provider 决定 chat / embedding / rerank / contextualization 全部走哪家。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiSelectionProperties {

    /** 激活的 provider 名（必须与 providers.* 下的 key 一致）。 */
    private String provider;

    /** 全局 system prompt（保留现状，被聊天客户端读取）。 */
    private String systemPrompt;
}
