package com.jimeng.dataserver.ai.connector.service;

import cn.hutool.json.JSONObject;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补全链（关系发现、业务文字）要一个 JSON 回来的那一次模型调用（数据星图设计 v3 §5.3、§6.2）。
 *
 * <p>和语义层推导同一个模型、同一种调法：{@code connector.semantic.infer-model}，经
 * {@link ClaudeService#messagesInternal}——不带任何工具（客户的表名列名不能被模型拿去 web_search）、只调一轮。
 * 超时沿用推导的 {@code connector.semantic.model-timeout-seconds}（{@link ConnectorSemanticDeriveService#modelTimeout}）。
 *
 * <p>这里不能并进 {@link ConnectorSemanticService}：那个类被 {@code ConnectorToolExecutor} 注入，
 * 碰到 {@code ClaudeService} 会闭合启动期的构造循环（见那个类的注释）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticModelCall {

    private final ClaudeService claudeService;
    private final ConnectorProperties properties;

    /** 留空即不下发 model，由 provider 回落到默认模型（同推导，见 {@code ConnectorSemanticDeriveService#inferModel}）。 */
    @Value("${connector.semantic.infer-model:}")
    private String inferModel;

    /** 本次会用的模型名；{@code null} = 没配，回落 provider 默认模型。业务视图把它记进 {@code model_code}。 */
    public String modelCode() {
        return inferModel == null || inferModel.isBlank() ? null : inferModel.trim();
    }

    /**
     * 调一次模型并把回复解析成一个 JSON 对象。
     *
     * @param what 日志开头那几个字（「关系发现」「业务领域」……）
     * @throws IllegalStateException 回复里找不到能解析的 JSON 对象。调用方按这一步失败处理，不要吞成「没有结果」
     */
    public Map<String, Object> askJson(String what, Long connectorId, String system, String user, int maxTokens) {
        // 必须用【可变】集合：下游 ModelResolver / GenericChatClient 会就地改写 body 与 messages，
        // Map.of / List.of 在那里抛 UnsupportedOperationException，而它的 getMessage() 是 null。
        Map<String, Object> body = new LinkedHashMap<>();
        String model = modelCode();
        if (model != null) {
            body.put("model", model);
        }
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", user);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);
        body.put("messages", messages);

        Duration timeout = ConnectorSemanticDeriveService.modelTimeout(properties.getSemantic());
        log.info("{} connectorId={} 请求模型={} 输入={}字符 max_tokens={} 超时={}秒", what, connectorId,
                model == null ? "(未配 connector.semantic.infer-model，回落 provider 默认模型)" : model,
                user == null ? 0 : user.length(), maxTokens, timeout.getSeconds());
        Object resp = claudeService.messagesInternal(body, timeout);
        return parse(extractText(resp));
    }

    /** 从 Anthropic messages 响应里抽 content[].text。与 {@code ConnectorSemanticDeriveService.extractText} 同形。 */
    private static String extractText(Object resp) {
        try {
            JSONObject j = new JSONObject(resp);
            var content = j.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; content != null && i < content.size(); i++) {
                JSONObject blk = content.getJSONObject(i);
                if ("text".equals(blk.getStr("type"))) {
                    sb.append(blk.getStr("text"));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(resp);
        }
    }

    /** 去掉 markdown 围栏和正文前的闲话；被 max_tokens 截断的收尾到最后一个完整条目（同推导的 {@code parseJson}）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end).trim();
            }
        }
        int a = s.indexOf('{');
        if (a < 0) {
            throw new IllegalStateException("模型回复里没有 JSON 对象：" + abbrev(raw));
        }
        s = s.substring(a);
        int fence = s.lastIndexOf("```");
        if (fence > 0) {
            s = s.substring(0, fence).trim();
        }
        try {
            return CommonUtil.getObjectMapper().readValue(s, Map.class);
        } catch (Exception first) {
            String repaired = ConnectorSemanticDeriveService.repairTruncatedJson(s);
            try {
                if (!repaired.equals(s)) {
                    log.warn("补全链的模型输出被截断，已收尾到最后一个完整条目：原长 {} 字符，修复后 {} 字符",
                            s.length(), repaired.length());
                    return CommonUtil.getObjectMapper().readValue(repaired, Map.class);
                }
            } catch (Exception ignored) {
                // 落到下面统一报错
            }
            throw new IllegalStateException("模型回复解析失败：" + abbrev(raw), first);
        }
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "(空)";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…(" + s.length() + ")";
    }
}
