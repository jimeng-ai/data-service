package com.jimeng.dataserver.ai.skill.eval;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.SkillEvalRun;
import com.jimeng.persistence.mapper.SkillEvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Skill 评测：拿真沙箱跑一组用例，再让模型当评委逐条判定。
 *
 * <h3>两种模式，测的是不同的东西</h3>
 * <ul>
 *   <li><b>RECALL</b>：提示词就是用户原话，<b>绝不提 skill 名</b>。测的是"模型会不会自己想到用"。
 *       这是本平台最需要的一类——description 写不好，skill 就等于不存在，
 *       而且请求 200、回复正常，<b>完全静默</b>。</li>
 *   <li><b>CAPABILITY</b>：明确要求用这个 skill。测的是"用了之后做得对不对"。
 *       等价于既有 {@code SkillBuilderRunService.testRun} 的语义。</li>
 * </ul>
 *
 * <h3>为什么串行跑、异步返回</h3>
 * 沙箱准入是单信号量（{@code maxConcurrency} 默认 3），且 {@code queueWaitMs} 必须小于
 * data-service 的读超时。并发跑用例会直接把真实用户的对话挤进队列甚至挤掉；
 * 同步等更不行——跑到第 3、4 个用例就超过 HTTP 超时了。所以：提交即返回 runId，
 * 后台串行跑，前端轮询进度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillEvalService {

    public static final String MODE_RECALL = "RECALL";
    public static final String MODE_CAPABILITY = "CAPABILITY";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    /** 单条 transcript 的字符上限。评委是一次 LLM 调用，不封顶会把上下文撑爆。 */
    private static final int MAX_TRANSCRIPT_CHARS = 24_000;

    private final SkillEvalRunMapper evalRunMapper;
    private final SkillMaterializer materializer;
    private final SidecarClient sidecarClient;
    private final AgentSandboxProperties sandboxProps;
    private final ClaudeService claudeService;
    private final ThreadPoolTaskExecutor streamExecutor;

    /** 评委模型。留空则回落 sandboxProps 的 model；建议在 Nacos 配一个 ai_model 表里 enabled=1 的值。 */
    @org.springframework.beans.factory.annotation.Value("${skill.eval.grader-model:}")
    private String graderModel;

    /**
     * 提交一轮评测，立即返回记录（status=RUNNING）。用例在后台串行执行。
     *
     * @param suite 来自 skill 包的 {@code evals/evals.json}
     * @param files skill 的文件表（脚本、references 等）；PROMPT skill 传空即可
     */
    public SkillEvalRun start(String skillName, String body, Map<String, String> files,
                              EvalSuite suite, String mode, Long skillId, Long conversationId) {
        if (suite == null || suite.getEvals() == null || suite.getEvals().isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "没有测试用例：请在 skill 包里提供 evals/evals.json");
        }
        if (!MODE_RECALL.equals(mode) && !MODE_CAPABILITY.equals(mode)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "mode 只能是 RECALL / CAPABILITY");
        }
        SkillEvalRun run = new SkillEvalRun();
        run.setSkillId(skillId);
        run.setConversationId(conversationId);
        run.setSkillName(skillName);
        run.setMode(mode);
        run.setStatus(STATUS_RUNNING);
        run.setContentHash(SkillEvalGate.contentHash(body, files));
        run.setTotalCases(suite.getEvals().size());
        run.setFinishedCases(0);
        run.setPassedCases(0);
        evalRunMapper.insert(run);

        // 后台执行。MdcAsyncSupport.wrap 是必须的：租户/用户上下文是 ThreadLocal，
        // 不 wrap 的话后台线程上 TenantContext 为空，写库会落到 __no_tenant__ 哨兵上（静默查不到）。
        final Long runId = run.getId();
        final String tenantId = TenantContext.get();
        streamExecutor.execute(MdcAsyncSupport.wrap("skill-eval-" + runId,
                () -> runAll(runId, tenantId, skillName, body, files, suite, mode)));
        return run;
    }

    public SkillEvalRun get(Long id) {
        SkillEvalRun r = evalRunMapper.selectById(id);
        if (r == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "评测记录不存在");
        return r;
    }

    // ------------------------------------------------------------------ 后台执行

    private void runAll(Long runId, String tenantId, String skillName, String body,
                        Map<String, String> files, EvalSuite suite, String mode) {
        List<Map<String, Object>> caseResults = new ArrayList<>();
        int passed = 0;
        int finished = 0;
        try {
            // 整轮只物化一次 skill：N 个用例共用同一份 bundle，省 N-1 次 MinIO 写入，
            // 也保证每个用例测的确实是同一份内容。
            String prefix = "skills/eval/" + runId + "/";
            SidecarRunPayload.SkillRef ref = materializer.materialize(skillName, prefix, body, files);

            for (EvalSuite.EvalCase c : suite.getEvals()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", c.getId());
                one.put("prompt", c.getPrompt());
                try {
                    String prompt = MODE_RECALL.equals(mode)
                            ? EvalPrompts.recallPrompt(c.getPrompt())
                            : EvalPrompts.capabilityPrompt(skillName, c.getPrompt());
                    Transcript t = runOneCase(tenantId, skillName, ref, prompt);
                    one.put("transcript", t.text());
                    one.put("artifacts", t.artifacts());

                    EvalGrading g = grade(skillName, prompt, c, t, MODE_RECALL.equals(mode));
                    one.put("grading", g);
                    if (isCasePassed(g, MODE_RECALL.equals(mode))) {
                        passed++;
                        one.put("passed", true);
                    } else {
                        one.put("passed", false);
                    }
                } catch (Exception e) {
                    log.warn("评测用例失败 runId={} caseId={}: {}", runId, c.getId(), describe(e), e);
                    one.put("passed", false);
                    one.put("error", describe(e));
                }
                caseResults.add(one);
                finished++;
                // 每跑完一条就落一次进度：这轮可能要几分钟，前端得看得见在动。
                updateProgress(runId, finished, passed, caseResults, null, STATUS_RUNNING);
            }
            updateProgress(runId, finished, passed, caseResults, null, STATUS_COMPLETED);
        } catch (Exception e) {
            log.error("评测整轮失败 runId={}", runId, e);
            updateProgress(runId, finished, passed, caseResults, describe(e), STATUS_FAILED);
        }
    }

    /** 异常摘要：带上类名。避免 NPE 这类 message 为 null 的异常在评测结果里显示成孤零零的 "null"、无从排查。 */
    private static String describe(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    /**
     * 一条用例的通过判定。
     *
     * <p>RECALL 模式下 <b>skillInvoked=false 直接判不通过</b>，哪怕断言全过——
     * 那说明模型是凭自身常识答对的，description 没能触发这个 skill，正是要抓的失败。
     */
    private boolean isCasePassed(EvalGrading g, boolean recall) {
        if (g == null || g.getSummary() == null) return false;
        if (recall && !Boolean.TRUE.equals(g.getSkillInvoked())) return false;
        Integer total = g.getSummary().getTotal();
        Integer ok = g.getSummary().getPassed();
        if (total == null || total == 0) {
            // 没有断言：此时唯一有意义的结论就是 skillInvoked。
            return !recall || Boolean.TRUE.equals(g.getSkillInvoked());
        }
        return ok != null && ok.equals(total);
    }

    private void updateProgress(Long runId, int finished, int passed,
                                List<Map<String, Object>> results, String error, String status) {
        try {
            SkillEvalRun u = new SkillEvalRun();
            u.setId(runId);
            u.setFinishedCases(finished);
            u.setPassedCases(passed);
            u.setStatus(status);
            u.setError(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
            if (finished > 0) {
                u.setPassRate(BigDecimal.valueOf((double) passed / finished).setScale(4, RoundingMode.HALF_UP));
            }
            u.setResultJson(CommonUtil.getObjectMapper().writeValueAsString(results));
            evalRunMapper.updateById(u);
        } catch (Exception e) {
            log.warn("写评测进度失败 runId={}: {}", runId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 沙箱一轮

    private record Transcript(String text, List<String> artifacts) {
    }

    /** 派一次沙箱 run，把 SSE 事件折成可读的 transcript。阻塞直到本轮结束或超时。 */
    private Transcript runOneCase(String tenantId, String skillName,
                                  SidecarRunPayload.SkillRef ref, String prompt) {
        SidecarRunPayload payload = new SidecarRunPayload();
        payload.setRunId("eval-" + UUID.randomUUID());
        payload.setTenantId(tenantId);
        payload.setUserId(AdminRequestContext.findUserIdOrNull() == null
                ? null : String.valueOf(AdminRequestContext.findUserIdOrNull()));
        payload.setPrompt(prompt);
        payload.setArtifactBucket(materializer.bucket());
        payload.setSkills(List.of(ref));
        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl(sandboxProps.getLlm().getBaseUrl());
        llm.setAuthToken(sandboxProps.getLlm().getAuthToken());
        llm.setModel(sandboxProps.getLlm().getModel());
        llm.setAuthScheme(sandboxProps.getLlm().getAuthScheme());
        payload.setLlm(llm);
        SidecarRunPayload.Limits limits = new SidecarRunPayload.Limits();
        limits.setWallClockSec(sandboxProps.getWallClockSec());
        limits.setMaxTurns(sandboxProps.getMaxTurns());
        limits.setMaxBudgetUsd(sandboxProps.getMaxBudgetUsd());
        payload.setLimits(limits);

        StringBuilder text = new StringBuilder();
        List<String> artifacts = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (type == null) return;
                appendEvent(text, artifacts, type, data);
            }

            @Override
            public void onClosed(EventSource es) {
                latch.countDown();
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                String msg = t != null ? t.getMessage()
                        : ("sidecar http " + (response != null ? response.code() : "?"));
                text.append("\n[error] ").append(msg);
                latch.countDown();
            }
        };

        sidecarClient.run(payload, listener);
        try {
            if (!latch.await(sandboxProps.getWallClockSec() + 30L, TimeUnit.SECONDS)) {
                text.append("\n[error] 本轮超时");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            text.append("\n[error] 被中断");
        }
        String s = text.toString();
        if (s.length() > MAX_TRANSCRIPT_CHARS) {
            // 保头保尾：开头有 skill 是否被调起的证据，结尾有最终结论，中间最不重要。
            s = s.substring(0, MAX_TRANSCRIPT_CHARS / 2)
                    + "\n…（transcript 过长，已截断中段）…\n"
                    + s.substring(s.length() - MAX_TRANSCRIPT_CHARS / 2);
        }
        return new Transcript(s, artifacts);
    }

    /**
     * 把边车 SSE 折成可读文本。事件名来自边车的 runAgent 映射：
     * claude-delta / progress / tool_result / code_output / artifact / summary / error。
     *
     * <p><b>工具调用必须完整保留</b>：RECALL 模式判定"模型有没有动用这个 skill"，
     * 唯一的证据就在 progress 里的工具名（Skill / Read / Bash 及其入参）。
     */
    private void appendEvent(StringBuilder out, List<String> artifacts, String type, String data) {
        try {
            switch (type) {
                case "claude-delta" -> {
                    // 增量文本：只取 text，避免把整包 SSE 帧塞进 transcript。
                    JSONObject j = new JSONObject(data);
                    Object d = j.getByPath("delta.text");
                    if (d != null) out.append(d);
                }
                case "progress" -> out.append("\n[tool] ").append(data);
                case "tool_result" -> out.append("\n[tool_result] ").append(data);
                case "code_output" -> out.append("\n[code_output] ").append(data);
                case "artifact" -> {
                    out.append("\n[artifact] ").append(data);
                    Object fn = new JSONObject(data).get("filename");
                    if (fn != null) artifacts.add(String.valueOf(fn));
                }
                case "summary" -> out.append("\n[summary] ").append(data);
                case "error" -> out.append("\n[error] ").append(data);
                default -> { /* 未知事件忽略：边车加新事件不该让评测崩掉 */ }
            }
        } catch (Exception e) {
            out.append("\n[").append(type).append("] ").append(data);
        }
    }

    // ------------------------------------------------------------------ 评委

    private EvalGrading grade(String skillName, String prompt, EvalSuite.EvalCase c,
                              Transcript t, boolean recall) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 评委用【可配、且默认取启用模型】的 model，而不是 sandboxProps 里那个——后者是派给沙箱
        // 直连 LLM 用的，可能是个在 ai_model 表里已下线的值（沙箱直连不过 ModelResolver 校验，
        // 但评委走 ClaudeService.messages 会过，于是"沙箱能跑、评委报模型下线"）。
        body.put("model", graderModel != null && !graderModel.isBlank()
                ? graderModel : sandboxProps.getLlm().getModel());
        body.put("max_tokens", 4096);
        body.put("system", EvalPrompts.graderSystem(recall));
        // 必须用【可变】集合：下游 ModelResolver / ChatClient 会就地规整 messages 与其中的消息体。
        // 用 List.of / Map.of 会在那里抛 UnsupportedOperationException——而且它 message 为 null，
        // 在结果里只表现为一个孤零零的 "null"，极难排查（本条注释即由此而来）。
        // 正常聊天路径的 body 来自 Jackson 反序列化（ArrayList/LinkedHashMap），天然可变，
        // 所以只有评委这条自己构造 body 的路会踩到。
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", EvalPrompts.graderUser(skillName, prompt, c.getExpectedOutput(),
                c.getExpectations(), t.text(), t.artifacts()));
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);
        body.put("messages", messages);

        Object resp = claudeService.messages(body);
        String textOut = extractText(resp);
        return parseGrading(textOut);
    }

    /** 从 Anthropic messages 响应里抽 content[].text。 */
    private String extractText(Object resp) {
        try {
            JSONObject j = new JSONObject(resp);
            var content = j.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; content != null && i < content.size(); i++) {
                JSONObject blk = content.getJSONObject(i);
                if ("text".equals(blk.getStr("type"))) sb.append(blk.getStr("text"));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(resp);
        }
    }

    /**
     * 解析评委返回的 JSON。模型偶尔会套 markdown 围栏，这里剥一层；
     * 彻底解析不了就抛，由调用方记成该用例失败——<b>不要返回一个空 grading 冒充成功</b>，
     * 那会让"评委挂了"长得和"测试没通过"一模一样。
     */
    private EvalGrading parseGrading(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).trim();
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        try {
            ObjectMapper om = CommonUtil.getObjectMapper();
            return om.readValue(s, EvalGrading.class);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR,
                    "评委返回的不是合法 JSON: " + e.getMessage());
        }
    }
}
