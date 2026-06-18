package com.jimeng.dataserver.ai.web;

import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置 web 工具的执行器。@Component 自动进 {@link com.jimeng.dataserver.ai.skill.service.SkillToolExecutorRegistryService}
 * 的 List<SkillToolExecutor>，executeAll 按 supports() 路由到这里。traceStepType 用默认 TOOL_CALL，自动记 Trace。
 */
@Component
@RequiredArgsConstructor
public class WebToolExecutor implements SkillToolExecutor {

    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";

    private final WebSearchService webSearchService;
    private final WebFetchService webFetchService;

    @Override
    public boolean supports(String toolName) {
        return WEB_SEARCH.equals(toolName) || WEB_FETCH.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        if (WEB_SEARCH.equals(toolName)) {
            String query = input == null ? null : str(input.get("query"));
            Integer count = input == null ? null : toInt(input.get("count"));
            return webSearchService.search(query, count);
        }
        if (WEB_FETCH.equals(toolName)) {
            String url = input == null ? null : str(input.get("url"));
            return webFetchService.fetch(url);
        }
        throw new IllegalArgumentException("unsupported web tool: " + toolName);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
