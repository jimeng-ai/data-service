# 对话 Agent 生图能力接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有对话 Agent 默认具备文生图能力——模型可在对话中调用 `generate_image`，生成图片落 MinIO，以独立图片卡片展示在对话 UI。

**Architecture:** 对话路径是纯 Java 工具循环（`AiConversationLoop`，不走 sidecar）。新增一个 `SkillToolExecutor` 实现（`GenerateImageToolExecutor`，与 `RagSkillToolExecutor` 同构，Spring 自动收集），调 `ImageGenClient`（复用 `agent.sandbox.image-gen` 的 Nacos 配置，当前 provider=seedream）生图、落 MinIO、记账。工具定义在 `AiConversationLoop.injectBuiltinTools` 处按配置完整性注入。前端在 `tool_result` 渲染里识别 `generate_image` 渲染图片卡片。

**Tech Stack:** 后端 Java 17 / Spring Boot / Hutool / JDK `java.net.http.HttpClient` / MinIO；测试 JUnit5 + Mockito。前端 React 18 + TypeScript + Ant Design 5（`Image.PreviewGroup`）。

---

## 背景与已确认决策

- 复用 exec 路径的 `agent.sandbox.image-gen` 配置（一处密钥）；当前实配 `provider: seedream`、`base-url: https://api.302.ai/v1`、`model: doubao-seedream-5-0-260128`。
- v1 只实现 **seedream + openai（均同步）**，kling-o3 异步推后。
- 图片落 MinIO，返回 presigned URL（已知限制：7 天过期，见末尾）。
- 计费：新增 `biz_type=image_gen` 记一笔 `ai_model_call_log`；Trace 由注册中心按 `traceStepType()=TOOL_CALL` 自动埋点（无需手写）。
- 闸门：三件套齐全即对所有 Agent 全局开启，不加按 Agent 开关。
- 展示：独立图片卡片（走 `tool_result` 事件 `output.urls`）。

## File Structure

**后端（data-service / modules/data-server，分支 `feat/chat-image-gen`）：**

- Create `…/ai/image/ImageGenToolDefinitions.java` — `generate_image` 工具 schema（仿 `WebToolDefinitions`）。
- Create `…/ai/image/ImageGenClient.java` — 调上游生图 API（seedream/openai 分发），返回图片字节；含纯函数 `normalizeBase`/`buildSeedreamBody`/`buildOpenAiBody`/`extractImageBytes`。
- Create `…/ai/image/GenerateImageToolExecutor.java` — `SkillToolExecutor` 实现：生图→落 MinIO→记账→返回 `{urls,…}`。
- Modify `…/ai/billing/BizTypeContext.java` — 新增 `IMAGE_GEN` 常量。
- Modify `…/ai/conversation/AiConversationLoop.java` — `injectBuiltinTools` 增 `imageGen` 参数 + 两处调用点 + 闸门计算。
- Test：`…/ai/image/ImageGenToolDefinitionsTest.java`、`ImageGenClientTest.java`、`GenerateImageToolExecutorTest.java`。

**前端（jm-agent-front，分支 `feat/chat-image-gen`）：**

- Modify `src/features/chat-admin/types.ts` — `ToolCallView.output` 支持 `{ urls?: string[] }`。
- Modify `src/features/chat-admin/hooks/useSSE.ts` — `onToolResult` 持久化 `r.output`。
- Modify `src/features/chat-admin/components/MessageBubble.tsx` — `ToolStepCard` 增图片卡片分支。

包根：`com.jimeng.dataserver` 实际目录前缀
`modules/data-server/src/main/java/com/jimeng/dataserver/`。

---

### Task 1: `generate_image` 工具定义 + biz_type 常量

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/image/ImageGenToolDefinitions.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/billing/BizTypeContext.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/image/ImageGenToolDefinitionsTest.java`

- [ ] **Step 1: 写失败测试**

`ImageGenToolDefinitionsTest.java`:
```java
package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenToolDefinitionsTest {

    @Test
    void buildsGenerateImageDef() {
        SkillToolDefinition def = ImageGenToolDefinitions.GENERATE_IMAGE;
        assertEquals("generate_image", def.getModelName());
        Map<String, Object> schema = def.getInputSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("prompt"));
        assertTrue(props.containsKey("count"));
        assertEquals(List.of("prompt"), schema.get("required"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl modules/data-server test -Dtest=ImageGenToolDefinitionsTest`
Expected: 编译失败 / `cannot find symbol ImageGenToolDefinitions`

- [ ] **Step 3: 实现工具定义**

`ImageGenToolDefinitions.java`:
```java
package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置生图工具定义（模型可见名 generate_image）。由 AiConversationLoop 在 agent.sandbox.image-gen
 * 三件套配齐时注入进请求体 tools 列表，对所有 Agent 永远在场（不走 skill 发现流程）。
 */
public final class ImageGenToolDefinitions {

    private ImageGenToolDefinitions() {
    }

    public static final SkillToolDefinition GENERATE_IMAGE = build();

    private static SkillToolDefinition build() {
        Map<String, Object> prompt = prop("string", "图片内容的详细文字描述（中英文均可）");
        Map<String, Object> count = prop("integer", "生成图片数量，1-4，默认 1");
        count.put("minimum", 1);
        count.put("maximum", 4);
        Map<String, Object> size = prop("string", "图片尺寸，默认 1024x1024");
        size.put("enum", List.of("1024x1024", "1536x1024", "1024x1536"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", prompt);
        properties.put("count", count);
        properties.put("size", size);
        return new SkillToolDefinition(
                "generate_image",
                "根据文字描述生成图片。当用户要求画图、生成图片、出图、做插画/头像时调用本工具，返回图片的可访问 URL。",
                objectSchema(properties, List.of("prompt")));
    }

    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }
}
```

- [ ] **Step 4: 新增 biz_type 常量**

在 `BizTypeContext.java` 现有常量区（`AGENT_GEN`/`SKILL_GEN` 之后）加：
```java
    /** 对话内 generate_image 文生图。 */
    public static final String IMAGE_GEN = "image_gen";
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl modules/data-server test -Dtest=ImageGenToolDefinitionsTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/image/ImageGenToolDefinitions.java \
        modules/data-server/src/main/java/com/jimeng/dataserver/ai/billing/BizTypeContext.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/image/ImageGenToolDefinitionsTest.java
git commit -m "feat: generate_image 工具定义 + image_gen biz_type

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `ImageGenClient`（调上游生图，复用 sandbox 配置）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/image/ImageGenClient.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/image/ImageGenClientTest.java`

参考：sidecar `jm-agent-sandbox/src/mcp/imageGenTool.ts`（seedream `:143-209`、openai `:219-294`、`normalizeBase` `:17`）；配置类 `AgentSandboxProperties.ImageGen`（`ai/agent/exec/config/AgentSandboxProperties.java:48-58`）。

- [ ] **Step 1: 写失败测试（纯函数 + 解析）**

`ImageGenClientTest.java`:
```java
package com.jimeng.dataserver.ai.image;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenClientTest {

    @Test
    void normalizeBaseStripsV1AndSlash() {
        assertEquals("https://api.302.ai", ImageGenClient.normalizeBase("https://api.302.ai/v1"));
        assertEquals("https://api.302.ai", ImageGenClient.normalizeBase("https://api.302.ai/v1/"));
        assertEquals("https://x.com", ImageGenClient.normalizeBase("https://x.com/"));
    }

    @Test
    void seedreamBodyHasExpectedFields() {
        JSONObject b = JSONUtil.parseObj(ImageGenClient.buildSeedreamBody("doubao-seedream-5-0", "一只猫"));
        assertEquals("doubao-seedream-5-0", b.getStr("model"));
        assertEquals("2K", b.getStr("size"));
        assertEquals("disabled", b.getStr("sequential_image_generation"));
    }

    @Test
    void openAiBodyDefaultsSize() {
        JSONObject b = JSONUtil.parseObj(ImageGenClient.buildOpenAiBody("gpt-image-2", "a cat", null));
        assertEquals("1024x1024", b.getStr("size"));
        assertEquals(1, b.getInt("n").intValue());
    }

    @Test
    void extractImageBytesDecodesB64() throws Exception {
        ImageGenClient client = new ImageGenClient(new AgentSandboxProperties());
        byte[] raw = {(byte) 0x89, 'P', 'N', 'G'};
        String b64 = Base64.getEncoder().encodeToString(raw);
        byte[] bytes = client.extractImageBytes("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}");
        assertArrayEquals(raw, bytes);
    }

    @Test
    void extractImageBytesThrowsOnError() {
        ImageGenClient client = new ImageGenClient(new AgentSandboxProperties());
        Exception e = assertThrows(RuntimeException.class,
                () -> client.extractImageBytes("{\"error\":{\"message\":\"quota exceeded\"}}"));
        assertTrue(e.getMessage().contains("quota"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl modules/data-server test -Dtest=ImageGenClientTest`
Expected: 编译失败 / `cannot find symbol ImageGenClient`

- [ ] **Step 3: 实现 `ImageGenClient`**

`ImageGenClient.java`:
```java
package com.jimeng.dataserver.ai.image;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话路径的生图客户端：复用 agent.sandbox.image-gen 配置，调 302.ai 上游（seedream / openai 兼容），
 * 返回图片字节。与 sidecar src/mcp/imageGenTool.ts 同构（同 endpoint / 同请求体）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenClient {

    private static final Duration GEN_TIMEOUT = Duration.ofSeconds(150);

    private final AgentSandboxProperties props;

    // proxy(getDefault()) 让本地 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=4780 生效，绕过本机对
    // api.302.ai 的 DNS 污染；生产无系统代理时即直连。http 字段带初始化，不进 @RequiredArgsConstructor。
    private final HttpClient http = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 生图三件套（base-url/auth-token/model）齐全才算启用。 */
    public boolean enabled() {
        AgentSandboxProperties.ImageGen ig = props.getImageGen();
        return ig != null && StrUtil.isAllNotBlank(ig.getBaseUrl(), ig.getAuthToken(), ig.getModel());
    }

    /** 生成 count 张图，返回每张图片的字节；任一张失败即抛异常。 */
    public List<byte[]> generate(String prompt, String size, int count) throws Exception {
        AgentSandboxProperties.ImageGen ig = props.getImageGen();
        boolean seedream = "seedream".equalsIgnoreCase(StrUtil.blankToDefault(ig.getProvider(), "openai"));
        List<byte[]> images = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String json = seedream
                    ? postJson(seedreamUrl(ig), authValue(ig), buildSeedreamBody(ig.getModel(), prompt))
                    : postJson(openAiUrl(ig), authValue(ig), buildOpenAiBody(ig.getModel(), prompt, size));
            images.add(extractImageBytes(json));
        }
        return images;
    }

    // ---- URL / auth ----
    static String normalizeBase(String baseUrl) {
        return baseUrl.replaceAll("/+$", "").replaceAll("(?i)/v1$", "");
    }

    private static String seedreamUrl(AgentSandboxProperties.ImageGen ig) {
        return normalizeBase(ig.getBaseUrl()) + "/doubao/images/generations";
    }

    private static String openAiUrl(AgentSandboxProperties.ImageGen ig) {
        return normalizeBase(ig.getBaseUrl()) + "/v1/images/generations";
    }

    private static String authValue(AgentSandboxProperties.ImageGen ig) {
        String scheme = StrUtil.blankToDefault(ig.getAuthScheme(), "bearer");
        return "api-key".equalsIgnoreCase(scheme) ? ig.getAuthToken() : "Bearer " + ig.getAuthToken();
    }

    // ---- 请求体（与 sidecar imageGenTool.ts 同构）----
    static String buildSeedreamBody(String model, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", "2K");                               // seedream 用 2K/4K 而非像素；v1 固定 2K（同 sidecar 默认）
        body.put("response_format", "url");
        body.put("watermark", false);
        body.put("sequential_image_generation", "disabled");  // 每次出 1 张，多张交给外层 count 循环
        return JSONUtil.toJsonStr(body);
    }

    static String buildOpenAiBody(String model, String prompt, String size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", StrUtil.blankToDefault(size, "1024x1024"));
        body.put("quality", "medium");
        return JSONUtil.toJsonStr(body);
    }

    private String postJson(String url, String auth, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(GEN_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("生图接口 HTTP " + resp.statusCode() + ": " + StrUtil.maxLength(resp.body(), 300));
        }
        return resp.body();
    }

    /** 解析 {data:[{url|b64_json}], error:{message}}；优先 b64_json，否则下载 url。包私有便于单测。 */
    byte[] extractImageBytes(String json) throws Exception {
        JSONObject obj = JSONUtil.parseObj(json);
        JSONObject error = obj.getJSONObject("error");
        if (error != null && StrUtil.isNotBlank(error.getStr("message"))) {
            throw new RuntimeException("生图失败: " + error.getStr("message"));
        }
        JSONArray data = obj.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("生图接口未返回图片");
        }
        JSONObject first = data.getJSONObject(0);
        String b64 = first.getStr("b64_json");
        if (StrUtil.isNotBlank(b64)) {
            return Base64.getDecoder().decode(b64);
        }
        String url = first.getStr("url");
        if (StrUtil.isBlank(url)) {
            throw new RuntimeException("生图返回项既无 b64_json 也无 url");
        }
        return downloadBytes(url);
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(GEN_TIMEOUT).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("下载生成图片失败 HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl modules/data-server test -Dtest=ImageGenClientTest`
Expected: PASS（4 个测试）

- [ ] **Step 5: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/image/ImageGenClient.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/image/ImageGenClientTest.java
git commit -m "feat: ImageGenClient 调上游生图(seedream/openai),复用 sandbox 配置

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `GenerateImageToolExecutor`（落 MinIO + 记账 + 返回卡片数据）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/image/GenerateImageToolExecutor.java`
- Test: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/image/GenerateImageToolExecutorTest.java`

参考：执行器接口 `ai/skill/service/SkillToolExecutor.java`；同构样例 `ai/rag/skill/RagSkillToolExecutor.java`、`ai/skill/install/SkillInstallToolExecutor.java`；MinIO `RagMinioStorageService.uploadBytes/presignedUrl`（`:85-97,109-113`）；记账 `AiModelCallRecordService.recordComputedCall(provider,endpoint,model,bizType,usage,httpStatus,latencyMs,note)`；用量 `ai/billing/usage/NormalizedUsage`。

- [ ] **Step 1: 写失败测试**

`GenerateImageToolExecutorTest.java`:
```java
package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerateImageToolExecutorTest {

    private static AgentSandboxProperties props() {
        AgentSandboxProperties p = new AgentSandboxProperties();
        AgentSandboxProperties.ImageGen ig = new AgentSandboxProperties.ImageGen();
        ig.setProvider("seedream");
        ig.setBaseUrl("https://api.302.ai/v1");
        ig.setAuthToken("sk-x");
        ig.setModel("doubao-seedream-5-0");
        p.setImageGen(ig);
        return p;
    }

    @Test
    void supportsOnlyGenerateImage() {
        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(
                mock(ImageGenClient.class), mock(RagMinioStorageService.class),
                mock(AiModelCallRecordService.class), props());
        assertTrue(ex.supports("generate_image"));
        assertFalse(ex.supports("rag_search"));
    }

    @Test
    void executeUploadsAndReturnsUrls() throws Exception {
        ImageGenClient client = mock(ImageGenClient.class);
        when(client.generate(eq("胖橘猫"), anyString(), eq(1)))
                .thenReturn(List.of(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        when(minio.uploadBytes(any(), anyString(), anyString())).thenReturn("2026/06/21/abc_genimg.png");
        when(minio.presignedUrl(eq("2026/06/21/abc_genimg.png"), anyInt())).thenReturn("http://minio/genimg.png");
        AiModelCallRecordService rec = mock(AiModelCallRecordService.class);

        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(client, minio, rec, props());
        Object out = ex.execute("generate_image", Map.of("prompt", "胖橘猫"));

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) out;
        assertEquals(List.of("http://minio/genimg.png"), m.get("urls"));
        assertEquals(1, m.get("count"));
        verify(rec).recordComputedCall(any(), eq("image:generate"), any(), eq("image_gen"),
                any(), eq(200), anyInt(), any());
    }

    @Test
    void executeRejectsBlankPrompt() {
        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(
                mock(ImageGenClient.class), mock(RagMinioStorageService.class),
                mock(AiModelCallRecordService.class), props());
        assertThrows(IllegalArgumentException.class,
                () -> ex.execute("generate_image", Map.of("prompt", "")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl modules/data-server test -Dtest=GenerateImageToolExecutorTest`
Expected: 编译失败 / `cannot find symbol GenerateImageToolExecutor`

- [ ] **Step 3: 实现执行器**

`GenerateImageToolExecutor.java`:
```java
package com.jimeng.dataserver.ai.image;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.BizTypeContext;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话内置工具 generate_image：把模型发出的文生图调用路由到 ImageGenClient（复用
 * agent.sandbox.image-gen 配置），生成图片落 MinIO，返回长期可访问 URL 供前端图片卡片渲染。
 * 由 SkillToolExecutorRegistryService 自动 Spring 注入收集；traceStepType=TOOL_CALL 由注册中心自动埋点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateImageToolExecutor implements SkillToolExecutor {

    private static final String TOOL = "generate_image";
    private static final int PRESIGN_EXPIRY_SEC = 7 * 24 * 3600; // MinIO presigned 上限 7 天

    private final ImageGenClient imageGenClient;
    private final RagMinioStorageService minio;
    private final AiModelCallRecordService recordService;
    private final AgentSandboxProperties props;

    @Override
    public boolean supports(String toolName) {
        return TOOL.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        Object p = input == null ? null : input.get("prompt");
        String prompt = p instanceof String s ? s : null;
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalArgumentException("generate_image 需要 prompt(string)");
        }
        int count = input.get("count") instanceof Number n ? Math.max(1, Math.min(4, n.intValue())) : 1;
        String size = input.get("size") instanceof String s ? s : "1024x1024";

        long start = System.currentTimeMillis();
        try {
            List<byte[]> images = imageGenClient.generate(prompt, size, count);
            List<String> urls = new ArrayList<>(images.size());
            for (byte[] img : images) {
                String ext = sniffExt(img);
                String objectName = minio.uploadBytes(img, "genimg-" + IdUtil.fastSimpleUUID() + ext, contentType(ext));
                urls.add(minio.presignedUrl(objectName, PRESIGN_EXPIRY_SEC));
            }
            recordBilling(size, urls.size(), 200, (int) (System.currentTimeMillis() - start));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("urls", urls);
            out.put("model", props.getImageGen().getModel());
            out.put("size", size);
            out.put("count", urls.size());
            return out;
        } catch (Exception e) {
            recordBilling(size, 0, 500, (int) (System.currentTimeMillis() - start));
            log.warn("生图工具执行失败 prompt={} err={}", StrUtil.maxLength(prompt, 80), e.getMessage());
            throw new RuntimeException("生图失败: " + e.getMessage(), e);
        }
    }

    private void recordBilling(String size, int imageCount, int httpStatus, int latencyMs) {
        try {
            AgentSandboxProperties.ImageGen ig = props.getImageGen();
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("biz_type", BizTypeContext.IMAGE_GEN);
            note.put("image_count", imageCount);
            note.put("size", size);
            recordService.recordComputedCall(
                    StrUtil.blankToDefault(ig.getProvider(), "image"),
                    "image:generate",
                    ig.getModel(),
                    BizTypeContext.IMAGE_GEN,
                    new NormalizedUsage(),   // 生图无 token 用量
                    httpStatus,
                    latencyMs,
                    note);
        } catch (Exception e) {
            log.warn("生图计费记录失败: {}", e.getMessage());
        }
    }

    private static String sniffExt(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return ".jpg";
        }
        return ".png";
    }

    private static String contentType(String ext) {
        return ".jpg".equals(ext) ? "image/jpeg" : "image/png";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl modules/data-server test -Dtest=GenerateImageToolExecutorTest`
Expected: PASS（3 个测试）

> 若 `recordComputedCall` 的 8 参重载不存在（只有带 `agentId` 的 9 参），改用 9 参版本并在末尾传 `null`：`recordService.recordComputedCall(..., note, null)`，同步调整测试 `verify` 的参数个数。

- [ ] **Step 5: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/image/GenerateImageToolExecutor.java \
        modules/data-server/src/test/java/com/jimeng/dataserver/ai/image/GenerateImageToolExecutorTest.java
git commit -m "feat: GenerateImageToolExecutor 落 MinIO + image_gen 记账

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: 接进对话循环（注入工具 + 闸门）

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/conversation/AiConversationLoop.java`

`injectBuiltinTools` 当前在 `:70-83`，被 `runBlocking`（`:104`）和 `runStream`（`:202`）两处调用；闸门 `builtinToolsEnabled` 在两处各算一次（blocking 在 `:101-103`）。

- [ ] **Step 1: 加 ImageGenClient 依赖字段**

在字段区（`private final WebSearchProperties webSearchProperties;` 之后，`:56` 附近）加：
```java
    private final com.jimeng.dataserver.ai.image.ImageGenClient imageGenClient;
```
并在顶部加 import：
```java
import com.jimeng.dataserver.ai.image.ImageGenToolDefinitions;
```

- [ ] **Step 2: 改 `injectBuiltinTools` 签名与方法体**

把 `:70-83` 整个方法替换为：
```java
    /**
     * 注入内置工具定义（web_search/web_fetch、skill.search/skill.install、generate_image），对所有 Agent
     * 永远在场（不走 skill 发现流程），并确保 tool_choice=auto。各工具组由对应开关独立控制。
     */
    private void injectBuiltinTools(Map<String, Object> body, AiProtocolAdapter adapter,
                                    boolean webTools, boolean skillInstall, boolean imageGen) {
        List<Object> tools = adapter.getToolsList(body);
        if (webTools) {
            tools.add(adapter.convertToolDef(WebToolDefinitions.WEB_SEARCH));
            tools.add(adapter.convertToolDef(WebToolDefinitions.WEB_FETCH));
        }
        if (skillInstall) {
            tools.add(adapter.convertToolDef(SkillInstallToolDefinitions.SEARCH_DEF));
            tools.add(adapter.convertToolDef(SkillInstallToolDefinitions.INSTALL_DEF));
        }
        if (imageGen) {
            tools.add(adapter.convertToolDef(ImageGenToolDefinitions.GENERATE_IMAGE));
        }
        adapter.setToolsList(body, tools);
        adapter.ensureToolChoiceAuto(body);
    }
```

- [ ] **Step 3: 改两处闸门 + 调用点**

`runBlocking` 内（`:101-105`）替换为：
```java
        boolean webToolsEnabled = webSearchProperties.enabled();
        boolean imageGenEnabled = imageGenClient.enabled();
        boolean builtinToolsEnabled = webToolsEnabled || skillInstallEnabled || imageGenEnabled;
        if (builtinToolsEnabled) {
            injectBuiltinTools(body, adapter, webToolsEnabled, skillInstallEnabled, imageGenEnabled);
        }
```
`runStream` 内有同形状的一段（闸门计算 + `:202` 的 `injectBuiltinTools(body, adapter, webToolsEnabled, skillInstallEnabled)` 调用）。用 `grep -n "injectBuiltinTools(body, adapter, webToolsEnabled, skillInstallEnabled)" AiConversationLoop.java` 定位第二处，做**完全相同**的两点改动：闸门加 `imageGenEnabled`、调用补第 5 个实参 `imageGenEnabled`。

- [ ] **Step 4: 编译 + 跑相关测试**

Run: `mvn -pl modules/data-server -q -DskipTests compile && mvn -pl modules/data-server test -Dtest='ImageGen*,GenerateImage*'`
Expected: 编译通过；测试 PASS。

> 验证两处都改全：`grep -n "injectBuiltinTools(" AiConversationLoop.java` 应见 1 处定义（5 参）+ 2 处调用（均 5 实参）。

- [ ] **Step 5: 提交**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/conversation/AiConversationLoop.java
git commit -m "feat: 对话循环注入 generate_image 工具(配齐即全局开启)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: 后端构建 + 本地端到端实跑（必做，jerry 硬性要求）

**Files:** 无（验证任务）

- [ ] **Step 1: 全量构建后端**

Run: `cd /Users/jerry/Desktop/jm/data-service && mvn clean install -DskipTests`
Expected: BUILD SUCCESS，产出 `modules/data-server/target/data-server-1.0-SNAPSHOT.jar`

- [ ] **Step 2: 重启 data-server（带本地代理，绕 302.ai DNS 污染）**

先停旧进程：`lsof -nP -iTCP:8020 -sTCP:LISTEN -t | xargs -r kill`
再起（注意加 https 代理，排除回环）：
```bash
cd /Users/jerry/Desktop/jm/data-service
NACOS_SERVER_ADDR=127.0.0.1:8849 nohup java \
  -Dspring.cloud.nacos.discovery.ip=127.0.0.1 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=4780 \
  -Dhttp.nonProxyHosts='localhost|127.0.0.1' \
  -jar modules/data-server/target/data-server-1.0-SNAPSHOT.jar > logs/data-server.log 2>&1 &
```
等就绪：`until lsof -nP -iTCP:8020 -sTCP:LISTEN >/dev/null 2>&1; do sleep 2; done; echo up`

- [ ] **Step 3: 对话端实跑生图**

浏览器开 `http://localhost:8082` 登录（企业端账号）→ 进任意 Agent 对话 → 发「帮我生成一个胖橘猫」。
观察后端日志生图调用：`grep -iE 'generate_image|生图|image:generate' logs/data-server.log | tail`

- [ ] **Step 4: 查库 + 查 MinIO 验证（不只看 UI）**

```bash
# ai_model_call_log 有 image_gen 记账
docker exec -i dev-mysql mysql -uroot -p123456 -N -e \
  "SELECT id,biz_type,model,http_status FROM \`data-server\`.ai_model_call_log WHERE biz_type='image_gen' ORDER BY id DESC LIMIT 3;"
# MinIO 有生成图片对象（RAG bucket 内 genimg-*）
docker exec -i dev-minio sh -c "mc ls --recursive local/ 2>/dev/null | grep genimg | tail" || echo "用控制台 :9003 看 genimg-*"
```
Expected: 有 `image_gen` 记录（`http_status=200`）；MinIO 有 `genimg-*` 对象；UI 出现图片。

> 若生图超时：确认 data-server 出口经 4780 能到 `api.302.ai`（`curl -x http://127.0.0.1:4780 https://api.302.ai -I`）。本地网络绕行，生产直连无此问题。

- [ ] **Step 5: 提交（如有调试性微调）**

```bash
git add -A && git commit -m "chore: 生图本地端到端联调通过" || echo "无改动可提交"
```

---

### Task 6: 前端图片卡片渲染

**Files:**
- Modify: `jm-agent-front/src/features/chat-admin/types.ts`（`ToolCallView` `:9-19`）
- Modify: `jm-agent-front/src/features/chat-admin/hooks/useSSE.ts`（`onToolResult` 回调）
- Modify: `jm-agent-front/src/features/chat-admin/components/MessageBubble.tsx`（`ToolStepCard` `:94-143`）

先在前端建分支：`cd /Users/jerry/Desktop/jm/jm-agent-front && git switch -c feat/chat-image-gen`

- [ ] **Step 1: 扩展 `ToolCallView.output` 类型**

`types.ts` 把 `ToolCallView` 的 `output?: string;` 改为：
```typescript
  output?: string | { urls?: string[]; model?: string; size?: string; count?: number };
```

- [ ] **Step 2: `useSSE.ts` 的 `onToolResult` 持久化 output**

`grep -n "onToolResult" src/features/chat-admin/hooks/useSSE.ts` 定位回调。把"找到对应 tool segment 后只更新 status"的逻辑，改为同时写入 `output`。两处分支（更新已有 segment / 新建 segment）都带上 `output: r.output`：
```typescript
  const onToolResult = useCallback(
    (results: ToolResultItem[]) => {
      for (const r of results) {
        const idx = segmentsRef.current.findIndex(
          (s) => s.type === 'tool' && s.call.id === r.id,
        );
        if (idx >= 0) {
          const seg = segmentsRef.current[idx];
          if (seg.type === 'tool') {
            segmentsRef.current[idx] = {
              type: 'tool',
              call: { ...seg.call, status: r.status, output: r.output as ToolCallView['output'] },
            };
          }
        } else {
          segmentsRef.current.push({
            type: 'tool',
            call: { id: r.id, name: r.name, status: r.status, output: r.output as ToolCallView['output'] },
          });
        }
      }
      commit();
    },
    [commit],
  );
```
（确保 `ToolCallView` 已 import；`ToolResultItem` 来自 `../api`。函数主体以现有实现为准，仅补 `output` 字段——保留现有其余逻辑。）

- [ ] **Step 3: `MessageBubble.tsx` 的 `ToolStepCard` 加图片分支**

确认顶部已 `import { Image } from 'antd';`（没有则加）。在 `ToolStepCard` 函数体内、`return` 之前插入：
```tsx
  const imgOut =
    tc.name === 'generate_image' && tc.output && typeof tc.output === 'object'
      ? (tc.output as { urls?: string[] })
      : null;
  if (imgOut && Array.isArray(imgOut.urls) && imgOut.urls.length > 0) {
    return (
      <div className="chat-step chat-step--tool">
        <div className="chat-step__body">
          <div className="chat-step__title">生成图片（{imgOut.urls.length}）</div>
          <Image.PreviewGroup>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
                gap: 8,
                marginTop: 8,
              }}
            >
              {imgOut.urls.map((url, i) => (
                <Image key={i} src={url} width="100%" style={{ borderRadius: 8 }} />
              ))}
            </div>
          </Image.PreviewGroup>
        </div>
      </div>
    );
  }
```
（Ant Design `Image` 自带点击放大/缩放/下载工具栏，无需额外组件。）

- [ ] **Step 4: 类型检查 + 构建**

Run: `cd /Users/jerry/Desktop/jm/jm-agent-front && npm run build`
Expected: 构建成功，无 TS 报错。

- [ ] **Step 5: 提交**

```bash
git add src/features/chat-admin/types.ts src/features/chat-admin/hooks/useSSE.ts \
        src/features/chat-admin/components/MessageBubble.tsx
git commit -m "feat: 对话端 generate_image 结果渲染为图片卡片

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: 前端容器重建 + 端到端验证（必做）

**Files:** 无（验证任务）

- [ ] **Step 1: 重建 jm-agent-front 容器（必带 dev nginx conf）**

```bash
cd /Users/jerry/Desktop/jm/jm-agent-front
docker build --build-arg NGINX_CONF=nginx.dev.conf -t jm-agent-front:local .
docker rm -f jm-agent-front
docker run -d --name jm-agent-front -p 8082:80 jm-agent-front:local
docker exec jm-agent-front grep proxy_pass /etc/nginx/conf.d/default.conf  # 应见 host.docker.internal:10011
```

- [ ] **Step 2: 浏览器端到端验证**

开 `http://localhost:8082` → 对话发「画一只戴帽子的胖橘猫」→ 确认：
1. 对话里出现**图片卡片**（不是折叠的 `<pre>` 文本块）；
2. 点击图片能放大预览、可下载；
3. 多张（发「生成 2 张不同姿势的胖橘猫」）时网格平铺。

- [ ] **Step 3: 回归确认其它工具未受影响**

发一句会触发联网/知识库的提问，确认普通 `tool_result`（非生图）仍按原折叠块渲染，无报错。

---

## 已知限制 / 后续

- **presigned URL 7 天过期**：MinIO presigned 上限 7 天，超过后历史对话里的图会失效。后续可加一个"按 objectKey 鉴权流式回图"的后端端点（注意二进制下载须 `void`+`OutputStream` 写出，避免被 `GlobalResponseHandler` 包成 JSON）替代 presigned，做永久可访问。
- **生图成本未计价**：`image_gen` 记账目前 token 用量为空、cost=0，仅用于"按模型×功能"计数；图像按张/分辨率的计价需扩展 `ModelPricing`，列为后续。
- **复用 RAG bucket**：生成图片落在 `rag.ingestion.minio-bucket`，v1 可接受；如需隔离再开专用 bucket。
- **kling-o3 异步 / 图生图**：v1 不做；`ImageGenClient.generate` 的 provider 分发已留扩展位。

---

## Self-Review

- **Spec 覆盖**：生图工具(Task1-3)/配置复用(Task2)/MinIO 存储(Task3)/对话注入与全局闸门(Task4)/计费 image_gen(Task3)/Trace(自动,由 traceStepType=TOOL_CALL)/前端图片卡片(Task6)/端到端(Task5、7) — 均有对应任务。Trace 走注册中心自动埋点，无独立任务（设计已说明）。
- **占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码。
- **类型一致**：`ImageGenClient.generate(String,String,int)`→`List<byte[]>`、`GenerateImageToolExecutor` 构造参数顺序(client,minio,recordService,props)与测试一致；`recordComputedCall` 8 参，含 9 参 fallback 备注；前端 `output` 类型在 types.ts/useSSE.ts/MessageBubble.tsx 三处一致为 `string | {urls?...}`。
