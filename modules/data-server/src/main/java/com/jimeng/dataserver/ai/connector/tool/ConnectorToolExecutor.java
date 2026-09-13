package com.jimeng.dataserver.ai.connector.tool;

import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.InvokeResult;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.model.WriteOutcome;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorSummary;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.InvokeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 连接器能力工具：把模型发出的 {@code conn_*} 工具调用路由到 {@link ConnectorGateway}。
 * 由 {@code SkillToolExecutorRegistryService} 自动 Spring 注入收集，无需额外注册。
 *
 * <h3>为什么是五个工具，而技术架构 §12.2 只列了四个</h3>
 * §12.2 的四个是「列目录 / 看结构 / 执行查询 / 调用接口」，§12.4 另说「平台默认在上下文里注入
 * 目录级概览」。但我们把能力工具落成了<b>磁盘 skill 包</b>（{@code skills/connector/}），
 * 而磁盘 SKILL.md 是<b>静态文本</b>——它被原样拼进 system 提示，没有「按当前 Agent 动态注入
 * 授权连接清单」的插入点。于是「有哪些连接器可用」只能也做成一个工具，即 {@code conn_list}。
 * <b>这是一处刻意的偏离，不是漏读文档。</b>若将来改走内置工具（有运行期注入点），可以把它退回
 * 成上下文注入、并删掉这个工具。
 *
 * <h3>为什么写操作是第六个独立工具，而不是把 conn_query 放宽</h3>
 * <ol>
 *   <li>放宽 {@code conn_query} 意味着 {@code ReadOnlySqlGuard} 要长出「有时允许写」的分支，
 *       而那道护栏的价值恰恰在于它<b>没有例外</b>：把「这条路绝对写不了」这个一眼可验的性质，
 *       换成一个要读条件才能确定的性质，不划算。</li>
 *   <li>分开之后模型必须<b>明确选择</b>「我要做一次写操作」，而不是在一个通用工具里
 *       不小心传了条 UPDATE 进去——工具名本身就是一道意图确认。</li>
 *   <li>审计、限流、审批都按<b>能力</b>分派（{@link Capability#WRITE}），分开后不需要再解析语句
 *       去猜这次调用是读还是写；猜错的那次，恰恰是最不该猜错的那次。</li>
 * </ol>
 *
 * <h3>{@code conn_execute} 返回的两种形态</h3>
 * 写策略是 {@code REQUIRE_APPROVAL} 时网关<b>会放行</b>（它只判 {@code WritePolicy.allowsWrite()}），
 * 但这一档不该直接执行——提交待审批项的分支在 {@code ConnectorGateway} 之外处理。
 * 于是本工具的 payload 有两种形态，<b>必须让模型能一眼分开</b>：
 * <ul>
 *   <li><b>已执行</b>：带 {@code affected_rows} / {@code statement} / {@code committed=true}
 *       （见 {@code WriteResult.toModelPayload()}）</li>
 *   <li><b>待审批</b>：带 {@code pending_approval=true} 与 {@code approval_id}，
 *       <b>此时数据一行都没改</b></li>
 * </ul>
 * 后两个 key 是<b>预留</b>的，本类当前不产生它们，但 {@code tools.json} 与 {@code SKILL.md}
 * 已经按这个形状写好了模型侧指引。<b>不要改这两个 key 的名字</b>：改名的表现不是报错，
 * 而是模型把一条「已提交待审批」的操作，向用户报告成「已完成修改」。
 *
 * <h3>构造器只准注入这两个 bean</h3>
 * {@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService →
 * SkillToolExecutorRegistryService → 本类}。注入任何能回到 {@code ProviderRegistry} /
 * {@code ClaudeService} / {@code ChatClient} 的 bean（例如想在工具里再叫一次 LLM 做 NL2SQL）
 * 会让这条链闭合，启动期构造循环依赖直接失败——这个坑仓库里已经被咬过两次。
 *
 * <h3>execute() 为什么从不抛异常</h3>
 * 注册中心的兜底 catch 会把 {@code e.getMessage()} 原样塞进回灌模型的 payload，再经协议适配器
 * 全量 JSON 化落进 {@code ai_model_call_content}。而 JDBC / HTTP 异常消息里常带完整 SQL、
 * 主机名、连接参数——抛出去就等于把客户的库结构写进了模型上下文和平台库表。
 * 所以这里自己 catch 住一切，转成固定形状的结构化错误<b>返回值</b>：
 * {@code {error, title, message, detail}}，其中 {@code detail} <b>只</b>取
 * {@link ConnectorException#getSafeDetail()}，绝不取 {@code getCause()}。
 * 模型因此永远只会拿到 {@link ConnectorErrorCode} 那九类之一，能据此决定重试 / 缩小范围 / 说明情况。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorToolExecutor implements SkillToolExecutor {

    static final String TOOL_LIST = "conn_list";
    static final String TOOL_CATALOG = "conn_catalog";
    static final String TOOL_DESCRIBE = "conn_describe";
    static final String TOOL_QUERY = "conn_query";
    static final String TOOL_INVOKE = "conn_invoke";
    /** 写操作单独一个名字。{@code conn_} 前缀已与既有工具做过撞名检查——撞名会被 mergeTools <b>静默丢弃</b>。 */
    static final String TOOL_EXECUTE = "conn_execute";

    /**
     * 只认这六个精确名字，<b>不做前缀匹配</b>。{@code SkillToolExecutorRegistryService.findExecutor}
     * 是线性扫描 first-match，既无 {@code @Order} 也无冲突检测：两个执行器的 supports() 区间一旦重叠，
     * 胜者由 Spring 注入顺序静默决定。前缀匹配（{@code startsWith("conn_")}）就是在给未来埋这种雷。
     */
    private static final Set<String> TOOLS =
            Set.of(TOOL_LIST, TOOL_CATALOG, TOOL_DESCRIBE, TOOL_QUERY, TOOL_INVOKE, TOOL_EXECUTE);

    private final ConnectorGateway connectorGateway;
    private final ConnectorProperties properties;

    @Override
    public boolean supports(String toolName) {
        return toolName != null && TOOLS.contains(toolName);
    }

    @Override
    public String traceStepType() {
        // 连接器自己在 ConnectorAuditService 里埋点（谁、哪个 Agent、哪次运行、做了什么、成没成），
        // 注册中心这边跳过，避免同一次调用被记两遍。与 RagSkillToolExecutor 的做法一致。
        return null;
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        Map<String, Object> args = input == null ? Map.of() : input;
        // toolName 归一成非 null：switch 撞 null 会抛 NPE，被下面的兜底 catch 归成 UPSTREAM_ERROR，
        // 而那个提示（「目标系统返回了错误」）会把排查方向带偏到客户系统上去。
        String name = toolName == null ? "" : toolName;
        try {
            switch (name) {
                case TOOL_LIST:
                    return doList();
                case TOOL_CATALOG:
                    return doCatalog(args);
                case TOOL_DESCRIBE:
                    return doDescribe(args);
                case TOOL_QUERY:
                    return doQuery(args);
                case TOOL_INVOKE:
                    return doInvoke(args);
                case TOOL_EXECUTE:
                    return doExecute(args);
                default:
                    // supports() 已经挡过一层，走到这里说明注册中心的路由和这里分叉了。
                    throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "未知的连接器工具：" + name);
            }
        } catch (ConnectorException e) {
            // cause 只准进日志。注意「不打印 input」：它含模型写的查询语句与调用参数，
            // 日志经 filebeat 进 Kibana，打出来等于把客户的 SQL 和业务参数落到检索平台上。
            log.warn("连接器工具执行失败 tool={} code={} detail={}", toolName, e.getCode(), e.getSafeDetail(), e);
            return errorPayload(e.getCode(), e.getSafeDetail());
        } catch (Exception e) {
            // 非 ConnectorException = 没人归过类的失败（含 NPE、类型转换、网关自身的异常）。
            // 归到 UPSTREAM_ERROR 并且 detail 留空：任何未经脱敏的文本都不准出现在返回值里。
            log.error("连接器工具执行出现未归类异常 tool={}", toolName, e);
            return errorPayload(ConnectorErrorCode.UPSTREAM_ERROR, null);
        }
    }

    // ------------------------------------------------------------------ 五个工具

    private Map<String, Object> doList() {
        List<ConnectorSummary> summaries = connectorGateway.listAuthorized();
        List<Map<String, Object>> items = new ArrayList<>();
        if (summaries != null) {
            for (ConnectorSummary s : summaries) {
                if (s == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("connector", s.name());
                m.put("display_name", s.displayName());
                m.put("kind", s.kind());
                m.put("capabilities", lowerCaseCapabilities(s.capabilities()));
                m.put("health_state", s.healthState());
                m.put("health_reason", s.healthReason());
                m.put("readonly_verified", s.readonlyVerified());
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("connectors", items);
        if (items.isEmpty()) {
            // 空集必须说成「未被授权」而不是让模型自行解读——否则它会开始猜连接器名字然后一路失败。
            out.put("hint", "当前 Agent 没有被授权任何连接器。请如实告诉用户「尚未接入可查询的外部系统」，"
                    + "不要猜测连接器名称，也不要凭空作答。");
        } else {
            out.put("hint", "capabilities 决定可用工具：query → conn_query，describe → conn_catalog / conn_describe，"
                    + "invoke → conn_invoke。health_state 非健康时调用很可能失败，请先向用户说明。");
        }
        return out;
    }

    private Map<String, Object> doCatalog(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        CatalogView view = connectorGateway.execute(connector, Capability.DESCRIBE, TOOL_CATALOG,
                session -> describeCapable(session).catalog());

        List<Map<String, Object>> entries = new ArrayList<>();
        if (view != null && view.entries() != null) {
            for (CatalogEntry e : view.entries()) {
                if (e == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.name());
                m.put("type", e.type());
                m.put("comment", e.comment());
                entries.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("object_kind", view == null ? null : view.objectKind());
        out.put("count", entries.size());
        out.put("total", view == null ? entries.size() : view.total());
        out.put("entries", entries);
        boolean truncated = view != null && view.truncated();
        out.put("truncated", truncated);
        if (truncated) {
            out.put("warning", "对象太多，目录已被截断，这不是全部对象。找不到想要的对象时请向用户确认名称，"
                    + "不要断言「系统里没有这张表」。");
        }
        return out;
    }

    private Map<String, Object> doDescribe(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        String object = requireString(args, "object");
        ObjectDetail detail = connectorGateway.execute(connector, Capability.DESCRIBE, TOOL_DESCRIBE,
                session -> describeCapable(session).describe(object));

        List<Map<String, Object>> fields = new ArrayList<>();
        if (detail != null && detail.fields() != null) {
            for (FieldDetail f : detail.fields()) {
                if (f == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("type", f.type());
                m.put("nullable", f.nullable());
                m.put("comment", f.comment());
                m.put("extra", f.extra());
                fields.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("object", detail == null ? object : detail.name());
        out.put("type", detail == null ? null : detail.type());
        out.put("comment", detail == null ? null : detail.comment());
        out.put("fields", fields);
        out.put("extra", detail == null ? null : detail.extra());
        return out;
    }

    private Map<String, Object> doQuery(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        String statement = requireString(args, "statement");

        // 嵌套配置类名字不写死（用 var）：它属于别人的文件，少一个硬耦合少一次对不齐。
        var q = properties.getQuery();
        int maxLimit = q.getMaxLimit();
        int defaultLimit = Math.min(q.getDefaultLimit(), maxLimit);
        Integer requested = intOrNull(args.get("limit"));

        int limit = defaultLimit;
        String limitNote = null;
        if (requested != null && requested <= 0) {
            limitNote = "limit 必须是正整数，已按平台默认值 " + defaultLimit + " 执行。";
        } else if (requested != null && requested > maxLimit) {
            // 夹取而不是报错：模型传大了不是它的错（它不知道上限），但必须明说实际用了多少，
            // 否则它会把「前 maxLimit 行」当成全集下结论。
            limit = maxLimit;
            limitNote = "请求的 limit=" + requested + " 超过平台上限，实际按 " + maxLimit + " 行执行，结果可能不是全集。";
        } else if (requested != null) {
            limit = requested;
        }

        QueryOptions options = new QueryOptions(limit, q.getMaxResultBytes(), q.getTimeoutSeconds());
        QueryResult result = connectorGateway.execute(connector, Capability.QUERY, TOOL_QUERY,
                session -> queryCapable(session).query(statement, options));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("limit", limit);
        if (limitNote != null) {
            out.put("limit_note", limitNote);
        }
        if (result != null) {
            // toModelPayload() 已经带上了平台实际执行的语句、行数与截断告警——查数必须亮出过程，
            // 只给一个数字会让口径错误彻底失去被发现的机会。
            out.putAll(result.toModelPayload());
        }
        return out;
    }

    private Map<String, Object> doInvoke(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        String operation = requireString(args, "operation");
        Map<String, Object> params = mapOrEmpty(args.get("params"));

        InvokeResult result = connectorGateway.execute(connector, Capability.INVOKE, TOOL_INVOKE,
                session -> invokeCapable(session).invoke(operation, params));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("operation", operation);
        if (result != null) {
            out.putAll(result.toModelPayload());
        }
        return out;
    }

    // ------------------------------------------------------------------ 第六个工具：写

    /**
     * 写操作。与 {@link #doQuery} 最大的差别是<b>这里没有「夹到上限继续跑」这种善意的自动修正</b>：
     * 查询的 limit 传大了夹一下照样出结果，写操作的影响行数超限则必须<b>整条回滚</b>
     * （在 {@code MySqlSession} 里执行之后、提交之前判定），因为「改了一半」比「一行没改」危险得多。
     *
     * <p>护栏参数一律从 {@code connector.write.*} 取，<b>不接受模型传入</b>——
     * 行数上限是事故防线，能被调用方改的防线等于没有。
     */
    private Map<String, Object> doExecute(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        String statement = requireString(args, "statement");

        // 嵌套配置类名字不写死（用 var），与 doQuery 同一理由：它属于别人的文件。
        var w = properties.getWrite();
        WriteOptions options = new WriteOptions(w.getMaxAffectedRows(), w.getTimeoutSeconds());

        // 分档（拒 / 入队 / 执行）在网关里判，这里只负责把两种归宿翻译成模型读得懂的两种形状。
        WriteOutcome outcome = connectorGateway.executeWrite(connector, statement, options, TOOL_EXECUTE);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        // 上限主动回给模型：它据此才能在动手前就把大批量拆成几批，而不是先撞一次回滚再来问。
        // 撞回滚本身是安全的（数据没变），但它会多烧一轮对话，还会让用户以为出了故障。
        out.put("max_affected_rows", w.getMaxAffectedRows());

        if (outcome != null && outcome.pendingApproval()) {
            out.put("pending_approval", true);
            // 雪花 id 按字符串下发：仓库的既定契约（JS 的 Number 放不下 19 位整数，
            // 静默丢精度之后模型报给用户的单号会对不上）。
            out.put("approval_id", String.valueOf(outcome.approvalId()));
            out.put("committed", false);
            // ★ 这段话是写给模型看的，措辞是有意的：
            //   「未执行」必须说死，否则模型很容易把「已提交」讲成「已改好」；
            //   「不要重试」也必须说死，否则它下一轮会以为是失败，再提交一条一模一样的进队列。
            out.put("message", "这条写操作需要人工审批，已提交审批队列，数据尚未被修改。"
                    + "请如实告诉用户「已提交审批，等待管理员处理」，不要声称修改已完成，也不要重复提交同一条语句。");
            return out;
        }

        if (outcome != null && outcome.result() != null) {
            // toModelPayload() 带着平台实际执行的语句、影响行数、committed 标记，以及 0 行时的告警。
            // 写操作比查询更要亮出过程：改了几行、改的是哪条语句，是事后唯一能核对的东西。
            out.putAll(outcome.result().toModelPayload());
        }
        return out;
    }

    // ------------------------------------------------------------------ 能力窄化

    // 网关按 required 能力放行后会话仍未实现对应接口，说明实例的 capability_flags 和实现分叉了。
    // 直接强转会抛 ClassCastException（消息里带实现类全名），这里换成一条已归类、已脱敏的错误。
    private static DescribeCapable describeCapable(Object session) {
        if (session instanceof DescribeCapable d) return d;
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "该连接器不支持自描述（describe）能力");
    }

    private static QueryCapable queryCapable(Object session) {
        if (session instanceof QueryCapable c) return c;
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "该连接器不支持查询（query）能力");
    }

    private static InvokeCapable invokeCapable(Object session) {
        if (session instanceof InvokeCapable c) return c;
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "该连接器不支持调用（invoke）能力");
    }

    // ------------------------------------------------------------------ 入参与错误

    /** 入参校验失败也走同一套错误形状，模型才不用分辨两种失败长相。 */
    private static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "缺少必填参数 " + key + "，它必须是非空字符串");
        }
        return s.trim();
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        // 全局 write_numbers_as_strings 只影响出参，但模型仍可能把数字写成字符串，顺手认一下。
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "参数 limit 必须是整数");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object v) {
        if (v == null) return Map.of();
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "参数 params 必须是一个对象");
    }

    private static List<String> lowerCaseCapabilities(Set<Capability> caps) {
        List<String> out = new ArrayList<>();
        if (caps != null) {
            for (Capability c : caps) {
                if (c != null) out.add(c.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 固定错误形状。detail 只可能来自 {@link ConnectorException#getSafeDetail()}。 */
    private static Map<String, Object> errorPayload(ConnectorErrorCode code, String safeDetail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code.name().toLowerCase(Locale.ROOT));
        out.put("title", code.title());
        out.put("message", code.modelHint());
        out.put("detail", safeDetail);
        return out;
    }
}
