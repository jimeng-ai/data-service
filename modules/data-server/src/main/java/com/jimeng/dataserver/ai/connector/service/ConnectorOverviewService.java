package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「默认注入目录」：把<b>已授权连接器的表清单 + 已确认的口径</b>直接放进 system 上下文，
 * 让模型不必先烧一次 {@code conn_catalog} 往返才知道库里有什么。
 *
 * <h3>数据只来自我们自己的库</h3>
 * 概览全部由 {@code connector_schema}（快照）+ {@code connector_semantic}（说明书）拼出来，
 * <b>绝不为了注入去打一次客户的生产库</b>。这是与 {@code conn_catalog} 的根本区别：那个工具读实时，
 * 因为模型明确要看目录；而注入发生在<b>每一次请求</b>上，把它接到客户库上等于客户每问一句话
 * 我们就 ping 一次他们的生产库。
 *
 * <h3>★ 它是快照，所以必须自报是快照</h3>
 * 快照可能落后于真实结构。注入文本里明写「权威结构以 conn_describe 为准，找不到的表用
 * conn_catalog 再查」——不写这句，模型会把一份可能过期的清单当成真相，写出引用已删除列的 SQL，
 * 或者更糟：对着一张快照里没有的表说「系统里没有这张表」。
 *
 * <h3>★ 这个类必须保持"不能回到模型"</h3>
 * 它被 {@code SkillRuntimeService} 注入，而那条链是
 * {@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService}。
 * 本类只注入四个 mapper 和 {@code ConnectorProperties}（一个 {@code @ConfigurationProperties} 叶子），
 * 没有任何一条边能回到 {@code ProviderRegistry} / {@code ClaudeService} / {@code ChatClient}，
 * 所以环闭不上。<b>以后往这里加依赖，先把这条链重新走一遍</b>——仓库已经被同一个环咬过两次。
 * 特别地：不要图省事注入 {@code ConnectorGateway}（它拖着 registry / loader / audit / redis 一串东西）
 * 或 {@code ConnectorSemanticService}（它的 {@code all()} 会拉回整库 detail_json）。
 *
 * <h3>它还负责这一轮的「确定性口径命中」</h3>
 * 除了表清单，本类还按<b>用户这一轮说的话</b>做一次口径精确命中（{@link MetricRewriter}），
 * 命中了就把那几条口径的定义摆到模型面前。放在这里而不是另起一个服务，理由是取数完全重合：
 * 授权判定、租户判定、按连接取 {@code METRIC} 行，三件事一模一样——分成两个类就是把同样的
 * 三次查询在<b>每一次请求</b>上各打一遍。
 *
 * <p><b>★ 口径必须按连接分别命中，一条连接一次 {@code detect}。</b>
 * 产出的那段文字里没有「这是哪条连接的口径」这一栏，把两条连接的 METRIC 行合并传进去，
 * 同一个词就会出现两条互相矛盾的定义，而模型无从区分该用哪条——它会挑一条，
 * 你不知道它挑了哪条。所以这里逐条连接各调一次，并在每段前面写明这是哪个数据源的口径。
 *
 * <p><b>★ 目录概览关掉，不连带关掉口径命中。</b>{@code connector.overview.enabled=false}
 * 说的是「别注入表清单」——那是一个省往返的<b>优化</b>；而口径命中是<b>正确性机制</b>
 * （「口径这类必须 100% 正确的东西，用确定性规则做」）。让前者的开关顺手关掉后者，
 * 表现是有人为了省上下文关掉概览，销售额的口径从此静默失效、而且没有任何信号。
 *
 * <h3>失败一律不注入</h3>
 * 没租户、没 Agent、没授权、查库炸了——统统 warn 一行然后返回 null。
 * 这是一个省往返的优化，不是一道闸：它坏掉的正确表现是「模型多调一次 conn_catalog」，
 * 而不是「这轮对话报错」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorOverviewService {

    /**
     * 免责声明。<b>它永远完整注入，不参与 {@code maxChars} 的截断</b>——
     * 预算砍掉的是表清单，砍掉声明等于把"这是快照"这件事从上下文里删掉，
     * 而那恰恰是清单被截断之后最需要模型知道的一句话。
     */
    private static final String HEADER = """
            【已接入数据源的表清单（平台缓存的概览，非实时）】
            下面是平台侧缓存的结构快照，直接给你，省掉一次 conn_catalog 往返。使用它有三条硬约束：
            - 这是快照，不是权威结构。真要用某张表时，字段 / 类型一律以 conn_describe 的返回为准。
            - 这里找不到想要的表，就用 conn_catalog 再查一次真实目录；在查过之前，不要断言「系统里没有这张表」。
            - comment 是客户自己写在库里的一手事实，语义是平台维护的二手理解（可能过时、也可能推错）；两者冲突时以 comment 为准。""";

    private static final String EMPTY = "";

    private final AgentConnectionMapper agentConnectionMapper;
    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorProperties properties;
    /**
     * 口径命中。<b>它没有任何注入依赖</b>（匹配是纯函数，候选行由本类查好传进去），
     * 所以这条新边过得了类注释里那条「不能回到 {@code ProviderRegistry}」的判据。
     */
    private final MetricRewriter metricRewriter;

    // ================================================================ 对外

    /**
     * 拼出本次请求要注入的概览文本；没有可注入的内容（或任何一步失败）时返回 {@code null}。
     *
     * <p>{@link #buildRequestContext} 的「只要表清单」版本：不传用户这一轮的话，也就不做口径命中。
     * 请求路径上应当用那一个（一趟取数出两段文字）；这里保留是给只关心目录的调用方与用例。
     *
     * <p>调用方只需判 {@code null}，不需要 try/catch——本方法自己把所有异常吞在里面。
     */
    public String buildOverview() {
        return buildRequestContext(null).getOverview();
    }

    /**
     * 本次请求要注入的全部连接器上下文：表清单概览 + 这一轮命中的口径。
     *
     * <p><b>★ {@code currentUserText} 只传用户<b>这一轮</b>说的话</b>，不要把对话历史拼进来。
     * 传历史的后果很具体：三轮前提过一次「销售额」，之后每一轮都会把它重新注入一遍，
     * 于是口径命中退化成「每次都提供给大模型作参考」——被引用的官方建议明确说那不是最优配置，
     * 真正相关的那一条会被十几条无关定义稀释掉。
     *
     * <p>两段都可能是 {@code null}（没内容 / 关掉了 / 出错了），调用方各自判空即可，
     * <b>不需要 try/catch</b>：本方法自己把所有异常吞在里面。
     */
    public RequestContext buildRequestContext(String currentUserText) {
        // 连接器总开关关掉时一个字都不注入：那时 conn_* 工具本来就会明确报错，
        // 先给模型一份表清单再让它每次调用都失败，是最糟的组合。
        if (!properties.isEnabled()) return RequestContext.empty();

        ConnectorProperties.Overview cfg = properties.getOverview();
        // 预算配成 0 / 负数 = 显式关掉，与 enabled=false 等价。
        boolean overviewWanted = cfg != null && cfg.isEnabled() && cfg.getMaxChars() > 0;
        boolean metricWanted = !isBlank(currentUserText);
        // ★ 两件事都不要的时候必须【在任何一次查询之前】退出：概览关掉本来就是零查询，
        //   不能因为多接了一个口径命中，就让关掉概览的部署每轮白打三次查询。
        if (!overviewWanted && !metricWanted) return RequestContext.empty();

        try {
            // ★ fail-closed，且与 ConnectorGateway.grantedConnectionIds 同形：没有 Agent 上下文
            //   （直连 /data/claude/messages 不带 agent_id）就是"谁也没授权"，不是"不过滤"。
            AgentRuntimeView agent = AgentContext.get();
            if (agent == null || agent.getAgentId() == null) return RequestContext.empty();
            if (!TenantContext.isSet()) {
                // 没有租户上下文时 MyBatis 的哨兵会让查询命中 0 行——那和"这个租户没有连接器"
                // 长得一模一样。这里明说一声，否则上下文丢失会表现为"概览莫名其妙不出现了"。
                log.warn("连接器概览：无租户上下文，本次不注入 agentId={}", agent.getAgentId());
                return RequestContext.empty();
            }

            Set<Long> granted = grantedConnectionIds(agent.getAgentId());
            if (granted.isEmpty()) return RequestContext.empty();   // 没授权连接器 → 一个字节都不注入

            List<Connection> conns = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                    // 投影：概览只用得上这四列，而 connection 行上挂着 credential_cipher / config_json。
                    .select(Connection::getId, Connection::getName, Connection::getDisplayName, Connection::getKind)
                    .in(Connection::getId, granted)
                    .eq(Connection::getStatus, "ACTIVE")
                    .orderByAsc(Connection::getName));
            if (conns == null || conns.isEmpty()) return RequestContext.empty();

            Set<Long> ids = new LinkedHashSet<>();
            for (Connection c : conns) ids.add(c.getId());

            Map<Long, Glosses> glosses = loadSemantics(ids);

            String overview = null;
            if (overviewWanted) {
                // 表清单只在真要用的时候才查：这一趟是几个连接器的全部对象行，比口径那一趟重得多。
                overview = render(conns, loadObjects(ids), glosses, cfg.getMaxChars());
                if (overview != null) {
                    log.debug("连接器概览已注入 agentId={} connectors={} chars={}",
                            agent.getAgentId(), conns.size(), overview.length());
                }
            }
            String metrics = metricWanted ? renderMetricContext(conns, glosses, currentUserText) : null;
            return RequestContext.builder().overview(overview).metricContext(metrics).build();
        } catch (Exception e) {
            // 每一次请求都会走到这里。它是叠加的优化，坏掉的正确表现是"模型多调一次 conn_catalog"。
            log.warn("构建连接器上下文失败，本次不注入", e);
            return RequestContext.empty();
        }
    }

    // ================================================================ 取数

    /**
     * 这个 Agent 有没有被授权任何外部连接。
     *
     * <p>给 {@code SkillRuntimeService} 判定 {@code requires: connections} 用：决定 connector 这个
     * 平台 Skill 是<b>提升为直接注入</b>还是<b>整个隐藏</b>。
     *
     * <h4>为什么单开一个方法，而不是让调用方去看 {@link #buildRequestContext} 返回空</h4>
     * 那个方法在判授权之前还会被三个开关短路（连接器总开关、概览开关、本轮有没有用户文本），
     * 拿它的"空"当"没授权"，会让<b>关掉概览注入</b>的部署连带把 conn_* 工具也一起弄丢——
     * 两件事完全无关。这里只回答授权这一个问题。
     *
     * <p>不判 {@code ACTIVE}：连接被停用时工具照常给，调用时由 {@code ConnectorGateway} 明确报错。
     * 反过来（悄悄抽掉工具）会让模型说"我没有查库的能力"，而真相是"有一条连接被停用了"——
     * 后者用户能去管理台看见并修，前者只会让人以为功能坏了。
     */
    public boolean hasGrantedConnections(Long agentId) {
        if (agentId == null) return false;
        return !grantedConnectionIds(agentId).isEmpty();
    }

    /** 与 {@code ConnectorGateway.grantedConnectionIds} 同形：授权只认 {@code agent_connection}。 */
    private Set<Long> grantedConnectionIds(Long agentId) {
        List<AgentConnection> binds = agentConnectionMapper.selectList(new LambdaQueryWrapper<AgentConnection>()
                .select(AgentConnection::getConnectionId)
                .eq(AgentConnection::getAgentId, agentId));
        Set<Long> out = new LinkedHashSet<>();
        if (binds == null) return out;
        for (AgentConnection b : binds) {
            if (b.getConnectionId() != null) out.add(b.getConnectionId());
        }
        return out;
    }

    /**
     * 表清单。
     *
     * <h4>★ 这里的 {@code select(...)} 投影不是优化，是这个特性能不能上线的前提</h4>
     * {@code connector_schema.detail_json} 是 <b>longtext</b>，装着一张表的全部字段、类型、注释、索引。
     * 一个 200 张表的库，整行 {@code selectList} 一次就是几 MB 从 MySQL 拉进堆里再整体丢掉——
     * 而 {@code applySkillContext} 是<b>每一次请求</b>都跑的（{@code AiConversationLoop} 的
     * 阻塞与流式两条路各一次）。不投影 = 给每一句用户消息加一次几 MB 的无用 IO 和一次大对象分配，
     * 这是实打实的性能回归，不是"顺手优化一下"。概览只需要 object_name / object_comment
     * （加上 object_type 用于区分视图、connector_id 用于分组），detail_json 一个字节都不该被读出来。
     */
    private Map<Long, List<ObjectRow>> loadObjects(Set<Long> connectorIds) {
        List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .select(ConnectorSchema::getConnectorId, ConnectorSchema::getObjectName,
                        ConnectorSchema::getObjectComment, ConnectorSchema::getObjectType)
                .in(ConnectorSchema::getConnectorId, connectorIds)
                .orderByAsc(ConnectorSchema::getObjectName));
        Map<Long, List<ObjectRow>> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (ConnectorSchema r : rows) {
            if (r == null || r.getConnectorId() == null || isBlank(r.getObjectName())) continue;
            out.computeIfAbsent(r.getConnectorId(), k -> new ArrayList<>())
                    .add(new ObjectRow(r.getObjectName().trim(), trimToNull(r.getObjectComment()),
                            trimToNull(r.getObjectType())));
        }
        return out;
    }

    /**
     * 语义层里注入前需要的两类：{@code OBJECT}（表用途）和 {@code METRIC}（口径）。
     *
     * <p>同样必须投影：{@code connector_semantic} 也有 {@code detail_json}，而且 FIELD 行是
     * 「每张表每个字段一行」的量级。这里按 {@code (connector_id, scope)} 取，命中
     * {@code idx_connector_semantic_conn_scope}，并且 <b>只取两类 scope</b>——
     * 注入目录用不到 FIELD / JOIN（那是 conn_describe 的事），拉回来只是白花 IO。
     */
    private Map<Long, Glosses> loadSemantics(Set<Long> connectorIds) {
        List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .select(ConnectorSemantic::getConnectorId, ConnectorSemantic::getScope,
                        ConnectorSemantic::getObjectName, ConnectorSemantic::getTerm,
                        ConnectorSemantic::getGloss, ConnectorSemantic::getStatus,
                        ConnectorSemantic::getSource, ConnectorSemantic::getAnsweredName,
                        ConnectorSemantic::getAnsweredAt)
                .in(ConnectorSemantic::getConnectorId, connectorIds)
                .in(ConnectorSemantic::getScope,
                        List.of(ConnectorSemanticService.SCOPE_OBJECT, ConnectorSemanticService.SCOPE_METRIC))
                .orderByAsc(ConnectorSemantic::getTerm));
        Map<Long, Glosses> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (ConnectorSemantic r : rows) {
            if (r == null || r.getConnectorId() == null || isBlank(r.getGloss())) continue;
            Glosses g = out.computeIfAbsent(r.getConnectorId(), k -> new Glosses());
            if (ConnectorSemanticService.SCOPE_OBJECT.equals(r.getScope())) {
                if (isBlank(r.getObjectName())) continue;
                g.objects.put(r.getObjectName().trim(), r);
            } else {
                if (isBlank(r.getTerm())) continue;
                // ★ STALE 的口径整条不注入，与 conn_catalog 的 glossaryPayload 同一条纪律：
                //   口径里可能带着 SQL 片段，它引用的列一改那段 SQL 就是错的——而错了不报错，
                //   照样返回一个看起来合理的数字。带个"可能过期"的标记继续给，等于赌模型会读那个标记。
                if (ConnectorSemanticService.ST_STALE.equals(r.getStatus())) continue;
                g.metrics.add(r);
            }
        }
        return out;
    }

    // ================================================================ 渲染

    /**
     * 渲染，并在 {@code maxChars} 用完时<b>如实说出来</b>。
     *
     * <h4>★ 为什么截断必须自报</h4>
     * 设计文档给的"概览约 1.2 KB"是在一个 35 张表的库上量的，不是保证。真遇到 300 张表的库，
     * 这段文本会挤掉真正的对话上下文，所以必须有预算。但<b>静默截断比不注入更糟</b>：
     * 一份被悄悄砍掉一半的清单，在模型看来和完整清单没有任何区别，于是它会对着列表里没有的表
     * 说「系统里没有这张表」——用户听到的是一句斩钉截铁的错话。所以砍了就要在文本里写明
     * 「只列出 N 张，共 M 张，其余用 conn_catalog 查」。
     *
     * <h4>预算怎么分</h4>
     * 按"还剩多少 ÷ 还有几个连接器"给每个连接器切一份，用不完的往后滚。
     * 不这么分的话，第一个连接器可以把预算吃光，后面的连接器<b>一行都不出现</b>——
     * 而"整个数据源消失"这件事，在注入文本里是看不出来的。
     */
    private String render(List<Connection> conns,
                          Map<Long, List<ObjectRow>> objects,
                          Map<Long, Glosses> glosses,
                          int maxChars) {
        // 先过滤掉"什么都没有"的连接器：既没快照也没口径的连接器，写一行标题只是噪声。
        List<Connection> usable = new ArrayList<>();
        for (Connection c : conns) {
            List<ObjectRow> objs = objects.get(c.getId());
            Glosses g = glosses.get(c.getId());
            boolean hasObjects = objs != null && !objs.isEmpty();
            boolean hasMetrics = g != null && !g.metrics.isEmpty();
            if (hasObjects || hasMetrics) usable.add(c);
        }
        if (usable.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(HEADER);
        // 预算只算连接器块；HEADER 与「被截断了」那两句提示不计入，它们永远完整给出
        // （见 HEADER 与本方法的注释：砍掉提示正好砍掉了截断这件事的唯一解药）。
        int used = 0;

        for (int i = 0; i < usable.size(); i++) {
            Connection c = usable.get(i);
            int share = Math.max(0, (maxChars - used) / (usable.size() - i));
            int limit = used + share;

            String title = "\n\n[数据源 " + c.getName() + titleSuffix(c) + "]";
            sb.append(title);
            used += title.length();   // 标题也占预算，否则连接器一多，预算就只是个下限

            Glosses g = glosses.getOrDefault(c.getId(), new Glosses());
            used = appendGlossary(sb, g.metrics, used, limit);
            used = appendObjects(sb, objects.getOrDefault(c.getId(), List.of()), g, used, limit);
        }
        return sb.toString();
    }

    /**
     * 口径排在表清单<b>前面</b>，而且优先占预算——这不是排版问题。
     * 「销售额扣不扣退款」决定了要不要 join 退款表，也就决定了接下来选哪几张表；
     * 等模型选完表再看到口径，那个决策点已经过去了。所以宁可少列两张表，也要把口径给全。
     */
    private int appendGlossary(StringBuilder sb, List<ConnectorSemantic> metrics, int used, int limit) {
        if (metrics.isEmpty()) return used;
        String head = "\n已确认的口径（决定要选哪几张表，动手前先读；引用时把括号里的依据一并带进答案）：";
        sb.append(head);
        used += head.length();

        int shown = 0;
        for (ConnectorSemantic m : metrics) {
            String line = "\n- " + m.getTerm().trim() + "：" + m.getGloss().trim()
                    + "（依据：" + metricBasis(m) + "）";
            if (used + line.length() > limit && shown > 0) break;
            sb.append(line);
            used += line.length();
            shown++;
        }
        if (shown < metrics.size()) {
            String note = "\n（口径共 " + metrics.size() + " 条，此处只列出 " + shown
                    + " 条，其余请调 conn_catalog 查看完整 glossary。）";
            sb.append(note);
            used += note.length();
        }
        return used;
    }

    private int appendObjects(StringBuilder sb, List<ObjectRow> rows, Glosses g, int used, int limit) {
        if (rows.isEmpty()) return used;

        // 排序：有注释 / 有语义的排前面，其次按表名。
        // 预算不够时砍掉的应该是信息量最低的那些行——一个既没注释也没语义的表名，
        // 模型拿到手仍然只能去 conn_describe，而它本来就会为找不到的表去调 conn_catalog。
        List<ObjectRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt((ObjectRow r) -> annotated(r, g) ? 0 : 1)
                .thenComparing(ObjectRow::name));

        String head = "\n表（共 " + rows.size() + " 张）：";
        sb.append(head);
        used += head.length();

        int shown = 0;
        for (ObjectRow r : sorted) {
            String line = "\n- " + renderObject(r, g);
            if (used + line.length() > limit && shown > 0) break;
            sb.append(line);
            used += line.length();
            shown++;
        }
        if (shown < rows.size()) {
            // ★ 这句永远输出，不受预算约束。它就是"静默截断"这个失败模式的唯一解药。
            String note = "\n（篇幅所限，以上只列出 " + shown + " 张，共 " + rows.size()
                    + " 张；有注释 / 有语义说明的已优先列出。其余的请用 conn_catalog 查，"
                    + "不要因为这里没有就说「系统里没有这张表」。）";
            sb.append(note);
            used += note.length();
        }
        return used;
    }

    /**
     * 这一轮的口径命中：<b>一条连接一次 {@link MetricRewriter#detect}</b>，命中了各出一段。
     *
     * <h4>★ 为什么不把几条连接的口径合起来做一次</h4>
     * 那段文字里没有「这是哪条连接的口径」这一栏。合并之后，两条连接对「销售额」的两种定义
     * 会并排出现在同一段里，模型无从区分该用哪条——它会挑一条，而你不知道它挑了哪条。
     * 所以这里按连接分开命中，并在每段前面写明数据源；两条连接各自的口径不会互相污染，
     * 模型也能看出「这条口径只在那个库上成立」。
     *
     * <h4>★ 这是附加上下文，不是对用户原话的改写</h4>
     * 它被拼在用户原话<b>旁边</b>。改写用户输入会让「用户说的话」和「模型看到的话」不再是同一句，
     * 答案错了的时候，对话记录里就没有任何材料能复原模型当时读到了什么——
     * 这个仓库的审计与回溯全都建立在「对话可复核」这个前提上。
     */
    private String renderMetricContext(List<Connection> conns, Map<Long, Glosses> glosses, String userText) {
        StringBuilder sb = null;
        for (Connection c : conns) {
            Glosses g = glosses.get(c.getId());
            if (g == null || g.metrics.isEmpty()) continue;
            MetricRewriter.MetricContext hit;
            try {
                hit = metricRewriter.detect(userText, g.metrics);
            } catch (Exception e) {
                // 一条连接命中失败不该拖垮其余连接：少一段口径，不是断一轮对话。
                log.warn("口径命中失败，这条连接本次不注入 connectionId={}", c.getId(), e);
                continue;
            }
            if (hit == null || isBlank(hit.getBlock())) continue;
            if (sb == null) {
                sb = new StringBuilder();
            } else {
                sb.append("\n\n");
            }
            sb.append("[数据源 ").append(c.getName()).append(titleSuffix(c))
                    .append("] 下面这段口径【只适用于这一条连接】，不要套用到别的数据源上。\n")
                    .append(hit.getBlock());
            log.info("口径确定性命中 connectionId={} terms={} omitted={}",
                    c.getId(), termsOf(hit), hit.getOmitted());
        }
        return sb == null ? null : sb.toString();
    }

    /** 只取词条名打日志：gloss 是客户的业务口径原文，不该进日志检索平台。 */
    private static List<String> termsOf(MetricRewriter.MetricContext hit) {
        List<String> out = new ArrayList<>();
        if (hit.getHits() != null) {
            for (MetricRewriter.Hit h : hit.getHits()) {
                if (h != null) out.add(h.getTerm());
            }
        }
        return out;
    }

    private static boolean annotated(ObjectRow r, Glosses g) {
        if (r.comment() != null) return true;
        ConnectorSemantic s = g.objects.get(r.name());
        return s != null && !isBlank(s.getGloss());
    }

    /** 一行：{@code 表名(类型) | 客户库注释 | 语义: 平台说明}。缺的部分直接不出现，不占位。 */
    private static String renderObject(ObjectRow r, Glosses g) {
        StringBuilder line = new StringBuilder(r.name());
        // 类型只在不是普通表时才写出来——满屏的 (TABLE) 是纯噪声，而"这是个视图"会影响怎么用它。
        if (r.type() != null && !"TABLE".equalsIgnoreCase(r.type())) {
            line.append('(').append(r.type()).append(')');
        }
        if (r.comment() != null) {
            line.append(" | ").append(oneLine(r.comment()));
        }
        ConnectorSemantic s = g.objects.get(r.name());
        if (s != null && !isBlank(s.getGloss())) {
            // 短版本：选表那一刻需要的就是一句话。完整版由 conn_describe 放在响应顶层的 semantic
            // （与顶层 comment 并排）；这里直接复用 conn_catalog 那条 80 字规则，免得两处对"短"的理解分叉。
            boolean stale = ConnectorSemanticService.ST_STALE.equals(s.getStatus());
            line.append(stale ? " | 语义(已过期,以 conn_describe 为准): " : " | 语义: ")
                    .append(oneLine(ConnectorSemanticService.shortGloss(s.getGloss())));
        }
        return line.toString();
    }

    private static String titleSuffix(Connection c) {
        String display = trimToNull(c.getDisplayName());
        String kind = trimToNull(c.getKind());
        if (display == null && kind == null) return EMPTY;
        StringBuilder sb = new StringBuilder("（");
        if (display != null) sb.append(display);
        if (display != null && kind != null) sb.append(" / ");
        if (kind != null) sb.append(kind);
        return sb.append("）").toString();
    }

    /**
     * 一条口径的依据，形如「9月13日由张三确认」。
     *
     * <p><b>与 {@code ConnectorToolExecutor.metricBasis} 刻意同形</b>（那边是 private，跨包拿不到）。
     * 它不是装饰：平台没有管理台的口径纠正入口，任何能对话的人都能覆盖口径，
     * 回答里亮出的这半句是口径被改坏之后唯一可能被业务方看见并纠正的地方。两处措辞要一起改。
     */
    private static String metricBasis(ConnectorSemantic m) {
        String who = isBlank(m.getAnsweredName()) ? "由用户在对话中确认" : "由" + m.getAnsweredName().trim() + "确认";
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

    /** 客户库注释里塞换行的情况是有的；一行一个对象的格式不能被它撑破。 */
    private static String oneLine(String s) {
        return s == null ? EMPTY : s.replaceAll("\\s+", " ").trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ================================================================ 形状

    /**
     * 一次请求要注入的两段文字。<b>刻意不是 record</b>：本仓库实际生效的 jackson-databind 是 2.11.1，
     * record 的序列化支持是 2.12+ 才有的，而这种"顺手直接 JSON 化一下看看"的事迟早会发生，
     * 到那时它只在运行期报错。同一条理由见 {@code SemanticValueProfiler.ValueProfile}。
     */
    @Data
    @Builder
    public static class RequestContext {

        /** 表清单概览；{@code null} = 本次不注入。 */
        private String overview;

        /**
         * 这一轮命中的口径；{@code null} = 这句话里没有命中任何已确认口径。
         *
         * <p><b>★ 它是附加上下文，放在用户原话旁边，永远不要拿它去替换用户原话。</b>
         */
        private String metricContext;

        static RequestContext empty() {
            // 每次新建而不是共用一个常量：@Data 是可变的，共用一个实例等于给调用方一个能改坏的全局状态。
            return RequestContext.builder().build();
        }

        public boolean isEmpty() {
            return (overview == null || overview.isBlank())
                    && (metricContext == null || metricContext.isBlank());
        }
    }

    /** 快照里的一个对象。只留渲染要用的三列——刻意不是 {@code ConnectorSchema}，免得有人顺手去读 detailJson。 */
    private record ObjectRow(String name, String comment, String type) {}

    /** 一条连接的语义层片段：表用途（按表名索引）+ 口径。 */
    private static final class Glosses {
        private final Map<String, ConnectorSemantic> objects = new LinkedHashMap<>();
        private final List<ConnectorSemantic> metrics = new ArrayList<>();
    }
}
