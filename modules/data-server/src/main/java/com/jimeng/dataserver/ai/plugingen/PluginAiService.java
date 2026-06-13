package com.jimeng.dataserver.ai.plugingen;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugingen.dto.GenerateRequest;
import com.jimeng.dataserver.ai.plugingen.dto.ParamSpec;
import com.jimeng.dataserver.ai.plugingen.dto.PluginDraft;
import com.jimeng.dataserver.ai.plugingen.dto.RefineRequest;
import com.jimeng.dataserver.ai.plugingen.dto.RefineResponse;
import com.jimeng.dataserver.ai.plugingen.dto.ToolSpec;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.protocol.OpenAiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.config.AiSelectionProperties;
import com.jimeng.dataserver.ai.rag.model.BlockType;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParserRegistry;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 文档 → 插件草稿（{@link PluginDraft}）。
 *
 * <p>设计要点：<b>直接打 provider 的 /messages 或 /chat/completions</b>（仿
 * {@code DefaultContextualizationClient}），<b>不走 {@code ChatClient.chat()}</b>——因为后者会经
 * {@code AiConversationLoop.runBlocking} 触发 skill/插件工具注入与多轮工具循环，污染这次「一次性结构化抽取」。
 * 这里强制只调 {@code emit_plugin_draft} 工具，拿到 tool_use 的 input 即结果。
 *
 * <p>后处理保证编辑器约束：类型只 5 种、枚举=string+候选值、对象/数组强制 body、baseUrl 永远留空。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PluginAiService {

    private final AiSelectionProperties selection;
    private final AiProviderProperties providerProperties;
    private final RequestService requestService;
    private final ClaudeProtocolAdapter claudeAdapter;
    private final OpenAiProtocolAdapter openAiAdapter;
    private final PluginDraftToolSchema toolSchema;
    private final DocumentParserRegistry documentParserRegistry;
    private final AiModelCallRecordService recordService;

    /** 记账 biz_type（功能维度）：插件草稿生成 / 草稿对话式微调。 */
    private static final String BIZ_GEN = "plugin_gen";
    private static final String BIZ_REFINE = "plugin_refine";

    /** 文档过长时的字符预算（~20k tokens）；超出截断并加 warning。 */
    private static final int MAX_DOC_CHARS = 80_000;
    /** llms.txt 索引最多跟进抓取的 api-*.md 文档数。 */
    private static final int MAX_LINKED_DOCS = 40;
    /** 匹配 Markdown 里的 .md 文档链接（llms.txt 索引用）。 */
    private static final Pattern MD_LINK = Pattern.compile("https?://[^\\s)\\]]+?\\.md");

    private static final Set<String> PARAM_TYPES = Set.of("string", "number", "boolean", "object", "array");
    private static final Set<String> ITEM_TYPES = Set.of("string", "number", "boolean", "object");
    private static final Set<String> LOCATIONS = Set.of("query", "body", "path");
    private static final Set<String> AUTH_TYPES = Set.of("NONE", "API_KEY", "BEARER", "BASIC", "HMAC");

    private static final String GEN_SYSTEM_PROMPT = """
            你是「API 文档 → 插件工具」的转换器。读取用户提供的 API 文档（文本/Markdown/curl/OpenAPI/截图），
            抽取成结构化插件草稿，并且只能通过 emit_plugin_draft 工具输出，不要输出其它文字。

            最重要：把接口的【每一个请求参数】都完整列进该 tool 的 params——路径参数、查询参数、
            请求体(body)字段都要逐个列出；绝不能只填 outputs 而漏掉 params。即使只有一个参数也要列。

            规则：
            1. 每一个 HTTP 端点抽成一个 tool。tool.name 用英文 snake_case（只含 a-z、0-9、_，供 LLM 调用），
               tool.title 用简洁中文展示名（给人看，如「创建朋友圈回调」）。
            2. tool.method 用大写；tool.path 只填接口路径（如 /api/v2/wecom/moment/result），不要带域名。
            3. 参数类型只用 string / number / boolean / object / array 五种；integer 归为 number。
            4. 枚举值用 type=string + enumValues 表达，并把每个枚举值的业务含义写进该参数的 description
               （例如 scope：1=全部可见，2=部分可见）。不要用单独的 enum 类型。
            5. 嵌套对象用 type=object + fields；数组用 type=array + itemType（元素为对象再给 itemFields）。
            6. 传入位置 location：URL 路径里的 {占位} 用 path；GET 的查询条件用 query；
               POST/PUT/PATCH 的 JSON 请求体字段用 body。对象/数组类型一律用 body。拿不准时用 body 并写进该 tool 的 warnings。
            7. 有示例响应时，从中抽取主要返回字段填入 outputs（name + type，可含说明）；没有就留空。
            8. 绝不编造 Base URL 和任何密钥/Token。鉴权方式填到 plugin.auth（type/in/name/notes），
               密钥值一律留给人工，写进 auth.notes 提示。
            9. 任何推断、不确定、文档没写清楚的点，都写进该 tool 的 warnings。
            10. 只抽取文档里真实存在的端点，不要臆造。
            """;

    private static final String REFINE_SYSTEM_PROMPT = """
            你是插件草稿的修改助手。用户会给你当前的插件草稿（JSON）和一条修改指令。
            请按指令修改草稿，并通过 emit_plugin_draft 工具返回【完整的】更新后草稿（不是增量，未提及的内容保持不变）。
            遵循同样的约束：类型只用 string/number/boolean/object/array；枚举用 string+enumValues；
            对象/数组放 body；绝不编造 Base URL 与密钥。
            """;

    // ------------------------------------------------------------------ public API

    /** 由 JSON 入参（文本 / 截图 / 文档链接）生成草稿。 */
    public PluginDraft generate(GenerateRequest req, String traceId) {
        if (req == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求为空");
        }
        List<String> warnings = new ArrayList<>();
        String docText = req.getText();
        if (StrUtil.isBlank(docText) && req.getDocUrls() != null && !req.getDocUrls().isEmpty()) {
            // 「自动跑完整份文档」分批：直接抓这批接口文档、拼接
            docText = fetchDocs(req.getDocUrls(), warnings);
        }
        if (StrUtil.isBlank(docText) && StrUtil.isNotBlank(req.getDocUrl())) {
            docText = fetchUrl(req.getDocUrl(), warnings);
        }
        boolean hasImage = StrUtil.isNotBlank(req.getImageBase64());
        if (StrUtil.isBlank(docText) && !hasImage) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请提供 API 文档文本、截图或文档链接");
        }
        return callDraft(truncate(docText, warnings), req.getImageBase64(), req.getImageMediaType(), warnings, traceId);
    }

    /** 由已解析出的纯文本（上传文件经 DocumentParser 抽取后）生成草稿。 */
    public PluginDraft generateFromText(String docText, String traceId) {
        if (StrUtil.isBlank(docText)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文档内容为空，未解析出可用文本");
        }
        List<String> warnings = new ArrayList<>();
        return callDraft(truncate(docText, warnings), null, null, warnings, traceId);
    }

    /** 由上传的文件（PDF/Word/Markdown）解析出文本后生成草稿。 */
    public PluginDraft generateFromFile(InputStream in, String mime, String filename, String traceId) {
        ParsedDocument doc;
        try {
            doc = documentParserRegistry.resolve(mime, filename).parse(in, filename);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文件解析失败：" + e.getMessage());
        }
        return generateFromText(flatten(doc), traceId);
    }

    /** 对话式微调：当前草稿 + 指令 → 完整更新后草稿。 */
    public RefineResponse refine(RefineRequest req, String traceId) {
        if (req == null || req.getDraft() == null || StrUtil.isBlank(req.getInstruction())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "缺少 draft 或 instruction");
        }
        ProviderConfig cfg = activeConfig();
        boolean anthropic = isAnthropic(cfg);
        AiProtocolAdapter adapter = anthropic ? claudeAdapter : openAiAdapter;

        Map<String, Object> body = newBody(cfg, anthropic);
        List<Object> messages = new ArrayList<>();
        if (req.getHistory() != null) {
            for (RefineRequest.ChatTurn t : req.getHistory()) {
                if (t == null || StrUtil.isBlank(t.getText())) continue;
                messages.add(message("assistant".equalsIgnoreCase(t.getRole()) ? "assistant" : "user", t.getText()));
            }
        }
        String userText = "当前插件草稿(JSON)：\n" + JSONUtil.toJsonStr(req.getDraft())
                + "\n\n请按下面的指令修改，并通过 " + PluginDraftToolSchema.TOOL_NAME
                + " 返回【完整的】更新后草稿（未提及的保持不变）：\n" + req.getInstruction();
        messages.add(message("user", userText));
        body.put("messages", messages);
        adapter.appendSystemContent(body, REFINE_SYSTEM_PROMPT);
        body.put("tools", new ArrayList<>(List.of(toolSchema.buildEmitToolDef(cfg.getChat().getProtocol()))));
        toolSchema.forceTool(body, cfg.getChat().getProtocol());

        PluginDraft updated = sanitize(toDraft(invokeTool(cfg, anthropic, adapter, body, BIZ_REFINE)), null);
        int n = updated.getTools() == null ? 0 : updated.getTools().size();
        return new RefineResponse(updated, "已根据你的指令更新草稿（" + n + " 个工具），请在右侧核对。");
    }

    // ------------------------------------------------------------------ core call

    private PluginDraft callDraft(String docText, String imageBase64, String imageMediaType,
                                  List<String> extraWarnings, String traceId) {
        ProviderConfig cfg = activeConfig();
        boolean anthropic = isAnthropic(cfg);
        AiProtocolAdapter adapter = anthropic ? claudeAdapter : openAiAdapter;

        Map<String, Object> body = newBody(cfg, anthropic);
        List<Object> content = new ArrayList<>();
        boolean hasImage = StrUtil.isNotBlank(imageBase64);
        if (hasImage) content.add(imageBlock(anthropic, imageBase64, imageMediaType));
        content.add(textBlock("以下是客户提供的 API 文档" + (hasImage ? "（含截图）" : "")
                + "，请抽取成插件草稿：\n\n" + (docText == null ? "" : docText)));
        addUserMessage(body, content);
        adapter.appendSystemContent(body, GEN_SYSTEM_PROMPT);
        body.put("tools", new ArrayList<>(List.of(toolSchema.buildEmitToolDef(cfg.getChat().getProtocol()))));
        toolSchema.forceTool(body, cfg.getChat().getProtocol());

        log.info("plugin-gen 调用 provider={} model={} traceId={}", selection.getProvider(), cfg.getChat().getModel(), traceId);
        return sanitize(toDraft(invokeTool(cfg, anthropic, adapter, body, BIZ_GEN)), extraWarnings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeTool(ProviderConfig cfg, boolean anthropic, AiProtocolAdapter adapter,
                                           Map<String, Object> body, String bizType) {
        String url = StrUtil.removeSuffix(cfg.getBaseUrl(), "/") + endpointPath(cfg, anthropic);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + cfg.getApiKey());
        headers.put("Content-Type", "application/json");

        // 记账：插件生成/微调是一次性结构化抽取，直接打 provider（不走对话循环），这里自行落 ai_model_call_log。
        // 仿 DefaultContextualizationClient 用紧凑 meta 体（不含整篇文档/图片的大 body），response 含 usage 由
        // RecordService 解析算 cost。记账任何异常都不得影响主流程。
        Long logId = safeRecordRequest(body, headers, url, bizType, cfg.getChat().getModel());
        long startMs = System.currentTimeMillis();

        RequestService.HttpResp resp;
        try {
            resp = requestService.post(url, headers, Map.of(), body);
        } catch (Exception e) {
            safeRecordException(logId, e, startMs);
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "调用 AI 失败：" + e.getMessage());
        }
        safeRecordResponse(logId, resp.getStatusCode(), resp.getBody(), startMs);
        Integer status = resp.getStatusCode();
        if (status == null || status < 200 || status >= 300) {
            log.warn("plugin-gen AI 调用失败 status={} body={}", status, resp.getBody());
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "AI 生成失败：HTTP " + status);
        }
        Map<String, Object> root;
        try {
            root = CommonUtil.getObjectMapper().readValue(resp.getBody(), Map.class);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "AI 响应解析失败");
        }
        List<ToolUseCall> calls = adapter.extractToolUseCalls(root);
        if (calls.isEmpty() || calls.get(0).getInput() == null || calls.get(0).getInput().isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "AI 未返回结构化结果，请重试或更换输入");
        }
        return calls.get(0).getInput();
    }

    // ------------------------------------------------------------------ 记账（best-effort，绝不影响主流程）

    /** 落紧凑 meta 体（不含大 body）；biz_type=plugin_gen/plugin_refine，scene_code=provider。 */
    private Long safeRecordRequest(Map<String, Object> body, Map<String, String> headers,
                                   String url, String bizType, String model) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("model", model);
            meta.put("max_tokens", body.get("max_tokens"));
            meta.put("max_completion_tokens", body.get("max_completion_tokens"));
            meta.put("biz_type", bizType);
            meta.put("scene_code", selection.getProvider());
            return recordService.recordRequest(meta, headers, selection.getProvider(), url, model);
        } catch (Exception e) {
            log.warn("plugin-gen 计费 recordRequest 失败: {}", e.getMessage());
            return null;
        }
    }

    private void safeRecordResponse(Long logId, Integer status, String respBody, long startMs) {
        if (logId == null) return;
        try {
            recordService.recordResponse(logId, status, respBody, (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("plugin-gen 计费 recordResponse 失败: {}", e.getMessage());
        }
    }

    private void safeRecordException(Long logId, Throwable t, long startMs) {
        if (logId == null) return;
        try {
            recordService.recordException(logId, t, (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("plugin-gen 计费 recordException 失败: {}", e.getMessage());
        }
    }

    private PluginDraft toDraft(Map<String, Object> input) {
        try {
            return CommonUtil.getObjectMapper().convertValue(input, PluginDraft.class);
        } catch (Exception e) {
            log.warn("plugin-gen 草稿反序列化失败: {}", e.getMessage());
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "AI 返回的结构无法解析为插件草稿");
        }
    }

    // ------------------------------------------------------------------ sanitize

    private PluginDraft sanitize(PluginDraft draft, List<String> extraWarnings) {
        if (draft == null) draft = new PluginDraft();
        if (draft.getPlugin() == null) draft.setPlugin(new PluginDraft.PluginMeta());
        draft.getPlugin().setBaseUrl(null); // 永不让 AI 填域名，留给人工
        // 归一 auth.type 到允许集合（模型偶尔回 "unknown" 之类）
        PluginDraft.AuthMeta auth = draft.getPlugin().getAuth();
        if (auth != null) {
            String at = auth.getType() == null ? "" : auth.getType().trim().toUpperCase();
            auth.setType(AUTH_TYPES.contains(at) ? at : "NONE");
        }

        List<String> w = draft.getWarnings() == null ? new ArrayList<>() : new ArrayList<>(draft.getWarnings());
        if (extraWarnings != null) w.addAll(extraWarnings);
        draft.setWarnings(w.isEmpty() ? null : w);

        List<ToolSpec> kept = new ArrayList<>();
        List<ToolSpec> tools = draft.getTools() == null ? List.of() : draft.getTools();
        for (ToolSpec t : tools) {
            if (t == null || StrUtil.isBlank(t.getName())) continue;
            // 工具名预清洗：作为 LLM 函数名只允许 [a-zA-Z0-9_-]，把模型偶尔吐出的中文/符号洗成 _，
            // 让草稿一回来就是干净的（前端只做即时提示，写入由后端 validateToolName 把关）。
            String normalized = normalizeToolName(t.getName());
            if (!normalized.equals(t.getName())) {
                List<String> tw = t.getWarnings() == null ? new ArrayList<>() : new ArrayList<>(t.getWarnings());
                tw.add("工具名已自动规整为「" + normalized + "」（原「" + t.getName()
                        + "」含非法字符；工具名只能用英文字母/数字/_/-，中文名请填 title）");
                t.setWarnings(tw);
                t.setName(normalized);
            }
            if (StrUtil.isNotBlank(t.getMethod())) t.setMethod(t.getMethod().trim().toUpperCase());
            t.setParams(sanitizeParams(t.getParams()));
            kept.add(t);
        }
        draft.setTools(kept);
        return draft;
    }

    /** 与 {@code SkillToolDefinition.normalizeModelName} 对齐：非 [a-zA-Z0-9_-] 字符替换为 _。 */
    private static String normalizeToolName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(valid ? c : '_');
        }
        return sb.toString();
    }

    private List<ParamSpec> sanitizeParams(List<ParamSpec> params) {
        if (params == null) return null;
        List<ParamSpec> out = new ArrayList<>();
        for (ParamSpec p : params) {
            if (p == null || StrUtil.isBlank(p.getName())) continue;
            String type = normType(p.getType());
            p.setType(type);
            // 候选值只对 string 有意义
            if (!"string".equals(type)) p.setEnumValues(null);
            // 对象/数组只能进 body
            if ("object".equals(type) || "array".equals(type)) {
                p.setLocation("body");
            } else {
                p.setLocation(normLocation(p.getLocation()));
            }
            if ("object".equals(type)) {
                p.setFields(sanitizeParams(p.getFields()));
                p.setItemType(null);
                p.setItemFields(null);
            } else if ("array".equals(type)) {
                String it = normItemType(p.getItemType());
                p.setItemType(it);
                p.setItemFields("object".equals(it) ? sanitizeParams(p.getItemFields()) : null);
                p.setFields(null);
            } else {
                p.setFields(null);
                p.setItemType(null);
                p.setItemFields(null);
            }
            out.add(p);
        }
        return out;
    }

    private String normType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        if ("integer".equals(t)) return "number";
        return PARAM_TYPES.contains(t) ? t : "string";
    }

    private String normItemType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        if ("integer".equals(t)) return "number";
        return ITEM_TYPES.contains(t) ? t : "string";
    }

    private String normLocation(String loc) {
        String l = loc == null ? "" : loc.trim().toLowerCase();
        return LOCATIONS.contains(l) ? l : "query";
    }

    // ------------------------------------------------------------------ helpers

    private ProviderConfig activeConfig() {
        String active = selection.getProvider();
        ProviderConfig cfg = providerProperties.getProviders().get(active);
        if (cfg == null || cfg.getChat() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "AI provider 未正确配置: " + active);
        }
        return cfg;
    }

    private boolean isAnthropic(ProviderConfig cfg) {
        return "anthropic".equalsIgnoreCase(cfg.getChat().getProtocol());
    }

    private String endpointPath(ProviderConfig cfg, boolean anthropic) {
        String p = cfg.getChat().getEndpointPath();
        if (StrUtil.isNotBlank(p)) return p;
        return anthropic ? "/messages" : "/chat/completions";
    }

    private Map<String, Object> newBody(ProviderConfig cfg, boolean anthropic) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getChat().getModel());
        int max = cfg.getChat().getMaxTokens() > 0 ? cfg.getChat().getMaxTokens() : 8192;
        if (anthropic) body.put("max_tokens", max);
        else body.put("max_completion_tokens", max);
        return body;
    }

    private void addUserMessage(Map<String, Object> body, List<Object> content) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", content);
        List<Object> messages = new ArrayList<>();
        messages.add(user);
        body.put("messages", messages);
    }

    private Map<String, Object> textBlock(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "text");
        b.put("text", text);
        return b;
    }

    private Map<String, Object> imageBlock(boolean anthropic, String base64, String mediaType) {
        String mime = StrUtil.isBlank(mediaType) ? "image/png" : mediaType;
        if (anthropic) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "base64");
            source.put("media_type", mime);
            source.put("data", base64);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("type", "image");
            b.put("source", source);
            return b;
        }
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("url", "data:" + mime + ";base64," + base64);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "image_url");
        b.put("image_url", img);
        return b;
    }

    private Map<String, Object> message(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text);
        return m;
    }

    private String truncate(String text, List<String> warnings) {
        if (text == null) return "";
        if (text.length() <= MAX_DOC_CHARS) return text;
        warnings.add("文档过长（" + text.length() + " 字符）已截断到 " + MAX_DOC_CHARS + " 字符，可能漏掉部分接口");
        return text.substring(0, MAX_DOC_CHARS);
    }

    /** 把解析结果拼成带标题面包屑的纯文本（跳过图片块）。 */
    private String flatten(ParsedDocument doc) {
        if (doc == null || doc.getBlocks() == null) return "";
        StringBuilder sb = new StringBuilder();
        String lastHeading = null;
        for (DocumentBlock b : doc.getBlocks()) {
            if (b == null || b.getType() == BlockType.IMAGE || StrUtil.isBlank(b.getText())) continue;
            List<String> hp = b.getHeadingPath();
            String heading = (hp == null || hp.isEmpty()) ? null : String.join(" > ", hp);
            if (heading != null && !heading.equals(lastHeading)) {
                sb.append("\n## ").append(heading).append("\n");
                lastHeading = heading;
            }
            sb.append(b.getText()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 抓取文档链接。若是 llms.txt 之类的「索引」（只列出指向各 .md 文档的链接），
     * 则跟进抓取其中的 api-*.md（单接口 OpenAPI 规格）并拼接——否则直接用原文。
     */
    private String fetchUrl(String url, List<String> warnings) {
        String content = doGet(url.trim());
        if (StrUtil.isBlank(content)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文档链接返回空内容");
        }
        List<String> mdLinks = extractMdLinks(content);
        boolean isIndex = url.toLowerCase().endsWith("llms.txt") || mdLinks.size() >= 5;
        if (!isIndex) return content;

        // 真正的接口规格在 api-*.md 里；索引本身只有标题/说明，抽不出工具
        List<String> apiLinks = mdLinks.stream().filter(l -> l.contains("/api-")).distinct().toList();
        if (apiLinks.isEmpty()) return content;

        StringBuilder sb = new StringBuilder("# 来自 llms.txt 索引的 API 文档\n\n");
        int fetched = 0;
        for (String link : apiLinks) {
            if (fetched >= MAX_LINKED_DOCS || sb.length() >= MAX_DOC_CHARS) break;
            try {
                String doc = doGet(link);
                if (StrUtil.isNotBlank(doc)) {
                    sb.append(doc).append("\n\n---\n\n");
                    fetched++;
                }
            } catch (Exception ignore) {
                // 单个文档抓取失败不影响整体
            }
        }
        if (fetched == 0) return content;
        if (fetched < apiLinks.size()) {
            warnings.add("该 API 文档共 " + apiLinks.size() + " 个接口，受长度限制本次只解析了前 "
                    + fetched + " 个；其余请分批生成，或单独粘贴某接口的文档链接");
        }
        return sb.toString();
    }

    /** 列出某个文档索引（llms.txt 等）里的全部接口文档链接（api-*.md），供前端分批。 */
    public List<String> listEndpointLinks(String url) {
        String content = doGet(url.trim());
        if (StrUtil.isBlank(content)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文档链接返回空内容");
        }
        return extractMdLinks(content).stream().filter(l -> l.contains("/api-")).distinct().toList();
    }

    /** 抓取一批接口文档链接并拼接（受字符预算限制）。 */
    private String fetchDocs(List<String> urls, List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String u : urls) {
            if (StrUtil.isBlank(u) || sb.length() >= MAX_DOC_CHARS) break;
            try {
                String doc = doGet(u.trim());
                if (StrUtil.isNotBlank(doc)) {
                    sb.append(doc).append("\n\n---\n\n");
                    n++;
                }
            } catch (Exception ignore) {
                // 单个文档抓取失败不影响整体
            }
        }
        if (n == 0) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未能抓取到任何接口文档内容");
        }
        return sb.toString();
    }

    private String doGet(String url) {
        try {
            RequestService.HttpResp resp = requestService.get(url, null, Map.of());
            Integer status = resp.getStatusCode();
            if (status == null || status < 200 || status >= 300) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "抓取链接失败：HTTP " + status + "（" + url + "）");
            }
            return resp.getBody();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "抓取链接失败：" + e.getMessage() + "（本机外网受限时请改用粘贴文本/上传文件）");
        }
    }

    private List<String> extractMdLinks(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        Matcher m = MD_LINK.matcher(content);
        while (m.find()) out.add(m.group());
        return out;
    }
}
