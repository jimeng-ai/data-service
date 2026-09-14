package com.jimeng.dataserver.ai.connector.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
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
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticValueProfiler;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.InvokeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.dataserver.web.MdcContextFilter;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 连接器能力工具：把模型发出的 {@code conn_*} 工具调用路由到 {@link ConnectorGateway}。
 * 由 {@code SkillToolExecutorRegistryService} 自动 Spring 注入收集，无需额外注册。
 *
 * <h3>为什么有 conn_list 这个工具，而技术架构 §12.2 只列了四个</h3>
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
 * <h3>conn_describe 里 joins 的四种状态，四句不同的话</h3>
 * P1 时期每条关系都写着「未经数据验证」，因为那时确实没有验证这回事。S3 之后一条关系的
 * {@code verified} 是 {@code CONFIRMED / WEAK / UNDECIDABLE / NONE} 之一
 * （{@code REJECTED} <b>整条不注入</b>——它是被数据验过、验错了的那一条）。
 * 这四种<b>必须说成四句不同的话</b>：模型只有读到不同的话，才可能做不同的事。
 * 说成同一句的代价是双向的——验证通过的关系照样被要求再跑一次 COUNT（白花客户的资源），
 * 而「查过了但判不出来」被当成「没查过」（再查一次，结论还是判不出来）。
 * 还要额外说一件 basis 回答不了的事：一条<b>成立</b>的 1:N / N:N 关系，直接 join 会把行数放大，
 * {@code SUM} 出来的金额凭空变大<b>且不报错</b>——这就是 {@code fanout_warning} 那个 key。
 *
 * <h3>★ 没有语义行时，返回的 JSON 必须和这套特性不存在时一模一样</h3>
 * 所有语义相关的 key（{@code semantic} / {@code joins} / {@code value_domain} /
 * {@code glossary} / {@code ambiguities} / {@code semantic_hint}）<b>一律缺省即不出现</b>，
 * 既不给 null 也不给空数组。原因不是洁癖：{@code "joins": []} 读起来是「这张表没有关联」，
 * {@code "value_domain": {}} 读起来是「这列没有枚举值」——而事实是我们根本没有这条信息。
 * 空值在这里不是"没内容"，是"一句错话"。
 *
 * <h3>★ 构造器能注入什么：判据是"能不能走回 ProviderRegistry"</h3>
 * {@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService →
 * SkillToolExecutorRegistryService → 本类}。注入任何能回到 {@code ProviderRegistry} /
 * {@code ClaudeService} / {@code ChatClient} 的 bean（例如想在工具里再叫一次 LLM 做 NL2SQL）
 * 会让这条链闭合，启动期构造循环依赖直接失败——这个坑仓库里已经被咬过两次。
 *
 * <p>现有五个依赖都过得了这条判据：{@link ConnectorGateway} 与 {@link ConnectorProperties} 不必说；
 * {@link ConnectorSemanticService} 只碰我们自己的库（两个 mapper），不叫模型；
 * {@link ConnectionMapper} 与 {@link ConnectorSemanticMapper} 是裸 MyBatis 接口。
 * <b>{@code ConnectorSemanticDeriveService} 永远不准注进来</b>——它要叫 {@code ClaudeService} 做推导，
 * 正好闭合上面那条链。语义层的"读"和"推"分成两个类，就是为了让这条线一眼可判。
 *
 * <h3>第七个工具 conn_define_metric：为什么是显式工具而不是对话嗅探</h3>
 * 模型现在已经在问「销售额要不要扣退款」了（{@code SKILL.md} 写着要问），缺的不是问，是<b>问完之后记住</b>。
 * 那为什么不在对话历史里认一下「用户刚刚回答了一个口径问题」然后自动落库？因为设计自己的规矩是
 * 「口径这类必须 100% 正确的东西，用确定性规则做，不要交给模型理解」——嗅探恰恰是交给模型理解。
 * 工具调用是确定性的：调了就是调了，没调就是没记。
 *
 * <p>这个工具<b>不碰客户系统</b>（只写我们自己的 {@code connector_semantic}），所以它不走网关。
 * 但授权照判：名字必须出现在 {@code listAuthorized()} 里，措辞与网关的 {@code findByName} 刻意一致——
 * 「不存在」和「未授权」同形，否则可以靠报错差异把租户里的连接名枚举出来。
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
    /** 口径沉淀。唯一一个<b>不碰客户系统</b>的 conn_ 工具：它只写我们自己的 {@code connector_semantic}。 */
    static final String TOOL_DEFINE_METRIC = "conn_define_metric";

    /**
     * 只认这七个精确名字，<b>不做前缀匹配</b>。{@code SkillToolExecutorRegistryService.findExecutor}
     * 是线性扫描 first-match，既无 {@code @Order} 也无冲突检测：两个执行器的 supports() 区间一旦重叠，
     * 胜者由 Spring 注入顺序静默决定。前缀匹配（{@code startsWith("conn_")}）就是在给未来埋这种雷。
     */
    private static final Set<String> TOOLS = Set.of(
            TOOL_LIST, TOOL_CATALOG, TOOL_DESCRIBE, TOOL_QUERY, TOOL_INVOKE, TOOL_EXECUTE, TOOL_DEFINE_METRIC);

    /**
     * 注入给模型看的那句提示。<b>semantic 与 comment 必须并排出现、永远不合并</b>：
     * comment 是客户自己写在库里的一手事实，semantic 是平台的二手推断，合成一个字段之后
     * 模型就再也分不清这句话是谁说的，而"谁说的"正是它该不该采信的唯一判据。
     */
    private static final String SEMANTIC_HINT =
            "semantic 是平台维护的业务说明（我们对这个库的理解，可能过时也可能推错），"
                    + "comment 是客户库自己写的注释（一手事实）。两者分开给、不要混为一谈，"
                    + "冲突时以 comment 为准并在回答里说明。semantic_status=STALE 表示所挂的结构已经变了。";

    /**
     * 结构漂移的措辞。<b>刻意与"未经数据验证"用两套完全不同的话</b>：
     * 前者是"这条说明当初可能就是错的"，后者是"这条说明当初对、现在结构变了"，
     * 模型对这两种情况该做的事不一样（一个先跑 COUNT 自验，一个重新看结构）。
     * 说成同一个词，它就只能按同一种方式处理，等于白标。
     */
    private static final String DRIFT_NOTE =
            "结构已变，这条说明可能过时——请以本次返回的 type / comment 为准，不要直接采信它";

    /**
     * 值域这一组措辞。<b>三句话说的是三件不同的事，不许合并</b>：
     * 结构变了（当初采的那组值属于一个已经不存在的列）、记录自相矛盾（写着完整却没有取值）、
     * 干脆没拿到。它们对模型的指示各不相同，而共同点只有一个——
     * <b>都不准被读成"这一列没有枚举值"</b>，因为那会让它放心写死一个查询条件。
     */
    private static final String VALUE_DOMAIN_STALE_NOTE =
            "这一列的结构已经变过，平台此前采集到的取值集合属于旧的列形状，已经不再展示。"
                    + "这不等于这一列没有枚举值——需要确切取值时自己查一次，或向用户确认。";

    /** 记录自相矛盾时的说法。<b>绝不能沿用那句写着"以下是全部取值"的话</b>，它下面已经没有取值了。 */
    private static final String VALUE_DOMAIN_BROKEN_NOTE =
            "平台这一列的取值记录不完整（标着已采全，却没有取值），按【没有拿到】处理。"
                    + "不要假设这一列只有某几个取值。";

    /** 兜底：拿到了完整集合，但采集时那句说明丢了。 */
    private static final String VALUE_DOMAIN_COMPLETE_NOTE =
            "以下是该列在客户库中的全部实际取值。平台【不知道】每个取值代表什么业务含义，"
                    + "也不会去猜——需要用到含义时请向用户确认。";

    /** 兜底：没拿到，而且连为什么没拿到都没记下来。 */
    private static final String VALUE_DOMAIN_UNKNOWN_NOTE =
            "平台没有这一列的完整取值集合。这不等于它没有枚举值——不要据此写死查询条件，"
                    + "需要确切取值时自己查一次，或向用户确认。";

    /**
     * {@code connector_semantic.term} 的列宽。191 不是随手定的：唯一键
     * {@code uk_connector_semantic} 含这一列，InnoDB 单索引上限 3072 字节、utf8mb4 每字符 4 字节，
     * 按 255 建表直接 ERROR 1071。<b>改这两个常量之前先去改迁移脚本的列宽</b>，反过来同理。
     */
    private static final int TERM_MAX = 191;
    /** {@code connector_semantic.gloss} 的列宽。 */
    private static final int GLOSS_MAX = 1000;

    private final ConnectorGateway connectorGateway;
    private final ConnectorProperties properties;
    private final ConnectorSemanticService semanticService;
    /** 只用来把模型给的连接名换成 {@code connection.id}（语义层按 id 存）。租户过滤由拦截器注入。 */
    private final ConnectionMapper connectionMapper;
    /**
     * 只为 conn_catalog 那一次「按 scope 取 CAVEAT」而注入，理由见 {@link #ambiguityPayload}。
     * 裸 {@code BaseMapper}，过得了上面那条「能不能走回 ProviderRegistry」的判据。
     */
    private final ConnectorSemanticMapper semanticMapper;

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
                case TOOL_DEFINE_METRIC:
                    return doDefineMetric(args);
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

    // ------------------------------------------------------------------ 五个读 / 调用工具

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

        // ★ 语义层在网关调用【之后】才取。网关那一趟已经把租户上下文和 Agent 授权判完了，
        //   这时按名字反查 id 才是安全的；反过来先查 id 再调网关，等于开了一条不经授权就能
        //   用报错差异枚举本租户连接名的路。
        Long connectorId = resolveConnectorId(connector);
        Map<String, ConnectorSemantic> objects = Map.of();
        List<Map<String, Object>> glossary = List.of();
        // ★ 口径和告诫必须【同一趟】出：凡是这次真的给出了答案的词条，对应的问题就要退场。
        //   否则同一个 payload 里既有「销售额=扣除退款，9月13日由张三确认」又有
        //   「销售额是否扣除退款？」，而 SKILL.md 让模型见到 ambiguity 就去问用户——
        //   用户答过一次，下一轮照样被问第二次，正是这套东西存在要解决的那件事。
        Set<String> answeredTerms = new HashSet<>();
        if (connectorId != null) {
            ConnectorSemanticService.CatalogSemantics sem = semanticService.forCatalog(connectorId);
            if (sem.getObjects() != null) objects = sem.getObjects();
            glossary = glossaryPayload(sem.getGlossary(), answeredTerms);
        }
        List<Map<String, Object>> ambiguities = ambiguityPayload(connectorId, answeredTerms);

        boolean anySemantic = false;
        List<Map<String, Object>> entries = new ArrayList<>();
        if (view != null && view.entries() != null) {
            for (CatalogEntry e : view.entries()) {
                if (e == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.name());
                m.put("type", e.type());
                m.put("comment", e.comment());
                ConnectorSemantic s = objects.get(e.name());
                if (s != null && s.getGloss() != null && !s.getGloss().isBlank()) {
                    // 这里给【短】版本：选表那一刻需要的就是一句话，完整版在 conn_describe 给。
                    // 没有语义行时这两个 key 干脆不出现——注入 null 只是把噪声塞进模型上下文。
                    m.put("semantic", ConnectorSemanticService.shortGloss(s.getGloss()));
                    m.put("semantic_status", s.getStatus());
                    anySemantic = true;
                }
                entries.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("object_kind", view == null ? null : view.objectKind());
        if (anySemantic || !glossary.isEmpty() || !ambiguities.isEmpty()) {
            out.put("semantic_hint", SEMANTIC_HINT);
        }
        // ★ 口径排在 entries 【前面】不是排版问题：销售额扣不扣退款，决定了要不要 join 退款表，
        //   也就决定了接下来要选哪几张表。等模型选完表再看到口径，那个决策点已经过去了。
        if (!glossary.isEmpty()) {
            out.put("glossary", glossary);
        }
        if (!ambiguities.isEmpty()) {
            out.put("ambiguities", ambiguities);
        }
        out.put("count", entries.size());
        out.put("total", view == null ? entries.size() : view.total());
        out.put("entries", entries);
        boolean truncated = view != null && view.truncated();
        out.put("truncated", truncated);
        if (truncated) {
            // 把【按什么排的】也告诉模型，而不只是「被截断了」。这两句缺一不可：
            // 排序从字母序改成了按重要性（估算行数的数量级），于是「没出现在这份目录里」的含义
            // 也跟着变了——从前是「名字排在后面」，现在是「这张表很小」。而一张小表恰恰可能是
            // 字典表、配置表这类 join 时绕不开的东西。模型知道漏掉的是小表，才会想到直接按名字
            // conn_describe 去问；以为漏掉的是「z 开头的」，它只会去猜名字。
            String ordering = view == null || view.ordering() == null || view.ordering().isBlank()
                    ? null : view.ordering();
            out.put("warning", "对象太多，目录已被截断，这不是全部对象"
                    + (ordering == null ? "" : "；本次是" + ordering + "，所以没列出来的是排在后面的那一批")
                    + "。找不到想要的对象时，可以直接用 conn_describe 按名字查，或向用户确认名称，"
                    + "不要断言「系统里没有这张表」。");
        }
        return out;
    }

    private Map<String, Object> doDescribe(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        String object = requireString(args, "object");
        ObjectDetail detail = connectorGateway.execute(connector, Capability.DESCRIBE, TOOL_DESCRIBE,
                session -> describeCapable(session).describe(object));

        // 用网关回来的真实对象名去查语义层，而不是模型传进来的那个：大小写、别名对不上时
        // 查不到语义只是"少注入一点"，但对齐了就能少一类"明明有说明书却没出现"的怪事。
        String objectName = detail == null || detail.name() == null ? object : detail.name();
        Long connectorId = resolveConnectorId(connector);
        Map<String, ConnectorSemantic> fieldSemantics = Map.of();
        List<Map<String, Object>> joins = List.of();
        if (connectorId != null) {
            ConnectorSemanticService.ObjectSemantics sem = semanticService.forObject(connectorId, objectName);
            if (sem.getFields() != null) fieldSemantics = sem.getFields();
            joins = joinPayload(sem.getJoins());
        }

        boolean anySemantic = !joins.isEmpty();
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
                ConnectorSemantic s = fieldSemantics.get(f.name());
                if (s != null) {
                    boolean stale = ConnectorSemanticService.ST_STALE.equals(s.getStatus());
                    if (s.getGloss() != null && !s.getGloss().isBlank()) {
                        // 这里给【完整】gloss，不截断。catalog 截到 80 字是为了选表那一刻不刷屏，
                        // describe 本来就是"要看细节"的地方，再截就把该看的那半句截没了。
                        m.put("semantic", s.getGloss().trim());
                        if (stale) {
                            m.put("semantic_note", DRIFT_NOTE);
                        }
                        anySemantic = true;
                    }
                    // ★ 值域和 gloss 【互相独立】，不能绑在一起判：S4 可能给一列采到了完整取值集合，
                    //   而 S2 压根没给这一列写出一句说明（反过来也一样）。写成 else / 嵌套在 gloss 里，
                    //   表现是"这列的取值集合凭空不见了"，而且没有任何报错。
                    Map<String, Object> domain = valueDomainPayload(readDetail(s), stale);
                    if (domain != null) {
                        m.put("value_domain", domain);
                        anySemantic = true;
                    }
                }
                fields.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("object", detail == null ? object : detail.name());
        out.put("type", detail == null ? null : detail.type());
        out.put("comment", detail == null ? null : detail.comment());
        if (anySemantic) {
            out.put("semantic_hint", SEMANTIC_HINT);
        }
        if (!joins.isEmpty()) {
            out.put("joins", joins);
        }
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

    // ------------------------------------------------------------------ 第七个工具：口径沉淀

    /**
     * 把用户刚刚澄清的一条业务口径记下来，下次不必再问。
     *
     * <h4>为什么它必须是一次显式工具调用</h4>
     * 口径（销售额扣不扣退款、客户指下单人还是付款方）<b>根本不在客户的数据库里</b>——它在一份财务口径文档里，
     * 或者在某个人脑子里。所以它只能靠问，而问完之后必须落库，否则每一轮对话都在重新问同一句话，
     * 而且每次的答案不保证一致。至于"为什么不嗅探对话自动落库"，见类注释。
     *
     * <h4>它不碰客户系统，所以不走网关，但授权照判</h4>
     * 网关那一趟会真的开一条到客户库的连接，而这里一行客户数据都不读，为一次元数据写入去占一个连接是浪费。
     * 代价是 4～7 步（Agent 授权 / 状态 / 能力 / 限流）都不会跑，所以这里自己补上最要紧的那一步：
     * 连接名必须出现在 {@code listAuthorized()} 里。
     *
     * <h4>{@code answeredName} 目前恒为 null</h4>
     * 见 {@code currentUserId()} 的注释：本类拿得到的是 user-id，拿不到显示名。
     */
    private Map<String, Object> doDefineMetric(Map<String, Object> args) {
        String connector = requireString(args, "connector");
        String term = requireString(args, "term");
        String definition = requireString(args, "definition");
        // 空 / 纯空白由 requireString 挡掉了（它要求非空白字符串并 trim），这里只补长度。
        // ★ 长度必须在【进 service 之前】判死，而且【不替它截断】：
        //   不判：超长值一路走到 MySQL，回来是一句原始 SQL 报错，既不是 ServiceException 也没归过类，
        //        被兜底 catch 归成 UPSTREAM_ERROR「目标系统返回了错误」——而这个工具压根没碰客户系统，
        //        于是模型掉头去排查客户的数据库，那条口径还悄悄丢了。
        //   不截断：截短的口径就是错的口径（「不扣退款」截成「不扣退」照样读得通，而意思反了），
        //        口径必须原样是用户说的那句话，宁可让模型重说一遍。
        requireWithinLimit(term, TERM_MAX, "term", "口径词条");
        requireWithinLimit(definition, GLOSS_MAX, "definition", "口径说明");
        List<String> appliesTo = stringListOrEmpty(args.get("applies_to"));

        requireAuthorizedName(connector);
        Long connectorId = resolveConnectorId(connector);
        if (connectorId == null) {
            throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND, unauthorizedMessage(connector));
        }

        Map<String, Object> detail = null;
        if (!appliesTo.isEmpty()) {
            detail = new LinkedHashMap<>();
            detail.put("applies_to", appliesTo);
        }

        Long userId = currentUserId();
        ConnectorSemantic row;
        try {
            row = semanticService.defineMetric(connectorId, term, definition, detail,
                    userId == null ? null : String.valueOf(userId), null,
                    MDC.get(MdcContextFilter.MDC_TRACE_ID));
        } catch (ServiceException e) {
            // ServiceException 的文案是我们自己写的（"口径词条不能为空"这种），不含客户库信息，
            // 可以原样回给模型；归到 CONFIG_ERROR 是因为它一定是入参的问题，改了就能过。
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, e.getRespMsg());
        }

        String basis = metricBasis(row);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("term", row.getTerm());
        out.put("definition", row.getGloss());
        if (!appliesTo.isEmpty()) {
            out.put("applies_to", appliesTo);
        }
        out.put("saved", true);
        out.put("basis", basis);
        // ★ 这段话是写给模型看的，措辞是有意的：亮出依据这件事必须在【每次引用】时都做，
        //   因为平台没有管理台的口径纠正入口——回答里那半句「（口径：扣除退款，X月X日确认）」
        //   是口径写错了之后，唯一可能被人看见并纠正的地方。
        out.put("message", "这条口径已记住，之后在这条连接上不必再问。以后凡是回答涉及「" + row.getTerm()
                + "」，都要按这个口径写查询，并在答案里把依据亮出来，例如「（口径：" + ConnectorSemanticService.shortGloss(row.getGloss())
                + "，" + basis + "）」。如果用户此刻纠正了这个口径，再调一次本工具覆盖它即可。");
        return out;
    }

    // ------------------------------------------------------------------ 语义层注入

    /**
     * 连接名 → {@code connection.id}。租户过滤由 {@code JimengTenantLineHandler} 注入
     * （{@code connection} 在 {@code TENANT_AWARE_TABLES} 里），所以这里不写 eq(tenantId)——
     * 与 {@code ConnectorGateway.findByName} 保持同一种做法，写两遍反而会在将来改白名单时分叉。
     *
     * <p>查不到一律返回 null 而不是抛：语义层是叠加的注解，不是必需品。
     * 用一个可选增强的故障去否决"看目录"这个必要功能是错的。
     */
    private Long resolveConnectorId(String connectorName) {
        try {
            Connection row = connectionMapper.selectOne(new LambdaQueryWrapper<Connection>()
                    .eq(Connection::getName, connectorName)
                    .last("LIMIT 1"));
            return row == null ? null : row.getId();
        } catch (Exception e) {
            log.warn("按名字解析连接 id 失败，本次不注入语义层 connector={}", connectorName, e);
            return null;
        }
    }

    /**
     * 口径表。{@code STALE} 的口径<b>整条不注入</b>，理由见方法内注释。
     *
     * @param answeredTerms <b>出参</b>：本次真的把答案发出去了的词条（已归一）。
     *                      只收这里真正 {@code add} 进 out 的那些——空 gloss 或 STALE 的 METRIC 行
     *                      没给出任何答案，对应的问题当然还得继续问。
     */
    private List<Map<String, Object>> glossaryPayload(List<ConnectorSemantic> metrics, Set<String> answeredTerms) {
        if (metrics == null || metrics.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectorSemantic m : metrics) {
            if (m == null || m.getTerm() == null || m.getTerm().isBlank()) continue;
            if (m.getGloss() == null || m.getGloss().isBlank()) continue;
            // ★ STALE 的口径必须【彻底停止注入】，不能像 OBJECT/FIELD/JOIN 那样带个标记继续给。
            //   口径里带着 SQL 片段（detail_json.sql_fragment），它引用的列一改，那段 SQL 就是错的——
            //   而错了不报错，照样返回一个看起来合理的数字。带标记继续给等于赌模型会读那个标记。
            //   这也意味着 STALE 的口径【不算答案】：它的 CAVEAT 要留着继续问。
            if (ConnectorSemanticService.ST_STALE.equals(m.getStatus())) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("term", m.getTerm());
            e.put("gloss", m.getGloss().trim());
            e.put("basis", metricBasis(m));
            out.add(e);
            answeredTerms.add(normalizeTerm(m.getTerm()));
        }
        return out;
    }

    /**
     * 词条归一：{@code trim} + {@code Locale.ROOT} 小写。
     *
     * <p>两处都不能省。{@code trim} 是因为库里那个唯一键的排序规则是 {@code utf8mb4_unicode_ci}
     * （PAD SPACE），「销售额」和「 销售额 」在库里<b>本来就是同一行</b>，注入时当成两个词
     * 会让退场失效。{@code Locale.ROOT} 是因为默认 locale 在土耳其语环境下会把
     * {@code "ROI"} 小写成 {@code "roı"}（点不见了的那个 i），于是同一份代码换个部署地就悄悄不匹配。
     */
    private static String normalizeTerm(String term) {
        return term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 说不清的地方（{@code CAVEAT}）。这里注入的是<b>问题</b>，不是答案——
     * {@code status tinyint} 取值 0/1/2/3 只能说"含义需确认"，绝不能说"0=待支付"：
     * 后面那句完全合理、完全可能错，而且没有任何验证能发现它错。
     *
     * <h4>为什么直接走 mapper，而不是 {@code ConnectorSemanticService.all()}</h4>
     * <ol>
     *   <li><b>量</b>：{@code all()} 不带 scope，会把这条连接的<b>每张表的每个字段</b>（动辄几百行）
     *       整批拉回来，只为在内存里挑出几条 CAVEAT。而 {@code conn_catalog} 的工具描述写着
     *       「很便宜，可以放心先调」——模型真的会先调它，而且每轮都调。这里按
     *       {@code (connector_id, scope)} 取，正好命中 {@code idx_connector_semantic_conn_scope}。</li>
     *   <li><b>缝</b>：{@code all()} 里的 {@code requireOwned()} 是<b>管理台</b>那条路的归属校验。
     *       模型路径根本不该借它——连接归属在上面那趟网关调用里早判完了，再判一次只是多一次
     *       {@code selectById}，还把"读注入"和"管理台读"两条路绑死在同一个签名上。</li>
     * </ol>
     * 租户过滤照旧由 {@code JimengTenantLineHandler} 注入（{@code connector_semantic} 在
     * {@code TENANT_AWARE_TABLES} 里），与 {@code ConnectorSemanticService} 内部那两个查询同一种写法。
     * 等它哪天长出一个按 scope 取的注入用方法，这里应当换过去。
     *
     * @param answeredTerms 本次 glossary 已经给出答案的词条（已归一），命中的问题直接不注入
     */
    private List<Map<String, Object>> ambiguityPayload(Long connectorId, Set<String> answeredTerms) {
        if (connectorId == null) return List.of();
        List<ConnectorSemantic> rows;
        try {
            rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getConnectorId, connectorId)
                    .eq(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_CAVEAT));
        } catch (Exception e) {
            log.warn("读取语义层告诫失败，本次不注入 connectorId={}", connectorId, e);
            return List.of();
        }
        if (rows == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectorSemantic r : rows) {
            if (r == null) continue;
            if (r.getGloss() == null || r.getGloss().isBlank()) continue;
            // ★ 已经有答案的词条，问题必须退场。退场做在【注入这一刻】而不是去删那行 CAVEAT，
            //   是刻意的：conn_define_metric 只写 METRIC，删掉的 CAVEAT 下一次重新推导还会被推回来，
            //   于是"答过的问题又冒出来"这个毛病会随着每次重推复发。在注入时过滤，扛得过重推。
            if (answeredTerms.contains(normalizeTerm(r.getTerm()))) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("term", caveatTerm(r));
            e.put("question", r.getGloss().trim());
            if (ConnectorSemanticService.ST_STALE.equals(r.getStatus())) {
                e.put("stale_note", DRIFT_NOTE);
            }
            out.add(e);
        }
        return out;
    }

    /**
     * 表关系。{@code REJECTED} 的关系<b>不注入</b>——它是被数据验过、验错了的那一条。
     *
     * <p>其余四种状态各出一条<b>不同的句子</b>，并且各自带一个不同的动作，理由见
     * {@link #verificationSentence}。{@code verified} 这一列同时<b>原样</b>给出去：
     * 中文句子是给模型读的，枚举值是给它照着分支的——措辞改写不会让分支跟着变。
     */
    private List<Map<String, Object>> joinPayload(List<ConnectorSemantic> joins) {
        if (joins == null || joins.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectorSemantic j : joins) {
            if (j == null) continue;
            if (ConnectorSemanticService.V_REJECTED.equals(j.getVerified())) continue;
            Map<String, Object> d = readDetail(j);
            Object toObject = d.get("to_object");
            Object toColumn = d.get("to_column");
            // 缺了任一端就不是一条能用的关系，给出去只会让模型拿它去拼一条编出来的 JOIN。
            if (toObject == null || toColumn == null) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("column", j.getFieldName());
            e.put("to_object", String.valueOf(toObject));
            e.put("to_column", String.valueOf(toColumn));
            String cardinality = stringOrNull(d.get("cardinality"));
            if (cardinality != null) {
                e.put("cardinality", cardinality);
            }
            // 右侧唯不唯一决定了这条关系能不能直接连——没测过就不写，别让模型把"没结论"读成"可以连"。
            Boolean autoJoinable = booleanOrNull(d.get("auto_joinable"));
            if (autoJoinable != null) {
                e.put("auto_joinable", autoJoinable);
            }
            if (j.getConfidence() != null) {
                e.put("confidence", j.getConfidence());
            }
            if (j.getVerified() != null && !j.getVerified().isBlank()) {
                e.put("verified", j.getVerified());
            }
            e.put("basis", joinBasis(j, d));
            // ★ fan-out 单独一个 key，不并进 basis：basis 回答"这条关系成不成立"，
            //   它回答"就算成立，这么连也会把数算错"。一条已验证通过的 1:N 关系两句话都要说，
            //   而合成一句之后，模型看到"验证通过"就会把后半句当成附注读过去。
            String fanout = fanoutWarning(autoJoinable, cardinality);
            if (fanout != null) {
                e.put("fanout_warning", fanout);
            }
            // ★ 与 basis 分成两个 key、两种措辞：basis 说的是"当初凭什么这么说"，
            //   stale_note 说的是"当初说得对，但结构后来变了"。合成一句，模型就只能按一种方式处理。
            if (ConnectorSemanticService.ST_STALE.equals(j.getStatus())) {
                e.put("stale_note", DRIFT_NOTE);
            }
            out.add(e);
        }
        return out;
    }

    /**
     * 一条关系的依据。<b>分界线是"有没有外部依据"，不是"模型自己说它有多确定"</b>，
     * 所以这里先说依据来源（注释 / 数据 / 名字），再说验没验过。
     */
    private static String joinBasis(ConnectorSemantic j, Map<String, Object> detail) {
        String evidence;
        if (ConnectorSemanticService.EV_COMMENT.equals(j.getEvidence())) {
            evidence = "客户库自己的注释";
        } else if (ConnectorSemanticService.EV_DATA.equals(j.getEvidence())) {
            evidence = "库里的数据";
        } else if (ConnectorSemanticService.EV_NAME.equals(j.getEvidence())) {
            evidence = "列名本身";
        } else {
            evidence = "常识推测，没有外部依据";
        }
        return "依据：" + evidence + "；" + verificationSentence(j.getVerified(), detail);
    }

    /**
     * 验证结论那半句。<b>四种状态必须是四句不同的话，而且各自带一个不同的动作。</b>
     *
     * <p>P1 时期只有"未经数据验证"一种说法，因为那时根本没有验证这回事。S3 之后
     * 一条关系可能是<b>验过且成立</b>、<b>验过但只部分成立</b>、<b>验过但判不出来</b>、
     * <b>压根没验</b>——把它们说成同一句话，模型对四种情况就只能做同一件事，
     * 那等于白验：CONFIRMED 也要再跑一次 COUNT（白花客户的资源），
     * UNDECIDABLE 却被当成"没验过"（它其实已经花过一次探查，而且结论是"这张表太空，判不出来"，
     * 再跑一次 COUNT 得到的还是判不出来）。
     *
     * <p>★ {@code V_NONE} 是"没探查过"，{@code V_UNDECIDABLE} 是"探查过、判不出来"。
     * 这两个<b>永远不许说成同一句</b>——它们对下一步的指示不同。
     *
     * @param detail 语义行的 {@code detail_json}，S3 往里写了 sample_n / match_n / containment /
     *               verify_note；缺了它们只是少一句量化说明，不影响这四句话本身的区分度
     */
    private static String verificationSentence(String verified, Map<String, Object> detail) {
        String measured = measuredSentence(detail);
        String suffix = measured == null ? "" : "（" + measured + "）";
        if (ConnectorSemanticService.V_CONFIRMED.equals(verified)) {
            return "已用真实数据采样验证通过" + suffix + "，可以直接使用，不必再为它单跑一次 COUNT 自验";
        }
        if (ConnectorSemanticService.V_WEAK.equals(verified)) {
            return "已用真实数据采样验证，但数据只能【部分】支持这条关系" + suffix
                    + "，说明有一部分取值在对面根本找不到。依赖它之前先自己跑一条 COUNT 核一次，"
                    + "并在结论里说明这条关联只是部分成立";
        }
        if (ConnectorSemanticService.V_UNDECIDABLE.equals(verified)) {
            String why = stringOrNull(detail.get("verify_note"));
            return "已经用真实数据查过了，但判不出来（" + (why == null ? "样本不足或探查未能完成" : why)
                    + "）。这不等于这条关系不成立，只是这次没能判定；依赖它之前先自己跑一条 COUNT 核一次";
        }
        return "未经数据验证，依赖它之前先跑一条 COUNT 自验";
    }

    /**
     * 量化那一句，形如「采样 1000 个取值，命中 970（包含率 97.0%）」。没有实测数字时返回 null。
     *
     * <p>刻意<b>自己按数字拼</b>，而不是直接把 {@code detail_json.basis} 倒给模型：那一份是给人和
     * 管理台看的细账，里面还夹着 fan-out 说明，而 fan-out 在本类是单独一个 key。
     */
    private static String measuredSentence(Map<String, Object> detail) {
        Long sampleN = longOrNull(detail.get("sample_n"));
        Long matchN = longOrNull(detail.get("match_n"));
        if (sampleN == null || matchN == null) return null;
        StringBuilder sb = new StringBuilder("采样 ").append(sampleN).append(" 个取值，命中 ").append(matchN);
        Double containment = doubleOrNull(detail.get("containment"));
        if (containment != null) {
            sb.append("（包含率 ").append(String.format(Locale.ROOT, "%.1f", containment * 100d)).append("%）");
        }
        return sb.toString();
    }

    /**
     * fan-out 告警。<b>这是本次注入里唯一一条「关系成立、但照着连仍然会算错」的提示。</b>
     *
     * <p>右侧那一列不唯一时，join 会把左表一行放大成多行，{@code SUM} / {@code COUNT} 跟着变大，
     * <b>而且不报任何错</b>——一个凭空变大的金额，看起来和一个正确的金额没有任何区别。
     *
     * <p>判据优先看实测的 {@code auto_joinable}（S3 按右侧唯一性测出来的）；没有实测就退回基数字面量，
     * 哪怕那个基数只是模型自己标的：这里<b>宁可多警告一次</b>，漏掉的代价是一个悄悄错掉的金额。
     */
    private static String fanoutWarning(Boolean autoJoinable, String cardinality) {
        if (SemanticJoinValidator.CARD_MANY_MANY.equals(cardinality)) {
            return "两侧都不唯一（N:N）：直接 join 会让左表的一行匹配到右表的多行，行数成倍放大，"
                    + "SUM / COUNT 出来的数会凭空变大而且不会报错。只把它当线索，不要直接 join；"
                    + "确实要用就先在一侧按连接键聚合或去重，再连。";
        }
        if (SemanticJoinValidator.CARD_ONE_MANY.equals(cardinality)) {
            return "右侧不是唯一键（1:N）：左表一行会匹配到右表多行，join 之后行数被放大，"
                    + "再做 SUM / AVG 就会算错而且不报错。先在右表上按连接键聚合，再拿聚合结果去 join。";
        }
        if (Boolean.FALSE.equals(autoJoinable)) {
            return "平台没能确认右侧这一列是唯一的：一行可能匹配到多行，join 会放大行数，"
                    + "SUM / COUNT 会跟着变大而不报错。动手前先用 COUNT 对一下 join 前后的行数。";
        }
        return null;
    }

    /**
     * 一列的取值集合（{@code value_domain}）。
     *
     * <h4>★ 「这列没有枚举值」和「没去查这列的枚举值」对模型的含义完全相反</h4>
     * 前者让它放心写死条件，后者应该让它去问、去查。所以<b>永远不输出空的 values 数组</b>：
     * 拿不到完整集合时只给 {@code complete=false} 和一句说明为什么没拿到的话。
     *
     * <h4>★ 只给集合，绝不给含义</h4>
     * {@code st} 的取值是 0/1/2/3 可以给；「0=待支付」<b>永远不给</b>——后面那句完全合理、
     * 完全可能错，而且没有任何验证能发现它错，它不会让查询报错，只会让一个数字悄悄算错。
     * 本方法因此只搬运取值本身和平台写好的那句说明，不产生任何一个字的解释。
     *
     * @param stale 这一行挂着的列结构已经变过。此时<b>连取值都不给</b>：
     *              当初采到的那一组值属于一个已经不存在的列形状，照着它写死条件比不给更糟
     */
    private static Map<String, Object> valueDomainPayload(Map<String, Object> detail, boolean stale) {
        Object raw = detail.get(SemanticValueProfiler.DETAIL_KEY);
        if (!(raw instanceof Map<?, ?> fragment) || fragment.isEmpty()) return null;

        Map<String, Object> out = new LinkedHashMap<>();
        if (stale) {
            out.put("complete", false);
            out.put("note", VALUE_DOMAIN_STALE_NOTE);
            return out;
        }

        List<String> values = valueList(fragment.get("values"));
        boolean claimsComplete = Boolean.TRUE.equals(fragment.get("complete"));
        boolean complete = claimsComplete && !values.isEmpty();
        out.put("complete", complete);
        Long distinct = longOrNull(fragment.get("distinct_count"));
        if (distinct != null) {
            out.put("distinct_count", distinct);
        }
        if (complete) {
            out.put("values", values);
        }
        out.put("note", valueDomainNote(fragment, claimsComplete, complete));
        return out;
    }

    /**
     * 这一列为什么是这个结论，一句话。
     *
     * <p>优先用采集时写下的那句（{@code SemanticValueProfiler} 生成，带着"共几个取值""判据是什么"
     * 这类细节）；缺了就按 {@code outcome} 还原成 {@code Outcome.modelNote()}——
     * 九种结局各有一句<b>已经写好</b>的话，在这里重新编一套措辞只会和采集侧分叉。
     */
    private static String valueDomainNote(Map<?, ?> fragment, boolean claimsComplete, boolean complete) {
        if (claimsComplete != complete) {
            // 写着"完整"却一个取值都没有：这两件事必有一个是错的，一律按"没拿到"处理，
            // 而且【不能】沿用那句写着"以下是全部取值"的说明——它下面已经没有取值了。
            return VALUE_DOMAIN_BROKEN_NOTE;
        }
        String stored = stringOrNull(fragment.get("note"));
        if (stored != null) return stored;
        String derived = outcomeNote(fragment.get("outcome"));
        if (derived != null) return derived;
        return complete ? VALUE_DOMAIN_COMPLETE_NOTE : VALUE_DOMAIN_UNKNOWN_NOTE;
    }

    /** {@code outcome} 还原成采集侧写好的那句话；认不出来返回 null，绝不自己编一句。 */
    private static String outcomeNote(Object outcome) {
        String name = stringOrNull(outcome);
        if (name == null) return null;
        try {
            return SemanticValueProfiler.Outcome.valueOf(name).modelNote();
        } catch (IllegalArgumentException e) {
            // 库里存了一个本版本不认识的结局。少一句说明，但绝不能猜它是什么意思。
            return null;
        }
    }

    /** 取值集合。非字符串的取值（数字、布尔）一律转成字符串给出去，形状固定模型才好用。 */
    private static List<String> valueList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 一条口径的依据，形如「9月13日由张三确认」。
     * <b>它是给人看的</b>：平台不提供管理台的口径纠正入口，回答里亮出的这半句，
     * 是口径被改坏之后唯一可能被业务方看见并纠正的地方。
     */
    private static String metricBasis(ConnectorSemantic m) {
        String who = m.getAnsweredName() == null || m.getAnsweredName().isBlank()
                ? "由用户在对话中确认" : "由" + m.getAnsweredName() + "确认";
        if (m.getAnsweredAt() != null) {
            return monthDay(m.getAnsweredAt()) + who;
        }
        if (ConnectorSemanticService.SOURCE_IMPORTED.equals(m.getSource())) {
            return "由管理员导入";
        }
        if (ConnectorSemanticService.SOURCE_HUMAN.equals(m.getSource())) {
            return who;
        }
        return "平台推断，未经人工确认";
    }

    private static String monthDay(Date at) {
        LocalDate d = at.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    /** CAVEAT 挂在哪儿：口径词条 → 表.字段 → 表。三者都没有时给个不会被误读成表名的占位。 */
    private static String caveatTerm(ConnectorSemantic r) {
        if (r.getTerm() != null && !r.getTerm().isBlank()) return r.getTerm();
        String object = r.getObjectName() == null ? "" : r.getObjectName();
        String field = r.getFieldName() == null ? "" : r.getFieldName();
        if (!object.isBlank() && !field.isBlank()) return object + "." + field;
        if (!object.isBlank()) return object;
        return "整条连接";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(ConnectorSemantic r) {
        if (r.getDetailJson() == null || r.getDetailJson().isBlank()) return Map.of();
        try {
            return CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
        } catch (Exception e) {
            // detail 坏了只影响这一条关系，不该让整次 describe 失败。
            log.debug("解析语义 detail 失败 id={}", r.getId(), e);
            return Map.of();
        }
    }

    /**
     * 当前用户 id。{@code AdminRequestContext} 是纯静态工具类，不是 bean——不违反构造器注入那条约束。
     *
     * <p>对话跑在 {@code streamExecutor} 上，{@code RequestContextHolder} 不会自动传过去，
     * 但 {@code MdcAsyncSupport.wrap} 在请求线程捕获了 userId 并显式捎带，所以这里拿得到。
     * <b>拿不到就返回 null，不抛</b>：口径本身比"谁答的"重要得多，为了记不下人而拒绝记口径是本末倒置。
     *
     * <p><b>拿不到的是显示名</b>：它在 {@code sys_user.display_name}，而那张表<b>不在</b>
     * {@code TENANT_AWARE_TABLES} 里（登录早于 TenantContext，按 username 全局解析），
     * 取它必须自己显式拼 {@code WHERE tenant_id=?}——在这个类里现搓一个跨租户读用户表的查询，
     * 风险大于收益。所以 {@code answered_name} 暂时留空，basis 退化成"由用户在对话中确认"，
     * {@code answered_by} 与 {@code trace_id} 仍然存着，管理台照样能查到是谁、在哪次对话里答的。
     */
    private static Long currentUserId() {
        try {
            return AdminRequestContext.findUserIdOrNull();
        } catch (Exception e) {
            log.debug("获取当前用户失败，口径按匿名记录", e);
            return null;
        }
    }

    /**
     * 授权校验（{@code conn_define_metric} 专用）。措辞与 {@code ConnectorGateway.findByName}
     * <b>刻意一致</b>：「不存在」与「未授权」必须同形，否则可以靠报错差异枚举出本租户有哪些连接名。
     */
    private void requireAuthorizedName(String connectorName) {
        List<ConnectorSummary> authorized = connectorGateway.listAuthorized();
        if (authorized != null) {
            for (ConnectorSummary s : authorized) {
                if (s != null && connectorName.equalsIgnoreCase(s.name())) {
                    return;
                }
            }
        }
        throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND, unauthorizedMessage(connectorName));
    }

    private static String unauthorizedMessage(String connectorName) {
        return "没有名为「" + connectorName + "」的连接，或当前 Agent 未被授权使用它。请先用 conn_list 查看可用连接";
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

    /**
     * 列宽校验。<b>按 code point 数，不按 {@code String.length()}</b>：MySQL 的 {@code varchar(N)}
     * 数的是字符，而 Java 把辅助平面的字（emoji、生僻汉字）算两个 {@code char}——按 {@code length()}
     * 判会在真正超限之前就误拒，而误拒的表现是用户答了一句合法的口径却被告知太长。
     *
     * <p>报错文案里<b>不回显原值</b>：一段几千字的入参再原样灌回模型上下文只是噪声，
     * 而模型手里本来就有它刚发出去的那个值。
     */
    private static void requireWithinLimit(String value, int max, String key, String label) {
        int len = value.codePointCount(0, value.length());
        if (len > max) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    label + "（参数 " + key + "）最多 " + max + " 字，本次是 " + len + " 字，这条口径没有被保存。"
                            + "平台不会替你截断——截短的口径就是错的口径。请把它改短后重新调用本工具。"
                            + "这是入参问题，与客户的数据库无关，不要去排查那边。");
        }
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

    /** {@code detail_json} 里的值一律当"可能是任何东西"来读：它可能被手工改过，也可能来自更早的版本。 */
    private static String stringOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** JSON 里的布尔可能是 {@code true}，也可能是字符串 {@code "true"}。认不出来返回 null，不猜。 */
    private static Boolean booleanOrNull(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s.trim())) return Boolean.FALSE;
        }
        return null;
    }

    private static Long longOrNull(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleOrNull(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
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

    /** 模型偶尔会把单元素数组写成裸字符串，顺手认一下——认不了就明确报错，不静默丢掉。 */
    private static List<String> stringListOrEmpty(Object v) {
        if (v == null) return List.of();
        if (v instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s.trim());
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "参数 applies_to 必须是字符串数组");
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
