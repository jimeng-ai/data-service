package com.jimeng.dataserver.ai.provider.spi;

/**
 * 聊天客户端的能力声明。供上层做协议匹配校验与 prompt cache 决策。
 *
 * @param protocol         "anthropic" 或 "openai"，决定请求体结构与端点
 * @param supportsPromptCache 是否支持 Anthropic ephemeral prompt cache（仅 anthropic 协议）
 * @param providerName     provider 名称（bean 名），用于日志与错误信息
 * @param model            激活的默认模型 ID
 */
public record ChatCapabilities(String protocol, boolean supportsPromptCache, String providerName, String model) {
}
