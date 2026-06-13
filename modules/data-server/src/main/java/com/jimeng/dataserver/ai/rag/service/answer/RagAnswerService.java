package com.jimeng.dataserver.ai.rag.service.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.run.RunEventTee;
import com.jimeng.dataserver.ai.run.RunFinalizer;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.agent.runtime.AgentIdContext;
import com.jimeng.dataserver.ai.billing.BizTypeContext;
import com.jimeng.dataserver.ai.agent.service.AgentRuntimeService;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.AnswerRequest;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.CitationAssembler;
import com.jimeng.dataserver.ai.rag.service.search.HybridSearchService;
import com.jimeng.dataserver.ai.rag.service.search.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 问答：检索 → 拼上下文 → 复用 ClaudeService 流式输出。
 *
 * <p>检索命中的片段以「参考来源」形式由前端在回答下方单独展示（按文档聚合）；
 * 系统提示明确禁止模型在正文里输出 chunk_id / 引用编号等标记。
 *
 * <p>关键观测点（INFO 日志，可在 Kibana 中按 MDC.connectionId 关联同一次请求）：
 * <ul>
 *   <li>RAG/retrieve.start — 查询入参（kb / query / topK / docIds / rerank）</li>
 *   <li>RAG/retrieve.rrf    — RRF 融合后候选（chunkId + score + 内容截断 80 字）</li>
 *   <li>RAG/retrieve.rerank — Rerank 精排后命中（同上 + rerankScore）</li>
 *   <li>RAG/answer.prompt   — 真正喂给 LLM 的 user 消息长度</li>
 *   <li>LLM 答复文本由 AiConversationLoop 在流结束时打印</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private static final int CHUNK_PREVIEW_CHARS = 80;

    private static final String SYSTEM_PROMPT =
            "你是一个基于企业知识库的问答助手。请严格遵守：\n"
                    + "1) 仅根据下方提供的【知识片段】回答问题，不要编造未在片段中出现的信息；\n"
                    + "2) 直接用自然语言作答，不要在回答正文里输出任何 chunk_id、片段编号、引用编号或方括号标签；\n"
                    + "   （引用来源会由系统在回答下方单独展示，无需你在正文中标注。）\n"
                    + "3) 如果片段不足以回答，明确告知\"未在知识库中找到相关信息\"；\n"
                    + "4) 回答中文且简洁，必要时分点。";

    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final ClaudeService claudeService;
    private final RagProperties ragProperties;
    private final RunEventTee tee;
    private final RunFinalizer runFinalizer;
    private final AgentRuntimeService agentRuntimeService;
    private final CitationAssembler citationAssembler;

    public List<SearchResultItem> retrieve(AnswerRequest req) throws Exception {
        int topK = req.getTopK() != null ? req.getTopK() : ragProperties.getRetrieval().getRerankTopK();
        int window = Math.max(ragProperties.getRetrieval().getBm25TopK(), ragProperties.getRetrieval().getVectorTopK());
        boolean doRerank = req.getRerank() == null || req.getRerank();

        log.info("RAG/retrieve.start kbId={} topK={} rerank={} docIds={} query={}",
                req.getKbId(), topK, doRerank, req.getDocIds(), req.getQuery());

        List<SearchResultItem> rrf = hybridSearchService.search(
                req.getKbId(), req.getQuery(), req.getDocIds(), window);
        logCandidates("RAG/retrieve.rrf", rrf, /*rerankPhase*/ false);
        if (rrf.isEmpty()) return rrf;

        if (!doRerank) {
            return rrf.subList(0, Math.min(topK, rrf.size()));
        }
        List<SearchResultItem> rerankHits = rerankService.rerank(req.getQuery(), rrf, topK);
        logCandidates("RAG/retrieve.rerank", rerankHits, /*rerankPhase*/ true);
        return rerankHits;
    }

    /**
     * 解析本次检索计划：知识库范围 + 生效的 topK / 是否精排。
     *
     * <p>① 显式 kbId 优先；② 否则取 Agent 绑定的知识库；③ 都没有则返回空 kbIds（=不走 RAG，纯对话）。
     * <p>topK / rerank 的生效优先级：<b>请求显式传参 &gt; Agent「知识库绑定」配置 &gt; 全局默认</b>。
     * 真实对话页不传 topK/rerank，因此 Agent 绑定里配的 rerank 开关、topK 在此生效。
     */
    private RagPlan resolveRagPlan(AnswerRequest req) {
        if (req.getKbId() != null) {
            // 显式 kbId（如检索调试/直连）：无 Agent 绑定，故不带相似度阈值。
            return new RagPlan(List.of(req.getKbId()),
                    effectiveTopK(req.getTopK(), null),
                    effectiveRerank(req.getRerank(), null),
                    null);
        }
        if (StrUtil.isNotBlank(req.getAgentId())) {
            Long agentId = parseAgentId(req.getAgentId());
            if (agentId != null) {
                try {
                    AgentRuntimeView view = agentRuntimeService.byId(agentId, req.isPreview());
                    if (view.getKbIds() != null && !view.getKbIds().isEmpty()) {
                        return new RagPlan(new ArrayList<>(view.getKbIds()),
                                effectiveTopK(req.getTopK(), view.getKbTopK()),
                                effectiveRerank(req.getRerank(), view.getKbRerank()),
                                view.getKbScoreThreshold());
                    }
                } catch (Exception e) {
                    log.warn("加载 Agent 知识库绑定失败 agentId={}: {}", req.getAgentId(), e.getMessage());
                }
            }
        }
        return new RagPlan(List.of(), 0, true, null);
    }

    /** 本次检索计划：知识库范围 + 生效 topK + 是否精排 + 相似度阈值（可空，来自 Agent 绑定）。 */
    private record RagPlan(List<Long> kbIds, int topK, boolean rerank, Double threshold) {}

    private int effectiveTopK(Integer reqTopK, Integer bindingTopK) {
        if (reqTopK != null) return reqTopK;
        if (bindingTopK != null) return bindingTopK;
        return ragProperties.getRetrieval().getRerankTopK();
    }

    private boolean effectiveRerank(Boolean reqRerank, Boolean bindingRerank) {
        if (reqRerank != null) return reqRerank;
        if (bindingRerank != null) return bindingRerank;
        return true;
    }

    private Long parseAgentId(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 跨一个或多个知识库检索：逐库召回后合并，再按检索计划统一精排取 topK。 */
    private List<SearchResultItem> retrieveForKbs(AnswerRequest req, RagPlan plan) throws Exception {
        int topK = plan.topK();
        int window = Math.max(ragProperties.getRetrieval().getBm25TopK(), ragProperties.getRetrieval().getVectorTopK());
        boolean doRerank = plan.rerank();

        log.info("RAG/retrieve.start kbIds={} topK={} rerank={} docIds={} query={}",
                plan.kbIds(), topK, doRerank, req.getDocIds(), req.getQuery());

        List<SearchResultItem> merged = new ArrayList<>();
        for (Long kbId : plan.kbIds()) {
            merged.addAll(hybridSearchService.search(kbId, req.getQuery(), req.getDocIds(), window));
        }
        logCandidates("RAG/retrieve.rrf", merged, /*rerankPhase*/ false);
        if (merged.isEmpty()) return merged;

        if (!doRerank) {
            merged.sort(Comparator.comparingDouble(
                    (SearchResultItem c) -> c.getRrfScore() == null ? 0d : c.getRrfScore()).reversed());
            return applyThreshold(merged.subList(0, Math.min(topK, merged.size())), plan);
        }
        List<SearchResultItem> rerankHits = rerankService.rerank(req.getQuery(), merged, topK);
        logCandidates("RAG/retrieve.rerank", rerankHits, /*rerankPhase*/ true);
        return applyThreshold(rerankHits, plan);
    }

    /**
     * 相似度阈值过滤：丢掉分值低于阈值的片段。
     *
     * <p>阈值是 0~1 的「相似度」，只有 <b>rerank 精排分（rerankScore∈[0,1]）</b>量纲与之匹配；
     * 未开 rerank 时命中分是 RRF 融合分（量级极小 ~0.0x），用同一阈值会误杀全部，故此时跳过过滤并记日志。
     * 阈值来自 Agent「知识库绑定」，显式 kbId 调试路径无阈值（threshold=null 即不过滤）。
     */
    private List<SearchResultItem> applyThreshold(List<SearchResultItem> items, RagPlan plan) {
        Double threshold = plan.threshold();
        if (threshold == null || threshold <= 0 || items.isEmpty()) {
            return items;
        }
        if (!plan.rerank()) {
            log.info("RAG/retrieve.threshold 跳过：未启用 rerank，RRF 融合分与相似度阈值量纲不一致 threshold={}", threshold);
            return items;
        }
        List<SearchResultItem> kept = items.stream()
                .filter(c -> c.getScore() != null && c.getScore() >= threshold)
                .collect(java.util.stream.Collectors.toList());
        log.info("RAG/retrieve.threshold threshold={} 过滤前={} 过滤后={}", threshold, items.size(), kept.size());
        return kept;
    }

    public void streamAnswer(AnswerRequest req, String connectionId, String traceId) {
        // 标注本次模型调用的功能（运营平台按功能统计），供 AiModelCallRecordService 落库 biz_type：
        // 默认 chat（纯对话）；下方若本次对话挂了知识库（显式 kbId 或 Agent 绑定 KB）→ 升级为 rag_answer。
        // 口径说明：Agent 绑定 KB 时「是否真的检索」由模型在对话循环里临时决定，请求阶段无法得知，
        // 故以「本次对话是否挂了 KB」为准（挂了即算知识库问答）。本方法跑在 MdcAsyncSupport.wrap 的
        // executor 线程上，其 finally 会统一 clear BizTypeContext。
        BizTypeContext.set(BizTypeContext.DEFAULT_CHAT);
        try {
            // RAG 检索策略（按入参区分两条路）：
            //  · 显式 kbId（调试台 Playground「挂载知识库测试」直连）→ 保留「前置强制检索」语义：就针对该库检索并前置发 citations。
            //  · 仅 Agent 绑定知识库（普通对话，无显式 kbId）→ 不再前置强制检索，改由模型在对话循环里按需调用 rag.search（工具式 B）：
            //    ClaudeService.applyAgentContext 给绑定 KB 的 Agent 注入检索护栏（必检索/零编造/kb_id 已给定），
            //    SkillRuntimeService 把 rag-knowledge 直注成可立即调用的工具，
            //    citations 由工具结果经 ToolExecutionResult.CITATIONS_SIDECAR_KEY 旁路在工具轮后上抛（AiConversationLoop）。
            // 解析检索计划（显式 kbId / Agent 绑定 KB / 都没有=纯对话）；挂了 KB 即把功能改记为知识库问答。
            RagPlan plan = resolveRagPlan(req);
            if (!plan.kbIds().isEmpty()) {
                BizTypeContext.set(BizTypeContext.RAG_ANSWER);
            }
            boolean forcedKbRetrieve = req.getKbId() != null;
            List<SearchResultItem> hits = List.of();
            if (forcedKbRetrieve) {
                hits = retrieveForKbs(req, plan);
                tee.tee(connectionId, "citations", JSONUtil.toJsonStr(citationAssembler.assemble(hits)));
            }

            Map<String, Object> body = buildClaudeBody(req, hits, forcedKbRetrieve);

            // 带 agentId 时加载 Agent 人设/模型/插件/知识库上下文：
            // prepareAgentContext 前置注入 systemPrompt（含绑定 KB 的检索护栏）、补默认 model/params，
            // 并把 AgentContext 设到当前线程（下游 rag.search 据此兜底 kb_id、插件据此判断可见工具）。
            if (StrUtil.isNotBlank(req.getAgentId())) {
                body.put("agent_id", req.getAgentId());
                // preview=调试台读实时草稿；缺省=对话端只读已发布快照（未发布时 prepareAgentContext 抛错 → 走外层 catch 发 error 事件）。
                body.put("agent_preview", req.isPreview());
                // 调试台同时传了显式 kbId（已前置强制检索该库）时，抑制 Agent 自身绑定 KB 的检索护栏，
                // 避免「针对显式库的前置检索」与「指示模型再去检索 Agent 绑定库」两套指令在 Playground 场景打架。
                if (forcedKbRetrieve) body.put("__suppress_kb_grounding__", Boolean.TRUE);
                // agent_id 会被 ClaudeService.applyAgentContext 从 body 移除，导致日志层读不到；
                // 这里同时记到 ThreadLocal，供 AiModelCallRecordService 落库时兜底（含本异步流式线程）。
                AgentIdContext.set(req.getAgentId());
                claudeService.prepareAgentContext(body);
            } else {
                // 无 agentId：清掉可能由 MdcAsyncSupport 从（被线程池复用的）请求线程继承来的 Agent 上下文，
                // 否则下游 applySkillContext / RagSkillToolExecutor.resolveKbId 会误读上一个请求残留的 Agent
                // 绑定（跨请求、甚至跨租户串知识库/插件可见性）；AgentIdContext 同理清掉，避免落库日志记成上个 agent。
                AgentContext.clear();
                AgentIdContext.clear();
            }

            log.info("RAG/answer.prompt forcedKbRetrieve={} agentId={} citations={} totalChunkChars={} historyTurns={}",
                    forcedKbRetrieve, req.getAgentId(), hits.size(), totalContentChars(hits),
                    req.getHistory() == null ? 0 : req.getHistory().size());

            claudeService.messagesStream(body, connectionId, traceId);
        } catch (Exception e) {
            log.error("RAG 回答失败 connectionId={}", connectionId, e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            err.put("message", e.getMessage());
            try {
                tee.teeJson(connectionId, "error", err);
                runFinalizer.complete(connectionId);
            } catch (Exception ignore) {}
        }
    }

    private Map<String, Object> buildClaudeBody(AnswerRequest req, List<SearchResultItem> citations, boolean useRag) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stream", true);
        // 仅在走 RAG 时注入知识库问答的系统提示（含引用标签规则）；
        // 纯对话场景不设 system，交由 Agent 的 systemPrompt 注入（prepareAgentContext）。
        if (useRag) {
            body.put("system", SYSTEM_PROMPT);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        if (req.getHistory() != null) messages.addAll(req.getHistory());

        String userContent;
        if (useRag) {
            StringBuilder ctx = new StringBuilder();
            ctx.append("【知识片段】\n");
            // 片段只编内部序号供模型组织答案，不再外露 chunk_id；系统提示已禁止模型把任何编号写进正文。
            int idx = 1;
            for (SearchResultItem c : citations) {
                ctx.append("\n【资料").append(idx++).append('】');
                if (c.getHeadingPath() != null) ctx.append(" 章节: ").append(c.getHeadingPath());
                if (c.getPageNum() != null) ctx.append(" 页码: ").append(c.getPageNum());
                ctx.append('\n').append(c.getContent() == null ? "" : c.getContent()).append('\n');
            }
            ctx.append("\n【问题】\n").append(req.getQuery());
            userContent = ctx.toString();
        } else {
            // 纯对话：直接把用户问题作为消息内容。
            userContent = req.getQuery();
        }

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        messages.add(userMsg);

        body.put("messages", messages);
        return body;
    }

    private void logCandidates(String tag, List<SearchResultItem> items, boolean rerankPhase) {
        if (items == null || items.isEmpty()) {
            log.info("{} 命中=0", tag);
            return;
        }
        StringBuilder sb = new StringBuilder(items.size() * 120);
        sb.append(tag).append(" 命中=").append(items.size()).append('\n');
        for (int i = 0; i < items.size(); i++) {
            SearchResultItem c = items.get(i);
            sb.append("  ").append(i + 1).append(". chunk_id=").append(c.getChunkId());
            sb.append(" doc_id=").append(c.getDocId());
            if (rerankPhase && c.getRerankScore() != null) {
                sb.append(String.format(" rerank=%.4f", c.getRerankScore()));
            }
            if (c.getRrfScore() != null) {
                sb.append(String.format(" rrf=%.4f", c.getRrfScore()));
            }
            if (StrUtil.isNotBlank(c.getHeadingPath())) {
                sb.append(" 章节=").append(c.getHeadingPath());
            }
            if (c.getPageNum() != null) sb.append(" 页=").append(c.getPageNum());
            sb.append(" content=").append(preview(c.getContent()));
            sb.append('\n');
        }
        log.info(sb.toString());
    }

    private static String preview(String content) {
        if (content == null) return "<null>";
        String trimmed = content.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= CHUNK_PREVIEW_CHARS) return trimmed;
        return trimmed.substring(0, CHUNK_PREVIEW_CHARS) + "…";
    }

    private static int totalContentChars(List<SearchResultItem> items) {
        int n = 0;
        for (SearchResultItem c : items) if (c.getContent() != null) n += c.getContent().length();
        return n;
    }
}
