package com.jimeng.dataserver.ai.agent.exec.dto;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SidecarRunPayload} 的语义层新字段与边车 TS 类型的对拍（设计文档 5.1、10.1）。
 *
 * <h3>为什么要对拍</h3>
 * 边车对未知字段<b>静默忽略</b>，所以 Java 这边字段名拼错不会报错，只会「不生效」。{@code SidecarRunPayload.Conn}
 * 就是现成的例子：Java 叫 {@code scheme}，边车读 {@code authScheme}，api-key 连接被静默当成 bearer，直到有人去翻两边的代码。
 * 语义层这几个字段错了的后果更隐蔽：{@code semanticContext} 的某个键对不上，边车的校验回 400，编排器把它当成
 * 「sandbox 拒绝语义层运行」降级——看起来像边车版本问题。
 *
 * <p>断言走<b>与派发完全相同的序列化路径</b>（{@code SidecarClient.run} 里的 Hutool {@code JSONUtil.toJsonStr}），
 * 解析出来看真实的键和类型，而不是看 Java 字段名。
 *
 * <p>期望的键抄自 jm-agent-sandbox {@code src/types.ts} 的 {@code interface SemanticContext} 与 {@code RunRequest}。
 * 本机工作区里并排放着边车仓库时，另外直接解析那份 {@code types.ts} 再比一次（找不到或那份还没有语义层字段就跳过这一段，
 * data-service 自己的 CI 不依赖边车仓库）。
 */
class SidecarRunPayloadJsonTest {

    /** 抄自边车 src/types.ts 的 interface SemanticContext，顺序无关。改这里之前先改边车。 */
    private static final Set<String> SANDBOX_SEMANTIC_CONTEXT_KEYS =
            Set.of("callbackBaseUrl", "accessToken", "generationId", "connectorId", "sliceNo");

    /** 雪花 id：大于 2^53，按 JSON 数字下发会被 JS 的 JSON.parse 静默改成 2099066794181541900。 */
    private static final long GENERATION_ID = 2099066794181541890L;
    private static final long CONNECTOR_ID = 2099066794181541891L;

    private static final String ACCESS_TOKEN = "eyJSENTINEL.semantic.callback";
    private static final String LLM_KEY = "sk-SENTINEL-deepseek";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SidecarRunPayload semanticPayload() {
        SidecarRunPayload p = new SidecarRunPayload();
        p.setRunId("semgen-" + GENERATION_ID + "-s1-a1");
        p.setTenantId("t_review");
        p.setUserId("1900000000000000001");
        p.setPrompt("开始本片工作。先调用 get_run_scope 读取本片范围。");
        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl("https://api.deepseek.com/anthropic");
        llm.setAuthToken(LLM_KEY);
        llm.setModel("deepseek-flash");
        llm.setAuthScheme("api-key");
        p.setLlm(llm);
        p.setRunProfile("semantic-layer");
        SidecarRunPayload.SemanticContext ctx = new SidecarRunPayload.SemanticContext();
        ctx.setCallbackBaseUrl("http://localhost:10011/data");
        ctx.setAccessToken(ACCESS_TOKEN);
        ctx.setGenerationId(String.valueOf(GENERATION_ID));
        ctx.setConnectorId(String.valueOf(CONNECTOR_ID));
        ctx.setSliceNo(1);
        p.setSemanticContext(ctx);
        return p;
    }

    /** 与 SidecarClient.run 同一条序列化路径。 */
    private static JsonNode wire(SidecarRunPayload p) throws IOException {
        return JSON.readTree(JSONUtil.toJsonStr(p));
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    @Test
    @DisplayName("★ semanticContext 的键名与边车 SemanticContext 逐字段一致，runProfile / semanticContext 按 RunRequest 的名字下发")
    void semanticContext键名与sandbox类型逐字段一致() throws IOException {
        JsonNode root = wire(semanticPayload());

        assertEquals("semantic-layer", root.path("runProfile").asText(), "runProfile 键名或取值不对：" + root);
        JsonNode ctx = root.get("semanticContext");
        assertNotNull(ctx, "请求体里没有 semanticContext 键：" + root);
        assertEquals(SANDBOX_SEMANTIC_CONTEXT_KEYS, keys(ctx),
                "semanticContext 的键与边车 src/types.ts 不一致——边车对多出来的键静默忽略、对缺的键回 400");

        // 其他调用方不设这两个字段时，请求体里也不能多出非空的这两个键（边车 default profile 见到非空 semanticContext 直接 400）。
        // 实测 Hutool 5.8.16 的 JSONUtil.toJsonStr 默认不输出 null 字段，所以老调用方的请求体里根本没有这两个键；
        // 这里只断言「没有非空值」，因为边车把 null 与缺省同样对待，哪天 Hutool 改成输出 null 也不算回归。
        SidecarRunPayload legacy = semanticPayload();
        legacy.setRunProfile(null);
        legacy.setSemanticContext(null);
        JsonNode legacyRoot = wire(legacy);
        assertFalse(legacyRoot.has("semanticContext") && !legacyRoot.get("semanticContext").isNull(),
                "未设 semanticContext 却下发了非空的 semanticContext：" + legacyRoot);
        assertFalse(legacyRoot.has("runProfile") && !legacyRoot.get("runProfile").isNull(),
                "未设 runProfile 却下发了非空的 runProfile：" + legacyRoot);

        // 本机工作区有边车仓库时，直接对着那份 types.ts 再比一次，不靠上面手抄的常量
        Path typesTs = findSandboxTypesTs();
        String ts = typesTs == null ? null : Files.readString(typesTs, StandardCharsets.UTF_8);
        if (ts != null && ts.contains("interface SemanticContext")) {
            assertEquals(interfaceFields(ts, "SemanticContext"), keys(ctx),
                    "semanticContext 的键与 " + typesTs + " 的 interface SemanticContext 不一致");
            Set<String> runRequestFields = interfaceFields(ts, "RunRequest");
            assertTrue(runRequestFields.contains("runProfile") && runRequestFields.contains("semanticContext"),
                    typesTs + " 的 RunRequest 里没有 runProfile / semanticContext：" + runRequestFields);
            for (String k : keys(root)) {
                assertTrue(runRequestFields.contains(k), "请求体顶层键 " + k + " 在边车 RunRequest 里不存在（会被静默忽略）");
            }
        }
    }

    @Test
    @DisplayName("★ generationId / connectorId 序列化为十进制字符串且逐位不变，sliceNo 是 JSON 数字")
    void 两个id序列化为字符串() throws Exception {
        // 类型本身先钉住：改成 Long 的话 Hutool 会按数字输出（类注释），边车按字符串校验、传 number 直接 400
        assertEquals(String.class, SidecarRunPayload.SemanticContext.class.getDeclaredField("generationId").getType());
        assertEquals(String.class, SidecarRunPayload.SemanticContext.class.getDeclaredField("connectorId").getType());

        String raw = JSONUtil.toJsonStr(semanticPayload());
        JsonNode ctx = JSON.readTree(raw).get("semanticContext");

        assertTrue(ctx.get("generationId").isTextual(), "generationId 不是 JSON 字符串：" + ctx);
        assertTrue(ctx.get("connectorId").isTextual(), "connectorId 不是 JSON 字符串：" + ctx);
        assertEquals("2099066794181541890", ctx.get("generationId").asText());
        assertEquals("2099066794181541891", ctx.get("connectorId").asText());
        // 原文里就是带引号的，边车的 JSON.parse 拿到的是字符串、不经过 double
        assertTrue(raw.contains("\"generationId\":\"2099066794181541890\""), raw);
        // 边车按 ^[1-9][0-9]{0,19}$ 校验
        assertTrue(ctx.get("generationId").asText().matches("^[1-9][0-9]{0,19}$"));

        assertTrue(ctx.get("sliceNo").isIntegralNumber(), "sliceNo 应是 JSON 数字（边车 Number.isInteger 校验）：" + ctx);
        assertEquals(1, ctx.get("sliceNo").asInt());
    }

    @Test
    @DisplayName("★ accessToken 与 llm.authToken 不进 toString，但照常序列化进请求体")
    void accessToken不进toString() throws IOException {
        SidecarRunPayload p = semanticPayload();

        String s = p.toString();
        assertFalse(s.contains(ACCESS_TOKEN), "payload.toString() 带出了回调 token：" + s);
        assertFalse(s.contains(LLM_KEY), "payload.toString() 带出了模型 key：" + s);
        String ctxString = p.getSemanticContext().toString();
        assertFalse(ctxString.contains(ACCESS_TOKEN), ctxString);
        // toString 本身还有用：非敏感字段照常在
        assertTrue(ctxString.contains(String.valueOf(GENERATION_ID)), ctxString);

        // 排除只作用于 toString，不能连带把请求体里的 token 也弄丢
        JsonNode root = wire(p);
        assertEquals(ACCESS_TOKEN, root.path("semanticContext").path("accessToken").asText());
        assertEquals(LLM_KEY, root.path("llm").path("authToken").asText());
    }

    // ------------------------------------------------------------------ 本机边车仓库（可选）

    /** 从当前目录往上找工作区里并排的 jm-agent-sandbox/src/types.ts；找不到返回 null。 */
    private static Path findSandboxTypesTs() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; dir != null && i < 6; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("jm-agent-sandbox").resolve("src").resolve("types.ts");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 取 {@code export interface Name { ... }} 里的字段名（{@code name:} 或 {@code name?:}），跳过注释行。 */
    private static Set<String> interfaceFields(String ts, String name) {
        Matcher m = Pattern.compile("interface\\s+" + name + "\\s*\\{(.*?)\\n}", Pattern.DOTALL).matcher(ts);
        assertTrue(m.find(), "types.ts 里没找到 interface " + name);
        Set<String> out = new LinkedHashSet<>();
        Pattern field = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\??\\s*:");
        for (String line : m.group(1).split("\\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) {
                continue;
            }
            Matcher f = field.matcher(line);
            if (f.find()) {
                out.add(f.group(1));
            }
        }
        return out;
    }
}
