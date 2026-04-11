package com.jimeng.dataserver.ai.claude.controller;

import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Claude消息管理", description = "Claude统一消息接口（文本、图片、文档、工具）")
@RestController
@RequestMapping("/data/claude")
@RequiredArgsConstructor
public class ClaudeController {

    private final ClaudeService claudeService;

    @Operation(summary = "Claude统一消息接口", description = "同一个接口支持文本、图片、文档和tools")
    @PostMapping("/messages")
    public Object messages(@RequestBody Map<String, Object> requestBody) {
        return claudeService.messages(requestBody);
    }
}
