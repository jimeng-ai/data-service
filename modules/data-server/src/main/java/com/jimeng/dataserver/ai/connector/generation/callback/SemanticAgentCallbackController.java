package com.jimeng.dataserver.ai.connector.generation.callback;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语义层 agent 的宿主回调。资源身份只取 {@link SemanticAgentScopeFilter} 写入的 principal；
 * 请求体只承载工具参数，没有 generationId / connectorId / tenantId / runId。
 */
@RestController
@RequestMapping("/data/internal/semantic-agent")
@RequiredArgsConstructor
public class SemanticAgentCallbackController {

    private final SemanticAgentCallbackService service;

    @PostMapping("/run-scope")
    public RunScopeView runScope(HttpServletRequest request,
                                 @RequestBody(required = false) Map<String, Object> ignoredBody) {
        return service.runScope(SemanticAgentTokens.requirePrincipal(request));
    }

    @PostMapping("/tables")
    public TableListView tables(HttpServletRequest request,
                                @RequestBody(required = false) TableListRequest body) {
        return service.listTables(SemanticAgentTokens.requirePrincipal(request), body);
    }

    @PostMapping("/table-metadata")
    public TableMetadataView tableMetadata(HttpServletRequest request,
                                           @RequestBody(required = false) TableMetadataRequest body) {
        return service.tableMetadata(SemanticAgentTokens.requirePrincipal(request), body);
    }

    @PostMapping("/submit")
    public SubmitResultView submit(HttpServletRequest request,
                                   @RequestBody(required = false) SubmitRequest body) {
        return service.submit(SemanticAgentTokens.requirePrincipal(request), body);
    }
}
