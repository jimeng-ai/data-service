package com.jimeng.common.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/11/22 21:44
 */

public interface ChatReqDTO {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    class ChatReq {
        private SseEmitter sseEmitter;
        // 消息列表
        private List<Message> messages;
        // 提示词
        private Prompt prompt;
        // 模型
        private String model;
        // 采样的概率
        private float topP;
        // 采样温度
        private float temperature;
        // 是否流式返回
        @lombok.Builder.Default
        private Boolean stream = false;
        // 允许模型生成的最大token
        private Integer maxToken;
        // 随机种子
        private Integer seed;
        // 是否开启联网搜索
        @lombok.Builder.Default
        private Boolean enableSearch = true;
        // 控制模型生成文本时的内容重复度
        private float presencePenalty;
        // 指定返回内容的格式
        private String responseFormat;
        // 启用流式输出时，可通过将本参数设置为{"include_usage": true}，在输出的最后一行显示所使用的Token数
        private String streamOptions;
    }

}
