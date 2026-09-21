package com.jimeng.dataserver.ai.connector.agent.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.connector.tool.ConnectorToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接器工具名的三方对拍：执行器常量 ↔ tools.json ↔ 回调控制器路由。
 *
 * <h3>为什么必须有这条</h3>
 * 这三处分别属于三个平面：{@code ConnectorToolExecutor} 是宿主的执行入口，
 * {@code tools.json} 是下发给模型的工具定义，{@code ConnectorAgentCallbackController} 的
 * 路由表是沙箱平面回调时的<b>白名单</b>。三者之间原先没有任何测试对拍。
 *
 * <p>名字漂一个字母的表现不是启动失败，而是：<b>模型看得见这个工具、每次调用都被拒，
 * 直到轮次耗尽</b>——既不报错也不降级，排查起来只能靠逐条肉眼比对。
 *
 * <p>★ 控制器那一侧刻意用<b>反射读注解</b>，不把八个字符串再抄一遍来自我对拍：
 * 抄一遍等于把「测试」变成「第四份副本」，改名时它会跟着一起错。
 */
class ConnectorAgentToolNameParityTest {

    /** 工作目录是 modules/data-server（surefire 默认），另兼容从仓库根跑的情况。 */
    private static final Path[] TOOLS_JSON_CANDIDATES = {
            Path.of("skills/connector/tools.json"),
            Path.of("modules/data-server/skills/connector/tools.json"),
            Path.of("data-service/modules/data-server/skills/connector/tools.json")
    };

    private static final int EXPECTED_COUNT = 8;

    @Test
    @DisplayName("★ 执行器常量 / tools.json / 回调路由，三处工具名必须是同一个集合")
    void 三方工具名一致() throws Exception {
        Set<String> constants = executorToolConstants();
        Set<String> declared = toolsJsonNames();
        Set<String> routes = callbackRoutePathSegments();

        assertEquals(EXPECTED_COUNT, constants.size(),
                "ConnectorToolExecutor 的 TOOL_* 常量应有 " + EXPECTED_COUNT + " 个，实际 " + constants);
        assertEquals(EXPECTED_COUNT, declared.size(),
                "tools.json 应有 " + EXPECTED_COUNT + " 条，实际 " + declared);
        assertEquals(EXPECTED_COUNT, routes.size(),
                "回调控制器应有 " + EXPECTED_COUNT + " 个 @PostMapping，实际 " + routes);

        assertEquals(constants, declared,
                "执行器常量与 tools.json 不一致：只在常量里=" + difference(constants, declared)
                        + "，只在 tools.json 里=" + difference(declared, constants));
        assertEquals(constants, routes,
                "执行器常量与回调路由不一致：只在常量里=" + difference(constants, routes)
                        + "，只在路由里=" + difference(routes, constants));
    }

    @Test
    @DisplayName("回调路由的 path 段不得重复——重复会让 Spring 启动期就撞路由")
    void 回调路由无重名() {
        Method[] methods = ConnectorAgentCallbackController.class.getDeclaredMethods();
        long mappingCount = Arrays.stream(methods)
                .filter(m -> m.getAnnotation(PostMapping.class) != null)
                .count();
        assertEquals(mappingCount, callbackRoutePathSegments().size(), "存在重复的 @PostMapping path 段");
    }

    @Test
    @DisplayName("工具名一律 conn_ 前缀且不含斜杠——path 段与工具名是同一个字符串")
    void 工具名形状() {
        for (String name : executorToolConstants()) {
            assertTrue(name.startsWith("conn_"), name + " 缺少 conn_ 前缀");
            assertTrue(name.matches("[a-z0-9_]+"),
                    name + " 含有不允许的字符（Anthropic 工具名不接受点号 / 斜杠）");
        }
    }

    // ---------------------------------------------------------------- 三个来源

    /** (a) 反射读 {@code ConnectorToolExecutor} 的 public static final String TOOL_* 常量。 */
    private static Set<String> executorToolConstants() {
        Set<String> names = new TreeSet<>();
        for (Field field : ConnectorToolExecutor.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!field.getName().startsWith("TOOL_")
                    || !Modifier.isPublic(modifiers)
                    || !Modifier.isStatic(modifiers)
                    || field.getType() != String.class) {
                continue;
            }
            try {
                names.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError("读取 " + field.getName() + " 失败", e);
            }
        }
        return names;
    }

    /** (b) tools.json 里八条的 name。 */
    private static Set<String> toolsJsonNames() throws IOException {
        Path toolsJson = Arrays.stream(TOOLS_JSON_CANDIDATES)
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "找不到 skills/connector/tools.json（工作目录 "
                                + Path.of("").toAbsolutePath() + "）；这条对拍不允许被跳过"));
        JsonNode tools = new ObjectMapper().readTree(Files.readString(toolsJson));
        Set<String> names = new TreeSet<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            assertTrue(!name.isBlank(), "tools.json 里存在没有 name 的条目");
            assertTrue(names.add(name), "tools.json 里工具名重复：" + name);
        }
        return names;
    }

    /** (c) 反射读回调控制器八个 {@code @PostMapping} 的 path 段（不硬编码字符串）。 */
    private static Set<String> callbackRoutePathSegments() {
        Set<String> segments = new LinkedHashSet<>();
        for (Method method : ConnectorAgentCallbackController.class.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] paths = mapping.value().length > 0 ? mapping.value() : mapping.path();
            assertEquals(1, paths.length,
                    method.getName() + " 的 @PostMapping 应恰好声明一个 path（路由表即白名单）");
            String path = paths[0];
            assertTrue(path.startsWith("/") && path.indexOf('/', 1) < 0,
                    method.getName() + " 的 path 必须是单段 " + path);
            segments.add(path.substring(1));
        }
        return new TreeSet<>(segments);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> only = new TreeSet<>(left);
        only.removeAll(right);
        return only;
    }
}
