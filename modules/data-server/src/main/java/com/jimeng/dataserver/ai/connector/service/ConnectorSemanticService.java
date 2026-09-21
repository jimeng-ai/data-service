package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 语义层的读写。<b>只碰我们自己的库</b>，不碰客户系统、不叫模型。
 *
 * <h3>为什么这个类必须保持"无外部依赖"</h3>
 * 它会被 {@code ConnectorToolExecutor} 注入，而那个类的构造器有一条硬约束：
 * 不准注入任何能回到 {@code ProviderRegistry} / {@code ClaudeService} / {@code ChatClient} 的 bean，
 * 否则闭合 {@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService →
 * SkillToolExecutorRegistryService → ConnectorToolExecutor} 这条链，启动期构造循环依赖直接失败
 * （仓库里已经被咬过两次）。
 * <b>推导（要叫模型）单独放在 {@link ConnectorSemanticDeriveService}，永远不要合并进来。</b>
 *
 * <h3>注入是叠加注解，不是一道闸</h3>
 * 读失败一律返回空、不抛——没有语义层时 conn_catalog / conn_describe 必须照常可用。
 * 用一个可选增强的故障去否决一个必要功能是错的。
 *
 * <h3>★ 写回纪律：只写自己改的列，并以「这一行仍是读到时的样子」为条件</h3>
 * 本类好几处是「先读出一批行、循环里逐条写回」（漂移处置、增量写入、口径沉淀、档位下调时删取值）。
 * 读与写之间，这张表上还有别的写者：人在对话里确认口径、采样验证逐条落结论、表形态测量、值域采集。
 * 整行写回会把他们刚写下的东西用读到的旧值盖掉，<b>而且没有任何痕迹</b>——最坏的一种是人刚答的口径（新 gloss、留痕、回答人）
 * 被一轮周期刷新悄悄抹回旧版本。所以每一处写回都带 {@link #unchangedSince} 这组条件，影响 0 行就是别处赢了：
 * 漂移处置跳过（下一次刷新按那时的样子重判），口径沉淀重读重试，删取值重读重来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSemanticService {

    // ── scope ──
    public static final String SCOPE_OBJECT = "OBJECT";
    public static final String SCOPE_FIELD = "FIELD";
    public static final String SCOPE_JOIN = "JOIN";
    public static final String SCOPE_METRIC = "METRIC";
    public static final String SCOPE_CAVEAT = "CAVEAT";

    // ── source ──
    public static final String SOURCE_INFERRED = "INFERRED";
    public static final String SOURCE_HUMAN = "HUMAN";
    public static final String SOURCE_IMPORTED = "IMPORTED";

    // ── evidence：分界线是"有没有依据"，不是"能不能验证" ──
    public static final String EV_COMMENT = "COMMENT";
    public static final String EV_DATA = "DATA";
    public static final String EV_NAME = "NAME";
    public static final String EV_GUESS = "GUESS";

    // ── status ──
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_STALE = "STALE";

    // ── verified ──
    public static final String V_CONFIRMED = "CONFIRMED";
    public static final String V_WEAK = "WEAK";
    public static final String V_REJECTED = "REJECTED";
    public static final String V_UNDECIDABLE = "UNDECIDABLE";
    public static final String V_NONE = "NONE";

    // ── anchorKind ──
    public static final String ANCHOR_NONE = "NONE";
    public static final String ANCHOR_FIELD = "FIELD";
    public static final String ANCHOR_JOIN = "JOIN";
    public static final String ANCHOR_COLUMN_SET = "COLUMN_SET";

    /**
     * {@code COLUMN_SET} 锚点依赖的列集合，存在 detail_json 里：
     * {@code [{"object":"orders","column":"pay_amt","anchor":"<写入那一刻的 fieldAnchor>"}, ...]}。
     * 形状由 {@link AnchorColumn#toDetailMap} 一处产出，写入侧不要在别处各拼各的键名。
     *
     * <p><b>读不出来（缺、不是列表、空、元素缺字段）一律当「这次没有依据判断」</b>，不是「结构变了」：
     * 见 {@link AnchorVerdict#NO_BASIS}。{@code anchor} 是可选的，只用来说得出「是哪一列变了」；
     * 没有它照样判得出变没变（整体 hash 就是 {@code anchor_hash}），只是名单只报得出「列没了」的那些。
     */
    static final String KEY_ANCHOR_COLUMNS = "anchor_columns";

    /**
     * {@code COLUMN_SET} 行被判过期时，detail_json 里记「是哪几列变了」，形如 {@code ["orders.pay_amt"]}。
     * 与 {@link #KEY_STALE_REMOVED}（是哪几张表没了）同一个用途：让管理台和下一次刷新说得出原因，
     * 而不是只留一个光秃秃的 STALE。<b>复活时清掉</b>——留着就等于把一次已经撤销的过期说成还在。
     */
    static final String KEY_STALE_COLUMNS = "stale_columns";

    /** 注入 conn_catalog 时 gloss 的截断长度。选表那一刻需要的就是一句话，完整版在 describe 给。 */
    static final int CATALOG_GLOSS_MAX = 80;

    /** 变更留痕保留条数。只为取证，不做时间机器。 */
    static final int HISTORY_MAX = 20;

    /**
     * 多态外键决出结论那一轮能不能读取值（布尔）。<b>与推导类的同名常量必须是同一个字符串</b>（单测钉住）。
     * 这里不直接引用那个常量，是为了不让本类在源码上指向一个会叫模型的类。
     */
    static final String KEY_PROBED_WITH_SAMPLE_VALUES = "probed_with_sample_values";

    /** 多态外键的判别值。它是客户库里的<b>真实取值</b>，只能在第 3 档存在。 */
    static final String KEY_DISCRIMINATOR_VALUE = "discriminator_value";

    /** 结构判定写的警告。判别值记下来时，这句话里按 {@code = '取值'} 原样带着它。 */
    static final String KEY_CARE_REASON = "care_reason";

    /** 值域阶段补写的 FIELD 行在 detail_json 里带的来源标记（契约 K-3）。 */
    static final String KEY_ORIGIN = "origin";
    static final String ORIGIN_VALUE_PROFILE = "value_profile";

    /**
     * JOIN 行上属于「S3 采样验证结论」的那组键（含结构判定那几个：它们和包含率是同一次落结论写下的）。
     * 增量写入保留结论时<b>整组</b>跟着旧行走，见 {@link #mergeInferred}。
     *
     * <p>{@link #KEY_PROBED_WITH_SAMPLE_VALUES} 必须在组里：它是「档位调上去之后只重探一次判别值」能停下来的记号。
     * 丢了它，一条已经在第 3 档探过、判别值因为 PII / 自增 id 重叠而没记下来的多态外键，每一轮验证都会把客户的库再扫一遍。
     */
    static final List<String> JOIN_VERDICT_KEYS = List.of("sample_n", "match_n", "containment", "cardinality",
            "auto_joinable", "basis", "verify_note", "join_kind", "discriminator_column", KEY_DISCRIMINATOR_VALUE,
            "composite_columns", KEY_CARE_REASON, KEY_PROBED_WITH_SAMPLE_VALUES);

    /** S1 留下的来源（「客户自己在视图里这样连过」）。单独一个键，就是为了不被别的覆盖顺手抹掉。 */
    static final String KEY_SOURCE_SQL = "source_sql";

    /** 表形态实测结论的那组键。 */
    static final List<String> MEASURED_SHAPE_KEYS = List.of(TableShape.KEY_SHAPE, TableShape.KEY_SOURCE,
            TableShape.KEY_MEASUREMENT, TableShape.KEY_KV_NAME_COLUMN, TableShape.KEY_KV_VALUE_COLUMN);

    /**
     * 值域阶段补写的 FIELD 行，档位不再允许真实取值之后换上的 gloss。
     *
     * <p>不是一句「已删除」就完：这一行存在的全部理由是「这一列的取值只有这几种」，删掉取值之后模型最需要的是
     * 「别凭猜测写死条件」这句话——少了它，模型会照着列名猜一个 {@code status = 1}。
     */
    static final String PURGED_VALUE_GLOSS = "这一列曾在第 3 档（样本值）下采集过完整取值集合；这条连接的数据出库档位已下调，"
            + "采到的真实取值已从平台删除。需要按取值过滤时先查一下这一列的实际取值分布，不要凭猜测写死条件。";

    /** 删掉取值之后 {@code value_domain.note} 的那一句。原来那句可能写着「以下是全部取值」，而下面已经没有取值了。 */
    static final String PURGED_VALUE_NOTE = "取值集合已随数据出库档位下调从平台删除（原为第 3 档采样得到）。"
            + "需要时先查这一列的实际取值，不要写死条件。";

    /** 写回撞上别处的写之后，重读重来的最多次数。 */
    static final int WRITE_ATTEMPTS = 3;

    /** JOIN 行的对端。人写的关系与推导写的关系用<b>同一对键</b>，注入层才不用分两种读法。 */
    public static final String KEY_TO_OBJECT = "to_object";
    public static final String KEY_TO_COLUMN = "to_column";

    /**
     * 人对一条关系给出的判断：{@link #HV_RELATED} / {@link #HV_UNRELATED}。
     *
     * <h4>★ 为什么「这两张表没关系」不能写成 {@code verified=REJECTED}</h4>
     * {@code verified} 这一列的含义是<b>采样核过的结论</b>：{@code REJECTED} 读作「平台真的去客户库里取过样、
     * 两边对不上」。把人说的一句话写进去有两个各自独立的坏处：
     * <ul>
     *   <li><b>伪装成一次采样</b>：注入层、管理台、下一轮验证都会把它当成实测结论，而它从来没被实测过；</li>
     *   <li><b>再也纠正不了</b>：{@link #mergeInferred} 保留旧结论时是<b>整组</b>（{@link #JOIN_VERDICT_KEYS}）
     *       跟着旧行搬运的，这条假结论会一路被抄进后面每一版推导，而推导侧永远不会去推翻一个「已经验过」的结论。</li>
     * </ul>
     * 所以人的否定只进 {@code detail_json} 的这一个独立键，{@code verified} 那一列<b>一次都不 set</b>
     * （见 {@link #upsertHuman} 的覆盖分支）。
     */
    public static final String KEY_HUMAN_VERDICT = "human_verdict";
    /** 人说这两列确实是同一个东西。 */
    public static final String HV_RELATED = "RELATED";
    /** 人说这两张表没有关系——别再把它当外键连。 */
    public static final String HV_UNRELATED = "UNRELATED";

    /** CAVEAT / METRIC 的适用范围。 */
    public static final String KEY_APPLIES_TO = "applies_to";

    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectionMapper connectionMapper;

    // ================================================================ 读（注入路径）

    /** conn_catalog 需要的：表用途（按表名索引）+ 口径表。 */
    public CatalogSemantics forCatalog(Long connectorId) {
        try {
            List<ConnectorSemantic> rows = semanticMapper.selectList(
                    new LambdaQueryWrapper<ConnectorSemantic>()
                            .eq(ConnectorSemantic::getConnectorId, connectorId)
                            .in(ConnectorSemantic::getScope, List.of(SCOPE_OBJECT, SCOPE_METRIC)));
            Map<String, ConnectorSemantic> objects = new LinkedHashMap<>();
            List<ConnectorSemantic> glossary = new ArrayList<>();
            for (ConnectorSemantic r : rows) {
                if (SCOPE_OBJECT.equals(r.getScope())) {
                    objects.put(r.getObjectName(), r);
                } else {
                    glossary.add(r);
                }
            }
            return CatalogSemantics.builder().objects(objects).glossary(glossary).build();
        } catch (Exception e) {
            // 读语义层失败不该让"看目录"整个挂掉——它是叠加的注解，不是必需品。
            log.warn("读取语义层失败（catalog），本次不注入 connectorId={}", connectorId, e);
            return CatalogSemantics.builder().objects(Map.of()).glossary(List.of()).build();
        }
    }

    /** conn_describe 需要的：某张表的字段含义（按列名索引）+ 从这张表出发的关联。 */
    public ObjectSemantics forObject(Long connectorId, String objectName) {
        try {
            List<ConnectorSemantic> rows = semanticMapper.selectList(
                    new LambdaQueryWrapper<ConnectorSemantic>()
                            .eq(ConnectorSemantic::getConnectorId, connectorId)
                            .eq(ConnectorSemantic::getObjectName, objectName)
                            .in(ConnectorSemantic::getScope, List.of(SCOPE_FIELD, SCOPE_JOIN)));
            Map<String, ConnectorSemantic> fields = new LinkedHashMap<>();
            List<ConnectorSemantic> joins = new ArrayList<>();
            for (ConnectorSemantic r : rows) {
                if (SCOPE_FIELD.equals(r.getScope())) {
                    fields.put(r.getFieldName(), r);
                } else {
                    joins.add(r);
                }
            }
            return ObjectSemantics.builder().fields(fields).joins(joins).build();
        } catch (Exception e) {
            log.warn("读取语义层失败（describe），本次不注入 connectorId={} object={}", connectorId, objectName, e);
            return ObjectSemantics.builder().fields(Map.of()).joins(List.of()).build();
        }
    }

    /** 管理台用：某条连接的全部语义行。 */
    public List<ConnectorSemantic> all(Long connectorId) {
        requireOwned(connectorId);
        return semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .orderByAsc(ConnectorSemantic::getScope)
                .orderByAsc(ConnectorSemantic::getObjectName)
                .orderByAsc(ConnectorSemantic::getFieldName));
    }

    /**
     * 语义层里<b>提到过</b>的对象名：挂着行的表、关系指向的表、口径过期名单里的表。给结构刷新决定「目录截断时该单独问哪些名字在不在」。
     *
     * <p>为什么要问语义层，而不是只看上一份结构快照：一张表可能从来没进过快照（刀口外），却被一条关系指着；
     * 口径过期名单里的表在它消失那次刷新之后就不在快照里了。只按快照问，这两种表在目录截断时永远是「不知道」——
     * 指向已删表的关系一直注入、过期的口径永远复活不了。
     *
     * <p>读失败返回空集合、不抛：这是「多问几个名字」的增强，没有它刷新照样做完，只是那几个名字停在「不知道」。
     */
    public Set<String> referencedObjectNames(Long connectorId) {
        try {
            List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                    .select(ConnectorSemantic::getId, ConnectorSemantic::getScope,
                            ConnectorSemantic::getObjectName, ConnectorSemantic::getDetailJson)
                    .eq(ConnectorSemantic::getConnectorId, connectorId));
            Set<String> out = new LinkedHashSet<>();
            for (ConnectorSemantic r : rows == null ? List.<ConnectorSemantic>of() : rows) {
                if (r.getObjectName() != null && !r.getObjectName().isBlank()) {
                    out.add(r.getObjectName());
                }
                Map<String, Object> d = readDetail(r);
                if (SCOPE_JOIN.equals(r.getScope())) {
                    String to = text(d.get("to_object"));
                    if (to != null) {
                        out.add(to);
                    }
                }
                out.addAll(stringList(d.get(KEY_STALE_REMOVED)));
            }
            return out;
        } catch (Exception e) {
            log.warn("读取语义层提到的对象名失败，本次结构刷新只按结构快照确认表是否存在 connectorId={}", connectorId, e);
            return Set.of();
        }
    }

    /**
     * 这条连接允不允许把<b>真实取值</b>带出客户库（契约 C-C）。注入路径在「要不要把取值给模型看」之前问这一句。
     *
     * <p><b>任何失败都答「不允许」</b>：连接查不到、库抖了、档位列是个认不出来的字符串。两个方向答错的代价不对称——
     * 答成不允许，模型少看一份值域，吵闹、能被发现；答成允许，客户的真实取值在他没授权的档位上进了提示词，
     * 安静、发现不了。空值按 {@link SemanticDataTier#parse} 落到默认档（第 2 档），同样是不允许。
     *
     * <p>只走 {@code ConnectionMapper}：本类被 {@code ConnectorToolExecutor} 注入，不能多出一条能回到模型的依赖。
     * 问的是谓词、不比档位序号，理由见 {@link SemanticDataTier} 类注释。
     */
    public boolean allowsSampleValues(Long connectorId) {
        if (connectorId == null) {
            return false;
        }
        try {
            Connection c = connectionMapper.selectById(connectorId);
            return c != null && SemanticDataTier.parse(c.getSemanticDataTier()).allowsSampleValues();
        } catch (Exception e) {
            log.warn("读取连接的数据出库档位失败，按不允许出库真实取值处理 connectorId={}", connectorId, e);
            return false;
        }
    }

    // ================================================================ 写（推导路径）

    /**
     * 用一批新推断的行替换旧的推断结果。<b>只动 INFERRED，HUMAN / IMPORTED 一行不碰。</b>
     *
     * <p>先按 connectorId 查出连接确认归属再删，是必须的：{@code physicalDeleteInferred} 那条
     * 裸 DELETE 在 {@code runAsSystem} 下<b>不带任何 tenant_id 条件</b>，传错 id 就抹掉别人的语义层。
     */
    @Transactional
    public int replaceInferred(Long connectorId, List<ConnectorSemantic> fresh) {
        Connection conn = requireOwned(connectorId);
        semanticMapper.physicalDeleteInferred(connectorId);
        int n = 0;
        for (ConnectorSemantic r : fresh) {
            r.setId(null);
            r.setTenantId(conn.getTenantId());
            r.setConnectorId(connectorId);
            r.setSource(SOURCE_INFERRED);
            if (r.getStatus() == null) r.setStatus(ST_DRAFT);
            if (r.getVerified() == null) r.setVerified(V_NONE);
            semanticMapper.insert(r);
            n++;
        }
        return n;
    }

    /** DIRECT 模式下按 term 合并一条连接级 CAVEAT 的结果。 */
    public enum CaveatMergeResult {
        INSERTED,
        MERGED,
        SKIPPED_NOT_INFERRED,
        CONFLICT
    }

    /**
     * DIRECT 提交路径合并连接级 CAVEAT。这个方法刻意不加 {@link Transactional}：调用者已经持有提交事务，
     * 唯一键冲突必须在 mapper 调用这一层被接住，不能穿过事务代理把外层事务标成 rollback-only。
     *
     * <p>不存在就插入；已有人工/导入行时不碰；已有 INFERRED 行时只把 {@code detail_json.applies_to}
     * 按首次出现顺序做大小写不敏感并集（最多 {@link SemanticRowAssembler#DETAIL_LIST_MAX} 张），先到的 gloss
     * 及其它证据保持原样。更新带 {@link #unchangedSince} 条件，0 行说明并发写者先赢，返回 {@link CaveatMergeResult#CONFLICT}。
     */
    public CaveatMergeResult mergeInferredCaveat(Long connectorId, ConnectorSemantic caveat) {
        Connection conn = requireOwned(connectorId);
        if (caveat == null || !SCOPE_CAVEAT.equals(caveat.getScope())
                || caveat.getTerm() == null || caveat.getTerm().isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "只能合并 term 非空的 CAVEAT");
        }
        if (caveat.getObjectName() == null) caveat.setObjectName("");
        if (caveat.getFieldName() == null) caveat.setFieldName("");
        caveat.setTerm(caveat.getTerm().trim());

        ConnectorSemantic existing = semanticMapper.selectOne(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .eq(ConnectorSemantic::getScope, SCOPE_CAVEAT)
                .eq(ConnectorSemantic::getObjectName, "")
                .eq(ConnectorSemantic::getFieldName, "")
                .eq(ConnectorSemantic::getTerm, caveat.getTerm())
                .last("limit 1"));
        if (existing == null) {
            caveat.setId(null);
            caveat.setTenantId(conn.getTenantId());
            caveat.setConnectorId(connectorId);
            caveat.setSource(SOURCE_INFERRED);
            if (caveat.getStatus() == null) caveat.setStatus(ST_DRAFT);
            if (caveat.getVerified() == null) caveat.setVerified(V_NONE);
            if (caveat.getAnchorKind() == null) caveat.setAnchorKind(ANCHOR_NONE);
            try {
                semanticMapper.insert(caveat);
                return CaveatMergeResult.INSERTED;
            } catch (DuplicateKeyException e) {
                log.info("agent 合并 CAVEAT 时撞上并发插入，本条跳过 connectorId={} term={}",
                        connectorId, caveat.getTerm());
                return CaveatMergeResult.CONFLICT;
            }
        }
        if (!SOURCE_INFERRED.equals(existing.getSource())) {
            return CaveatMergeResult.SKIPPED_NOT_INFERRED;
        }

        Map<String, Object> mergedDetail = new LinkedHashMap<>(readDetail(existing));
        LinkedHashMap<String, String> appliesTo = new LinkedHashMap<>();
        for (String table : stringList(mergedDetail.get("applies_to"))) {
            appliesTo.putIfAbsent(ciFold(table), table);
            if (appliesTo.size() == SemanticRowAssembler.DETAIL_LIST_MAX) break;
        }
        for (String table : stringList(readDetail(caveat).get("applies_to"))) {
            appliesTo.putIfAbsent(ciFold(table), table);
            if (appliesTo.size() == SemanticRowAssembler.DETAIL_LIST_MAX) break;
        }
        mergedDetail.put("applies_to", List.copyOf(appliesTo.values()));
        String detailJson = toJson(mergedDetail);
        if (detailJson == null) {
            return CaveatMergeResult.CONFLICT;
        }

        LambdaUpdateWrapper<ConnectorSemantic> update = new LambdaUpdateWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getId, existing.getId());
        unchangedSince(update, existing);
        update.set(ConnectorSemantic::getDetailJson, detailJson);
        return semanticMapper.update(null, update) == 1
                ? CaveatMergeResult.MERGED
                : CaveatMergeResult.CONFLICT;
    }

    /**
     * ★ 增量写入：<b>原地 UPSERT，不删一行</b>。给「新出现的表只跑 S2」那条路用。
     *
     * <h3>为什么不能复用 {@link #replaceInferred}</h3>
     * 那是整层擦掉重插：为 3 张新表调它，另外 197 张表上已经跑完的采样验证结论（S3）、值域（S4）、
     * 表形态测量一起被物理删除，而且没有任何一处会报出来——说明书看起来刚刚「重新生成成功」。
     *
     * <h3>为什么也不能「先删这几张表的旧行再插」</h3>
     * 除了整层擦除之外没有物理删除的入口，而 {@code BaseMapper.delete} 在全局 {@code @TableLogic} 下
     * 是<b>软删</b>：死行仍然占着 {@code uk_connector_semantic}（它不含 deleted），下一次插入同一条就撞键。
     * 本表的前提就是「不产生软删死行」（见实体注释）。所以只剩原地更新这一条路。
     *
     * <h3>规则</h3>
     * <ul>
     *   <li>按唯一键匹配，<b>大小写与重音不敏感</b>——索引是 utf8mb4_unicode_ci，{@code ORD_ID} 与 {@code ord_id}
     *       在库里是同一个键。大小写敏感地匹配会把「已有」判成「没有」，插入时撞键。</li>
     *   <li>没有 → 插入一条 INFERRED。</li>
     *   <li>有，且 {@code source != INFERRED}（HUMAN / IMPORTED）→ <b>一个字不碰</b>。</li>
     *   <li>有，是 INFERRED，但挂在别的表上 → <b>保持原样</b>。它身上可能已经有采样验证结论，
     *       而这次推导根本没看过那张表的完整结构。</li>
     *   <li>有，是 INFERRED，且挂在 {@code updatableObjects} 里的表上 → 原地更新，但<b>不毁证据</b>：
     *       采样结论、语料来源、表形态实测、值域哪些留、哪些作废，见 {@link #mergeInferred}。
     *       一张被删掉又重建的表已经带着 STALE 行，这一步正好把它们换成新结构上的说明并复活。</li>
     *   <li>回写以「这一行仍是读到时的样子」为条件（{@link #unchangedSince}）。读与写之间被人答成 HUMAN、
     *       被采样验证落了结论的行不覆盖——合并规则是按读到的旧行算的，拿它盖掉一条更新的结论就是在毁证据。
     *       这种行计入 {@link UpsertResult#concurrentSkips()}。</li>
     * </ul>
     *
     * <p>单条撞键（本地折叠规则与 MySQL 排序规则仍有覆盖不到的字符）只丢那一条、不回滚整批：
     * MySQL 的唯一键冲突只回滚那一条语句，mapper 也不是事务代理，在这里接住不会把事务标成 rollback-only。
     *
     * @param updatableObjects 允许被原地覆盖的表名（本次真正把完整结构给过模型的那些表）
     */
    @Transactional
    public UpsertResult upsertInferred(Long connectorId, List<ConnectorSemantic> fresh,
                                       Collection<String> updatableObjects) {
        Connection conn = requireOwned(connectorId);
        Set<String> updatable = new HashSet<>();
        if (updatableObjects != null) {
            for (String o : updatableObjects) {
                updatable.add(ciFold(o));
            }
        }
        Map<String, ConnectorSemantic> byKey = new HashMap<>();
        for (ConnectorSemantic e : semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId))) {
            byKey.putIfAbsent(uniqueKey(e), e);
        }

        int inserted = 0;
        int updated = 0;
        int notInferred = 0;
        int kept = 0;
        int conflicts = 0;
        int concurrent = 0;
        int preservedRows = 0;
        // 档位只在真要合并时问一次：一批全是新插入时不必多打一次库。
        Boolean sampleValues = null;
        for (ConnectorSemantic r : fresh == null ? List.<ConnectorSemantic>of() : fresh) {
            if (r == null) {
                continue;
            }
            // 三个键列 NOT NULL DEFAULT ''：null 在唯一键上不参与去重。
            if (r.getObjectName() == null) r.setObjectName("");
            if (r.getFieldName() == null) r.setFieldName("");
            if (r.getTerm() == null) r.setTerm("");
            String key = uniqueKey(r);
            ConnectorSemantic old = byKey.get(key);

            if (old == null) {
                r.setId(null);
                r.setTenantId(conn.getTenantId());
                r.setConnectorId(connectorId);
                r.setSource(SOURCE_INFERRED);
                if (r.getStatus() == null) r.setStatus(ST_DRAFT);
                if (r.getVerified() == null) r.setVerified(V_NONE);
                try {
                    semanticMapper.insert(r);
                    byKey.put(key, r);
                    inserted++;
                } catch (DuplicateKeyException e) {
                    conflicts++;
                    log.warn("增量写入撞唯一键，本条跳过 connectorId={} scope={} object={} field={}",
                            connectorId, r.getScope(), r.getObjectName(), r.getFieldName());
                }
                continue;
            }
            if (!SOURCE_INFERRED.equals(old.getSource())) {
                notInferred++;
                continue;
            }
            if (!updatable.contains(ciFold(old.getObjectName()))) {
                kept++;
                continue;
            }

            if (sampleValues == null) {
                sampleValues = allowsSampleValues(connectorId);
            }
            Merged m = mergeInferred(old, r, sampleValues);
            ConnectorSemantic u = new ConnectorSemantic();
            // 键列也写：大小写以这次快照的写法为准（表按大小写改过名时，锚点要和快照对得上）。
            u.setObjectName(r.getObjectName());
            u.setFieldName(r.getFieldName());
            u.setTerm(r.getTerm());
            u.setGloss(m.gloss());
            u.setStatus(m.status());
            u.setVerified(m.verified());
            LambdaUpdateWrapper<ConnectorSemantic> w = new LambdaUpdateWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getId, old.getId());
            // 读与写之间被人答成 HUMAN 的、被采样验证 / 值域 / 表形态刚写过的，都不覆盖（source 条件就在这组里）。
            unchangedSince(w, old);
            // ★ 可能为 null 的列必须显式 set：默认 NOT_NULL 更新策略会跳过 null，
            //   于是旧化身留下的 value_domain / 置信度 / 锚点会原样活在新结构上。
            w.set(ConnectorSemantic::getDetailJson, m.detailJson())
                    .set(ConnectorSemantic::getEvidence, m.evidence())
                    .set(ConnectorSemantic::getConfidence, m.confidence())
                    .set(ConnectorSemantic::getAnchorKind, m.anchorKind())
                    .set(ConnectorSemantic::getAnchorHash, m.anchorHash());
            int n = semanticMapper.update(u, w);
            if (n > 0) {
                updated++;
                if (!m.preserved().isEmpty()) {
                    preservedRows++;
                    log.debug("增量写入保住了已有证据 connectorId={} scope={} object={} field={} 保留={}",
                            connectorId, old.getScope(), old.getObjectName(), old.getFieldName(), m.preserved());
                }
            } else {
                concurrent++;
                log.info("增量写入回写时这一行刚被别处改过（人答成 HUMAN / 采样验证刚落结论），本条不覆盖 "
                        + "connectorId={} scope={} object={} field={}",
                        connectorId, old.getScope(), old.getObjectName(), old.getFieldName());
            }
        }
        if (preservedRows > 0) {
            // 这个数要进日志：它是「表回来了、证据没被重推冲掉」唯一看得见的痕迹。
            log.info("增量写入保住了已有证据（采样结论 / 语料来源 / 表形态实测 / 值域） connectorId={} 条数={}",
                    connectorId, preservedRows);
        }
        return new UpsertResult(inserted, updated, notInferred, kept, conflicts, concurrent);
    }

    /**
     * ★ 一条已有的 INFERRED 行被同键的新推断撞上时，落库的是什么。<b>这里决定证据会不会被悄悄抹掉。</b>
     *
     * <h3>为什么不能「新的一律覆盖旧的」</h3>
     * 撞上的场景几乎都是「一张表回来了」：消失过、或者被挤出过描述上限，这次重新出现，增量推导把同样的关系又提了一遍。
     * 旧行身上这时带着的东西，新推断<b>一样都给不出</b>：
     * <ul>
     *   <li>S3 的采样结论。数据<b>否决</b>过的关系（REJECTED）一旦被重置成 NONE，就以「未经验证」的身份重新进 joins——
     *       「REJECTED 永不注入」被一次增量推导静默打破；而 S3 只挑 NONE 当候选，还要再花一轮客户库的配额把它否决一次。</li>
     *   <li>S1 的来源（客户自己在视图里这样连过：EV_COMMENT + {@code source_sql}）。模型的「名字像」不该顶掉它。</li>
     *   <li>表形态的实测结论（MEASURED）。丢了它，键值对表会以模型猜的「明细表」身份被 SUM——所有聚合都错且不报错；
     *       而测过的表下一轮不会再测（推导类 {@code shapeAlreadyMeasured} 看的正是这几个键），丢了就回不来。</li>
     * </ul>
     *
     * <h3>规则（按顺序，先中先得）</h3>
     * <ol>
     *   <li><b>同一条断言、锚点变了</b>（非 JOIN 锚点不同；JOIN 目标相同而锚点不同）：结构变了，旧结论作废，
     *       整条换成新的，与从前一样。JOIN <b>目标不同</b>不算这一条——那不是「结构变了」，是另一条竞争的断言；
     *       两条的锚点本来就不同（也可能碰巧相同：两边列的名字 / 类型 / 注释一样时），所以目标必须显式比。
     *       <b>OBJECT 行永远不走这一条</b>：它不锚结构，「锚点变了」对它没有意义，走进来只会把表形态测量顺手扔掉。</li>
     *   <li><b>旧行有外部依据（COMMENT / DATA）、新行没有（NAME / GUESS）</b>：gloss、目标、detail、依据、置信度、结论
     *       一个字不让。状态只在「同一条断言」时跟新行走——走到这里同一条断言必然锚点相同，而新锚点是按当前结构算的，
     *       等于证明了旧行结构上仍然成立，可以复活；换了目标时保持漂移处置给它的状态，这次推导没有核过旧目标。</li>
     *   <li>其余情况以新行为准，但把新行给不出、旧行已经挣到的东西带过去：
     *     <ul>
     *       <li>JOIN 同一条关系（目标同、锚点同）且旧 {@code verified} 不是 NONE：留 {@code verified}、留 gloss
     *           （S3 落结论时连 gloss 一起改写了，换回模型那句「本条未经数据验证」就是在说反话）、留 {@link #JOIN_VERDICT_KEYS}。
     *           <b>旧行没有的结论键从新行里拿掉</b>：结论是一个整体，一条写着「已采样验证」却带着模型新猜的基数的关系
     *           比没验证过更危险——与推导类 {@code persistVerdict} 在基数判不出时删掉 {@code cardinality} 同一个理由。</li>
     *       <li>JOIN 同一条关系：新行没带 {@code source_sql} 时留旧的。同一条关系、同样的结构，「客户这样连过」依然成立。</li>
     *       <li>OBJECT 且旧行 {@code table_shape_source=MEASURED}：留 {@link #MEASURED_SHAPE_KEYS}；新模型的判断与实测不一致时
     *           记进 {@code table_shape_model_guess}（分歧本身是信号），一致时保留旧的分歧记录。
     *           OBJECT 不锚结构，看不出表是不是换了一张同名的——这是已知代价，实测结论宁可留着让人看见，也不静默丢掉。</li>
     *       <li>OBJECT 且旧行带着 {@code table_shape_measurement}、<b>不管结论是什么</b>：新行没带时留旧的。
     *           「测过、不是键值对表」同样是一次打在客户库上的测量；丢了这条留痕，推导类会把这张表当成没测过，
     *           下一轮再去客户库量一遍，而结论不会变。</li>
     *       <li>FIELD 锚点相同（且非空）、<b>且这条连接此刻允许真实取值</b>：新行没带值域时留旧的 {@code value_domain}。
     *           列指纹没变，值域与其它列上的一样有效；丢了就要回客户库再读一遍真实取值。锚点变了的那种（旧化身）按第 1 条整条换掉。</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h3>★ 档位不允许真实取值时，三条规则走完再统一剥一遍</h3>
     * 第 2 条会把旧行的 detail 与 gloss <b>原样</b>留下，而值域阶段补写的行恰恰是 DATA 依据——它的 gloss 里就列着取值，
     * 值域里存着取值；JOIN 的结论组里带着判别值。客户收回第 3 档之后，一次增量推导不该成为让真实取值继续留在我们库里的那条路。
     * 剥法与 {@link #purgeSampleValues} 同一套（{@link #scrubSampleValues}）。
     *
     * @param sampleValuesAllowed 这条连接此刻是否允许真实取值（{@link #allowsSampleValues}，失败按不允许）
     */
    Merged mergeInferred(ConnectorSemantic old, ConnectorSemantic r, boolean sampleValuesAllowed) {
        Merged m = mergeKeepingEvidence(old, r, sampleValuesAllowed);
        return sampleValuesAllowed ? m : withoutSampleValues(old, m);
    }

    private Merged mergeKeepingEvidence(ConnectorSemantic old, ConnectorSemantic r, boolean sampleValuesAllowed) {
        String newStatus = r.getStatus() == null ? ST_DRAFT : r.getStatus();
        String newVerified = r.getVerified() == null ? V_NONE : r.getVerified();

        boolean join = SCOPE_JOIN.equals(old.getScope());
        boolean object = SCOPE_OBJECT.equals(old.getScope());
        Map<String, Object> oldD = readDetail(old);
        Map<String, Object> newD = new LinkedHashMap<>(readDetail(r));
        boolean sameTarget = !join || (sameName(oldD.get("to_object"), newD.get("to_object"))
                && sameName(oldD.get("to_column"), newD.get("to_column")));
        boolean sameAnchor = object || Objects.equals(old.getAnchorHash(), r.getAnchorHash());

        // ① 同一条断言、结构变了：旧结论作废。
        if (sameTarget && !sameAnchor) {
            return new Merged(r.getGloss(), r.getDetailJson(), r.getEvidence(), r.getConfidence(),
                    r.getAnchorKind(), r.getAnchorHash(), newVerified, newStatus, List.of());
        }

        // ② 旧的有外部依据、新的没有：旧的一个字不让。
        if (strongEvidence(old.getEvidence()) && !strongEvidence(r.getEvidence())) {
            String status = sameTarget ? newStatus : (old.getStatus() == null ? ST_DRAFT : old.getStatus());
            return new Merged(old.getGloss(), old.getDetailJson(), old.getEvidence(), old.getConfidence(),
                    old.getAnchorKind(), old.getAnchorHash(),
                    old.getVerified() == null ? V_NONE : old.getVerified(), status, List.of("evidence"));
        }

        // ③ 以新行为准，带上新行给不出的。走到这里：sameTarget ⇒ sameAnchor。
        String gloss = r.getGloss();
        String verified = newVerified;
        List<String> preserved = new ArrayList<>();
        boolean touched = false;

        if (join && sameTarget) {
            String ov = old.getVerified();
            if (ov != null && !ov.isBlank() && !V_NONE.equals(ov)) {
                verified = ov;
                if (old.getGloss() != null) {
                    gloss = old.getGloss();
                }
                for (String k : JOIN_VERDICT_KEYS) {
                    if (oldD.containsKey(k)) {
                        newD.put(k, oldD.get(k));
                    } else {
                        newD.remove(k);
                    }
                }
                touched = true;
                preserved.add("verdict");
            }
            if (oldD.get(KEY_SOURCE_SQL) != null && newD.get(KEY_SOURCE_SQL) == null) {
                newD.put(KEY_SOURCE_SQL, oldD.get(KEY_SOURCE_SQL));
                touched = true;
                preserved.add(KEY_SOURCE_SQL);
            }
        }

        if (object && TableShape.SOURCE_MEASURED.equals(text(oldD.get(TableShape.KEY_SOURCE)))) {
            TableShape modelShape = TableShape.parse(text(newD.get(TableShape.KEY_SHAPE))).orElse(null);
            for (String k : MEASURED_SHAPE_KEYS) {
                if (oldD.containsKey(k)) {
                    newD.put(k, oldD.get(k));
                } else {
                    newD.remove(k);
                }
            }
            TableShape measured = TableShape.parse(text(newD.get(TableShape.KEY_SHAPE))).orElse(null);
            if (modelShape != null && modelShape != measured) {
                newD.put(TableShape.KEY_MODEL_GUESS, modelShape.name());
            } else if (oldD.containsKey(TableShape.KEY_MODEL_GUESS)) {
                newD.put(TableShape.KEY_MODEL_GUESS, oldD.get(TableShape.KEY_MODEL_GUESS));
            }
            touched = true;
            preserved.add(TableShape.KEY_SOURCE);
        }

        // 测过、结论不是键值对表的（table_shape_source 仍是 MODEL）：测量留痕照样跟着走，理由见方法注释。
        if (object && oldD.get(TableShape.KEY_MEASUREMENT) != null && !newD.containsKey(TableShape.KEY_MEASUREMENT)) {
            newD.put(TableShape.KEY_MEASUREMENT, oldD.get(TableShape.KEY_MEASUREMENT));
            touched = true;
            preserved.add(TableShape.KEY_MEASUREMENT);
        }

        if (sampleValuesAllowed && SCOPE_FIELD.equals(old.getScope()) && old.getAnchorHash() != null
                && oldD.containsKey(SemanticValueProfiler.DETAIL_KEY)
                && !newD.containsKey(SemanticValueProfiler.DETAIL_KEY)) {
            newD.put(SemanticValueProfiler.DETAIL_KEY, oldD.get(SemanticValueProfiler.DETAIL_KEY));
            touched = true;
            preserved.add(SemanticValueProfiler.DETAIL_KEY);
        }

        String detailJson = r.getDetailJson();
        if (touched) {
            String json = toJson(newD);
            if (json != null) {
                detailJson = json;
            }
        }
        return new Merged(gloss, detailJson, r.getEvidence(), r.getConfidence(),
                r.getAnchorKind(), r.getAnchorHash(), verified, newStatus, List.copyOf(preserved));
    }

    /** 档位不允许真实取值时，把合并结果里的取值剥掉。见 {@link #mergeInferred} 的最后一节。 */
    private Merged withoutSampleValues(ConnectorSemantic old, Merged m) {
        // ★ 判的是这次要写下去的那一行：键与来源随旧行，依据与 detail 随合并结果。判据只有 isValueProfileRow 一条——
        //   从前这里只看 INFERRED + FIELD + DATA，旧行依据强被原样留下时（规则 ②），模型看结构写、标了 DATA 的说明
        //   （「自增主键，一行一个订单」）会在默认档的增量写入里被换成「曾在第 3 档采集过」的假话。
        ConnectorSemantic written = new ConnectorSemantic();
        written.setScope(old.getScope());
        written.setSource(old.getSource());
        written.setEvidence(m.evidence());
        written.setDetailJson(m.detailJson());
        boolean valueRow = isValueProfileRow(written);
        Map<String, Object> d = parseDetailOrNull(m.detailJson());
        if (d == null) {
            // 解析不了的 detail 读出侧同样读不出，但它躺在库里——里面写着取值键就整份不要，不去猜里面有没有取值。
            // 是值域行就在同一次写里把 gloss 一起换掉：只换 detail 的话，写下去的 "{}" 读得出来、又不带值域键，
            // 之后删取值和注入路径都不会再把它认成值域行，一句可能列着取值的 gloss 就永远留着。
            String json = mentionsSampleValues(m.detailJson()) ? "{}" : m.detailJson();
            String gloss = valueRow ? PURGED_VALUE_GLOSS : m.gloss();
            if (Objects.equals(json, m.detailJson()) && Objects.equals(gloss, m.gloss())) {
                return m;
            }
            return new Merged(gloss, json, m.evidence(), m.confidence(), m.anchorKind(), m.anchorHash(),
                    m.verified(), m.status(), m.preserved());
        }
        d = new LinkedHashMap<>(d);
        String discriminator = text(d.get(KEY_DISCRIMINATOR_VALUE));
        String toObject = text(d.get("to_object"));
        boolean detailChanged = scrubSampleValues(d, old.getObjectName(), old.getFieldName());
        String gloss = discriminator == null ? m.gloss() : scrubQuoted(m.gloss(), discriminator, toObject);
        if (valueRow && !PURGED_VALUE_GLOSS.equals(gloss)) {
            gloss = PURGED_VALUE_GLOSS;
        }
        if (!detailChanged && Objects.equals(gloss, m.gloss())) {
            return m;
        }
        String json = detailChanged ? requireJson(d) : m.detailJson();
        return new Merged(gloss, json, m.evidence(), m.confidence(), m.anchorKind(), m.anchorHash(),
                m.verified(), m.status(), m.preserved());
    }

    /** 分界线是「有没有外部依据」：客户库里写着的（COMMENT）、数据里测得到的（DATA）压过名字像的与猜的。 */
    private static boolean strongEvidence(String evidence) {
        return EV_COMMENT.equals(evidence) || EV_DATA.equals(evidence);
    }

    /** 表名列名按唯一键的排序规则比较：{@code USERS} 与 {@code users} 是同一个目标。 */
    private static boolean sameName(Object a, Object b) {
        String x = text(a);
        String y = text(b);
        if (x == null || y == null) {
            return x == null && y == null;
        }
        return ciFold(x).equals(ciFold(y));
    }

    private static String text(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    // ================================================================ 写（人在对话里写下的语义）

    /**
     * 管理台删掉<b>一行</b>语义（任何 scope、任何 source）。
     *
     * <h3>为什么这个入口必须存在</h3>
     * {@code source=HUMAN} 的行<b>写下即永久免疫</b>重新推导与整层重生成（upsertInferred /
     * replaceInferred / derive 上一共 5 处 SOURCE_HUMAN 跳过合起来就是这条纪律）。
     * 好处是人确认过的东西不会被机器覆盖；代价是<b>写错了也再没有机器能改回来</b>——
     * 在此之前唯一的纠错办法是用同一个 term 再说一遍覆盖，而 JOIN 这一类连覆盖都做不到
     * （「这两张表没关系」不是一个能写进去的值）。
     *
     * <h3>为什么是物理删除</h3>
     * 见 {@code ConnectorSemanticMapper#physicalDeleteRow}：唯一键不含 deleted，
     * 软删死行会让同一条断言<b>永远写不进去</b>，而且报错会伪装成「正在被同时修改」。
     *
     * <h3>刻意不加 {@code @Transactional}</h3>
     * 这条路上只有一条 DELETE，单语句自身原子；套一层事务只是白占连接。
     *
     * <h3>删 0 行不抛错</h3>
     * 幂等：双击、前端乐观刷新之后再点、或者这行刚被另一个超管删掉，都不该弹一个假错误。
     * 调用方拿返回值决定要不要记审计。
     *
     * @return 实际删除的行数（0 或 1）
     */
    public int deleteRow(Long connectorId, Long rowId) {
        if (rowId == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "语义行 id 不能为空");
        }
        // 先确认这条连接属于当前租户：SQL 里虽然显式写了 tenant_id，但那个值正是从这里取的。
        Connection conn = requireOwned(connectorId);
        int removed = semanticMapper.physicalDeleteRow(conn.getTenantId(), connectorId, rowId);
        if (removed > 0) {
            log.info("管理台删除语义行 connectorId={} rowId={}", connectorId, rowId);
        }
        return removed;
    }

    /**
     * 覆盖既有行时，锚点那两列怎么处理。
     *
     * <p>只有两档，因为「不动」不是一个策略而是两档各自的兜底：{@link #SET} 给的锚点为空就写 NONE，
     * {@link #REBASE} 重算不出来就一列都不 set。
     */
    public enum HumanAnchor {
        /**
         * 按本次算出的锚点写下去。FIELD / JOIN / 明确给了依赖列的 METRIC 都走这一档——
         * 它们的锚点这一次是从快照上现算的，就是新的基线。
         */
        SET,
        /**
         * 按<b>既有</b>的依赖列在当前快照上重算一次（re-baseline）。人重新确认口径、但没有重发依赖列时走这一档。
         *
         * <p>为什么必须重算而不是原样留着：人重新确认的意思就是「我知道结构变过，口径照这样算」。
         * 只把 {@code status} 翻回 CONFIRMED、锚点还留着旧基线，下一次刷新重算依然对不上，
         * 会把人刚确认的口径<b>再一次</b>悄悄标成 STALE、停止注入——而他完全不知道自己的确认被撤销了。
         *
         * <p>既有行本来就是 {@link #ANCHOR_NONE}（纯人工口径，没有算式）时<b>一列都不动</b>：
         * 给它现编一个锚点等于凭空给它加上一个会过期的理由。
         */
        REBASE
    }

    /**
     * 人在对话里写下的一条语义断言：{@link #upsertHuman} 的全部入参，五个 scope 共用一个形状。
     *
     * <h4>三列键怎么填（唯一键 = tenant_id, connector_id, scope, object_name, field_name, term）</h4>
     * <pre>
     * OBJECT  object=表名   field=""     term=""    anchor=NONE
     * FIELD   object=表名   field=列名   term=""    anchor=FIELD
     * JOIN    object=左表   field=左列   term=""    anchor=JOIN，detail 必带 to_object / to_column
     * METRIC  object=""     field=""     term=词条  anchor=NONE 或 COLUMN_SET（给了依赖列时）
     * CAVEAT  object=""     field=""     term=词条  anchor=NONE
     * </pre>
     * 填错的表现不是报错，是<b>写进另一行</b>：唯一键换了一组值，覆盖就变成了新增，
     * 同一件事在库里有了两条各说各话的记录。
     */
    @Data
    @Builder
    public static class HumanWrite {
        /** {@link #SCOPE_OBJECT} / {@link #SCOPE_FIELD} / {@link #SCOPE_JOIN} / {@link #SCOPE_METRIC} / {@link #SCOPE_CAVEAT}。 */
        private String scope;
        private String objectName;
        private String fieldName;
        private String term;
        /** 给模型看的那句话。空白一律拒写——一行没有 gloss 的语义什么都不说，却会把这个键占掉。 */
        private String gloss;
        /** {@code detail_json} 的内容。{@code null} = 覆盖时沿用既有的那一份（只去掉过期名单）。 */
        private Map<String, Object> detail;
        /**
         * 依据来源。人答的东西<b>不在客户的库里</b>——{@link #EV_COMMENT} / {@link #EV_DATA} 都是假话，
         * 只能是 {@link #EV_GUESS}；而 CAVEAT 是「一个还没有答案的问题」、根本不是断言，传 {@code null}。
         */
        private String evidence;
        /** 插入时写的锚点种类。{@link #ANCHOR_NONE} / {@link #ANCHOR_FIELD} / {@link #ANCHOR_JOIN}。 */
        private String anchorKind;
        /** 插入时写的锚点指纹。调用方从<b>快照</b>算好传进来（理由见 {@code ConnectorSnapshotColumnService}）。 */
        private String anchorHash;
        /**
         * METRIC 的依赖列：算式里<b>真正出现</b>的那几列，已经盖好每列指纹。非空时锚点改写成
         * {@link #ANCHOR_COLUMN_SET}，并把名单落进 {@code detail_json}。其余 scope 传空。
         */
        private List<AnchorColumn> anchorColumns;
        /** 上一次刷新的结构快照（表名 → 列名 → 列）。算 COLUMN_SET 锚点与 {@link HumanAnchor#REBASE} 用。 */
        private Map<String, Map<String, FieldDetail>> snapshot;
        /** 覆盖时锚点怎么办。 */
        private HumanAnchor onOverwrite;
        /** 报错与日志里这条断言的称呼，例如「口径「销售额」」。会原样出现在模型看到的错误文案里。 */
        private String subject;
        private String answeredBy;
        private String answeredName;
        private String traceId;
    }

    /**
     * 写下一条人确认过的语义（任意 scope）。<b>这是全仓库唯一一条从对话写 {@code source=HUMAN} 的路。</b>
     *
     * <p>为什么必须显式记 {@code answeredBy} 而不靠 {@code BaseEntity.createUser}：语义是在对话流里
     * 沉淀的，对话跑在 {@code streamExecutor} 上，{@code MdcAsyncSupport.wrap} 不传
     * {@code RequestContextHolder}，于是 {@code MyMetaObjectHandler} 拿不到用户、create_user 是 null。
     *
     * <p>覆盖既有行时把<b>旧值</b>追加进 history_json。平台不提供管理台的口径纠正入口，
     * 任何能对话的人都能覆盖且不做权限区分——这是那个已知代价的唯一取证材料。
     * JOIN 的留痕额外带上旧的对端与旧的 {@code verified}（见 {@link #appendHistory}）：
     * 一条采样核过的关系被人改了对端之后，不留这两样就<b>再也回不去</b>。
     *
     * <h3>★ 人确认一次，就清掉「是哪几张消失的表让它过期的」名单（{@link #KEY_STALE_REMOVED}）</h3>
     * <b>不传 detail 时也清。</b>模型在对话里重新确认往往只补一句话、不重发结构化细节；名单若留着，
     * 下一次刷新看到名单里那张表仍然不在（它确实不在），就会把人刚确认的东西悄悄重新标成 STALE、停止注入，
     * 而人完全不知道自己的确认被撤销了。确认本身就是那句「我知道那张表没了，照这样算」。
     * 旧名单不会丢：它在旧 detail 里，而旧 detail 整份进了留痕。
     *
     * <h3>★ 覆盖路径一次都不 set {@code verified}</h3>
     * 那一列的含义是「采样核过的结论」。人说「这两张表没关系」要写进 {@link #KEY_HUMAN_VERDICT}，
     * 绝不能写成 {@link #V_REJECTED}——完整理由见那个常量的注释。
     *
     * <h3>★ 不在事务里；写回撞上别处的写就重读重试</h3>
     * 这里是「读旧值 → 追加留痕 → 写回」。中间若有别处的写落地（另一个人同时确认同一个词条、漂移处置刚给它挂上过期名单），
     * 整行写回会把那一次盖掉而不留痕：留痕里少一条旧值，或者一份过期名单连同它的来龙去脉一起消失。
     * 所以写回以「这一行仍是读到时的样子」为条件，不成立就重读、在新的旧值上再追加一次留痕。
     * <b>刻意不加 {@code @Transactional}</b>：可重复读隔离级别下同一个事务里的重读仍是第一次读的快照，重试会永远撞同一个条件；
     * 每条语句各自提交，重读才看得见别人刚写下的东西。插入撞唯一键（同一条断言刚被另一次确认插进去）同样重读、按覆盖处理——
     * 让这一次回答报错消失，不如让它排在后面、把前一次的回答留进留痕。
     * 重试 {@value #WRITE_ATTEMPTS} 次仍写不进去就报错：宁可让模型当场说「没记住，请再说一次」，也不能假装记住了。
     * 覆盖时 {@code answered_by} / {@code trace_id} 这类可空列按这次给的值<b>显式</b>写下去（包括 null）——
     * 留着上一次的 trace_id，「这条断言是在哪次对话里答的」就指向了另一段对话。
     */
    public ConnectorSemantic upsertHuman(Long connectorId, HumanWrite w) {
        if (w.getGloss() == null || w.getGloss().isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "语义说明不能为空");
        }
        Connection conn = requireOwned(connectorId);
        String object = nullToEmpty(w.getObjectName()).trim();
        String field = nullToEmpty(w.getFieldName()).trim();
        String term = nullToEmpty(w.getTerm()).trim();
        String gloss = w.getGloss().trim();
        Map<String, Map<String, FieldDetail>> snapshot = w.getSnapshot() == null ? Map.of() : w.getSnapshot();

        // 依赖列一给就改写锚点：口径从此锚在算式引用到的那几列上，任一列改了名 / 类型 / 可空 / 注释都让它失效。
        // 算不出来（列不在快照里）就退回无锚——宁可它永不过期，也不要挂一个当场就对不上的假锚点。
        List<AnchorColumn> columns = w.getAnchorColumns() == null ? List.of() : w.getAnchorColumns();
        String insertKind = w.getAnchorKind();
        String insertHash = w.getAnchorHash();
        if (!columns.isEmpty()) {
            String setHash = columnSetAnchor(columns, snapshot);
            insertKind = setHash == null ? ANCHOR_NONE : ANCHOR_COLUMN_SET;
            insertHash = setHash;
        }

        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            ConnectorSemantic existing = semanticMapper.selectOne(new LambdaQueryWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getConnectorId, connectorId)
                    .eq(ConnectorSemantic::getScope, w.getScope())
                    .eq(ConnectorSemantic::getObjectName, object)
                    .eq(ConnectorSemantic::getFieldName, field)
                    .eq(ConnectorSemantic::getTerm, term)
                    .last("limit 1"));

            Date now = new Date();
            if (existing == null) {
                ConnectorSemantic row = new ConnectorSemantic();
                row.setTenantId(conn.getTenantId());
                row.setConnectorId(connectorId);
                row.setScope(w.getScope());
                row.setObjectName(object);
                row.setFieldName(field);
                row.setTerm(term);
                row.setGloss(gloss);
                row.setDetailJson(humanDetailJson(w.getDetail(), columns));
                row.setSource(SOURCE_HUMAN);
                row.setEvidence(w.getEvidence());
                row.setStatus(ST_CONFIRMED);
                // 新行只能是 NONE：这条断言从来没有被采样核过。人的否定判断进 detail 的 human_verdict，
                // 绝不借 REJECTED 表达——那会让一句人说的话伪装成一次采样结论，而且再也纠正不了。
                row.setVerified(V_NONE);
                row.setAnchorKind(insertKind);
                row.setAnchorHash(insertHash);
                row.setAnsweredBy(w.getAnsweredBy());
                row.setAnsweredName(w.getAnsweredName());
                row.setAnsweredAt(now);
                row.setTraceId(w.getTraceId());
                try {
                    semanticMapper.insert(row);
                    return row;
                } catch (DuplicateKeyException e) {
                    log.info("{} 刚被另一次确认插入，重读后按覆盖处理 connectorId={} attempt={}",
                            w.getSubject(), connectorId, attempt);
                    continue;
                }
            }

            // 覆盖：先把旧值留痕，再改。
            String history = appendHistory(existing, w.getAnsweredBy(), w.getAnsweredName(), w.getTraceId(), now);
            String newDetail = overwriteDetail(existing, w.getDetail(), columns);
            String setKind = null;
            String setHash = null;
            if (w.getOnOverwrite() == HumanAnchor.SET) {
                setKind = insertKind;
                setHash = insertHash;
            } else if (ANCHOR_COLUMN_SET.equals(existing.getAnchorKind())) {
                List<AnchorColumn> rebased = restamp(parseAnchorColumns(readDetail(newDetail, existing.getId())
                        .get(KEY_ANCHOR_COLUMNS)), snapshot);
                String rebasedHash = rebased == null ? null : columnSetAnchor(rebased, snapshot);
                if (rebasedHash != null) {
                    setKind = ANCHOR_COLUMN_SET;
                    setHash = rebasedHash;
                    newDetail = withAnchorColumns(newDetail, existing.getId(), rebased);
                }
            }

            LambdaUpdateWrapper<ConnectorSemantic> u = new LambdaUpdateWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getId, existing.getId());
            unchangedSince(u, existing);
            u.set(ConnectorSemantic::getGloss, gloss)
                    .set(ConnectorSemantic::getDetailJson, newDetail)
                    .set(ConnectorSemantic::getSource, SOURCE_HUMAN)
                    // ★ evidence 必须跟着这次的值改。覆盖的很可能是一条推导出来的行（evidence=COMMENT/DATA），
                    // 不改的话「客户库自己写的注释」这个来源标签就挂在了一句人说的话上——
                    // 而注入层正是照 evidence 判这句话该不该被当成一手事实采信。
                    .set(ConnectorSemantic::getEvidence, w.getEvidence())
                    .set(ConnectorSemantic::getStatus, ST_CONFIRMED)
                    .set(ConnectorSemantic::getAnsweredBy, w.getAnsweredBy())
                    .set(ConnectorSemantic::getAnsweredName, w.getAnsweredName())
                    .set(ConnectorSemantic::getAnsweredAt, now)
                    .set(ConnectorSemantic::getTraceId, w.getTraceId())
                    .set(ConnectorSemantic::getHistoryJson, history);
            if (setKind != null) {
                u.set(ConnectorSemantic::getAnchorKind, setKind)
                        .set(ConnectorSemantic::getAnchorHash, setHash);
            }
            if (semanticMapper.update(null, u) > 0) {
                existing.setHistoryJson(history);
                existing.setGloss(gloss);
                existing.setDetailJson(newDetail);
                existing.setSource(SOURCE_HUMAN);
                existing.setEvidence(w.getEvidence());
                existing.setStatus(ST_CONFIRMED);
                existing.setAnsweredBy(w.getAnsweredBy());
                existing.setAnsweredName(w.getAnsweredName());
                existing.setAnsweredAt(now);
                existing.setTraceId(w.getTraceId());
                if (setKind != null) {
                    existing.setAnchorKind(setKind);
                    existing.setAnchorHash(setHash);
                }
                return existing;
            }
            log.info("{} 写回时这一行刚被别处改过，重读后在新的旧值上再留一次痕 connectorId={} attempt={}",
                    w.getSubject(), connectorId, attempt);
        }
        throw new ServiceException(ExceptionCode.SERVER_BUSY,
                w.getSubject() + "正在被同时修改，这一次没有记下来，请再确认一次");
    }

    /**
     * 沉淀一条业务口径。<b>这是 §5 的全部价值所在：模型现在本来就在问，缺的是问完之后记住。</b>
     *
     * <p>旧签名，等价于「没有依赖列、也没有快照」：口径不挂锚点，结构怎么变它都照样注入。
     * 纯人工口径（「客户指下单人」）本来就该如此；带算式的口径请走下面那个重载。
     */
    public ConnectorSemantic defineMetric(Long connectorId, String term, String gloss,
                                          Map<String, Object> detail,
                                          String answeredBy, String answeredName, String traceId) {
        return defineMetric(connectorId, term, gloss, detail, List.of(), Map.of(),
                answeredBy, answeredName, traceId);
    }

    /**
     * 沉淀一条业务口径，并把它锚在算式真正引用到的那几列上。
     *
     * <h3>为什么口径也要有能失效的锚</h3>
     * 列被删掉还算吵闹（查询直接报错）；<b>列被复用成别的含义才是要命的那一种</b>——
     * SQL 照跑、行数照出，数字静默地错，而模型会一直照着这条口径写下去。
     *
     * <h3>覆盖时的三个分支</h3>
     * <ol>
     *   <li><b>这次带了依赖列</b>：整份重算，新的列集合就是新基线；</li>
     *   <li><b>没带、而既有行本来就锚着列集合</b>：按既有名单在当前快照上 re-baseline
     *       （理由见 {@link HumanAnchor#REBASE}）；</li>
     *   <li><b>没带、既有行是 {@link #ANCHOR_NONE}</b>：锚点那两列<b>一个都不 set</b>。
     *       给一条纯人工口径现编一个锚点，等于凭空给它加上一个会过期的理由。</li>
     * </ol>
     *
     * @param dependsOn 算式里<b>真正出现</b>的列（调用方已按快照核对过并盖好每列指纹）。
     *                  多填一列的代价很具体：那一列改个注释就会让整条口径停止注入
     * @param snapshot  上一次刷新的结构快照，算锚点与 re-baseline 用
     */
    public ConnectorSemantic defineMetric(Long connectorId, String term, String gloss,
                                          Map<String, Object> detail,
                                          List<AnchorColumn> dependsOn,
                                          Map<String, Map<String, FieldDetail>> snapshot,
                                          String answeredBy, String answeredName, String traceId) {
        if (term == null || term.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "口径词条不能为空");
        }
        if (gloss == null || gloss.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "口径说明不能为空");
        }
        List<AnchorColumn> cols = dependsOn == null ? List.of() : dependsOn;
        return upsertHuman(connectorId, HumanWrite.builder()
                .scope(SCOPE_METRIC)
                .objectName("")
                .fieldName("")
                .term(term.trim())
                .gloss(gloss)
                .detail(detail)
                // 人答的口径依据是"人说的"，不是数据也不是注释。它恰恰是数据库里查不到答案的那一类。
                .evidence(EV_GUESS)
                .anchorKind(ANCHOR_NONE)
                .anchorHash(null)
                .anchorColumns(cols)
                .snapshot(snapshot)
                .onOverwrite(cols.isEmpty() ? HumanAnchor.REBASE : HumanAnchor.SET)
                .subject("口径「" + term.trim() + "」")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
                .traceId(traceId)
                .build());
    }

    /**
     * 「这张表是干什么的」。OBJECT 行<b>不锚结构</b>：客户加一列并不改变这句话，
     * 只有整张表消失才让它失效（见建表脚本里锚点那一段的论证）。
     */
    public ConnectorSemantic annotateObject(Long connectorId, String objectName, String note,
                                            String answeredBy, String answeredName, String traceId) {
        return upsertHuman(connectorId, HumanWrite.builder()
                .scope(SCOPE_OBJECT)
                .objectName(objectName)
                .fieldName("")
                .term("")
                .gloss(note)
                .evidence(EV_GUESS)
                .anchorKind(ANCHOR_NONE)
                .onOverwrite(HumanAnchor.SET)
                .subject("表「" + objectName + "」的说明")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
                .traceId(traceId)
                .build());
    }

    /**
     * 「这一列什么意思」。这是 B4 里量最大的一类（实测 434 条语义里有 99 条 FIELD 自己写着「未确认 / 含义不清楚」）。
     *
     * @param anchorHash 调用方从<b>快照</b>里这一列算出的 {@link #fieldAnchor}。
     *                   为空表示快照里找不到这一列——调用方本该在此之前就拒写，这里兜底成无锚
     */
    public ConnectorSemantic annotateField(Long connectorId, String objectName, String columnName, String note,
                                           String anchorHash,
                                           String answeredBy, String answeredName, String traceId) {
        return upsertHuman(connectorId, HumanWrite.builder()
                .scope(SCOPE_FIELD)
                .objectName(objectName)
                .fieldName(columnName)
                .term("")
                .gloss(note)
                .evidence(EV_GUESS)
                .anchorKind(anchorHash == null ? ANCHOR_NONE : ANCHOR_FIELD)
                .anchorHash(anchorHash)
                .onOverwrite(HumanAnchor.SET)
                .subject("字段「" + objectName + "." + columnName + "」的说明")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
                .traceId(traceId)
                .build());
    }

    /**
     * 「这两张表怎么连」，以及<b>「这两张表没关系」</b>。
     *
     * <p>后者是 B4 之前根本写不进去的那一种：JOIN 连覆盖都做不到，因为「没关系」不是一个能写进去的值。
     * 现在它是 {@link #KEY_HUMAN_VERDICT} 的一个取值，而 {@code verified} 那一列<b>一次都不 set</b>——
     * 用 {@link #V_REJECTED} 表达会让这句话伪装成一次采样结论，并被 {@link #mergeInferred} 整组搬运下去。
     *
     * @param related    人说这两列是不是同一个东西
     * @param anchorHash 调用方从快照里两端列算出的 {@link #joinAnchor}
     */
    public ConnectorSemantic annotateJoin(Long connectorId, String objectName, String columnName,
                                          String toObject, String toColumn, boolean related, String note,
                                          String anchorHash,
                                          String answeredBy, String answeredName, String traceId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(KEY_TO_OBJECT, toObject);
        detail.put(KEY_TO_COLUMN, toColumn);
        detail.put(KEY_HUMAN_VERDICT, related ? HV_RELATED : HV_UNRELATED);
        detail.put("basis", related ? "由业务方在对话中确认" : "业务方在对话中明确说这两张表没有关系");
        return upsertHuman(connectorId, HumanWrite.builder()
                .scope(SCOPE_JOIN)
                .objectName(objectName)
                .fieldName(columnName)
                .term("")
                .gloss(note)
                .detail(detail)
                .evidence(EV_GUESS)
                .anchorKind(anchorHash == null ? ANCHOR_NONE : ANCHOR_JOIN)
                .anchorHash(anchorHash)
                .onOverwrite(HumanAnchor.SET)
                .subject("关系「" + objectName + "." + columnName + " → " + toObject + "." + toColumn + "」")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
                .traceId(traceId)
                .build());
    }

    /**
     * 「用这条连接的时候要注意什么」。
     *
     * <p>{@code evidence} 传 {@code null} 是有意的：CAVEAT 不是一条断言，而是一句提醒 / 一个还没有答案的问题，
     * 给它挂任何一个依据来源都是在说「这句话有某种出处」。
     */
    public ConnectorSemantic annotateCaveat(Long connectorId, String term, String note, List<String> appliesTo,
                                            String answeredBy, String answeredName, String traceId) {
        Map<String, Object> detail = null;
        if (appliesTo != null && !appliesTo.isEmpty()) {
            detail = new LinkedHashMap<>();
            detail.put(KEY_APPLIES_TO, appliesTo);
        }
        return upsertHuman(connectorId, HumanWrite.builder()
                .scope(SCOPE_CAVEAT)
                .objectName("")
                .fieldName("")
                .term(term)
                .gloss(note)
                .detail(detail)
                .evidence(null)
                .anchorKind(ANCHOR_NONE)
                .onOverwrite(HumanAnchor.SET)
                .subject("告诫「" + term + "」")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
                .traceId(traceId)
                .build());
    }

    /** 调用方给的 detail 落库形状：带进来的过期名单一律拿掉（它只该由漂移处置写），依赖列名单按本次的重写。 */
    private String humanDetailJson(Map<String, Object> detail, List<AnchorColumn> columns) {
        if (detail == null && columns.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = detail == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detail);
        copy.remove(KEY_STALE_REMOVED);
        if (!columns.isEmpty()) {
            copy.put(KEY_ANCHOR_COLUMNS, detailColumns(columns));
        }
        return toJson(copy);
    }

    /**
     * 覆盖时 {@code detail_json} 写成什么。
     *
     * <p>三种情形刻意分开：这次给了 detail 就整份替换；没给但补了依赖列，就<b>只</b>换掉依赖列名单、
     * 其余键一个字不动（「重新确认一次口径」不等于「把我没提的那些细节清空」）；两样都没给就沿用既有的，
     * 只去掉过期名单。
     */
    private String overwriteDetail(ConnectorSemantic existing, Map<String, Object> detail,
                                   List<AnchorColumn> columns) {
        if (detail != null) {
            return humanDetailJson(detail, columns);
        }
        if (!columns.isEmpty()) {
            return withAnchorColumns(withoutStaleRemoved(existing.getDetailJson(), existing.getId()),
                    existing.getId(), columns);
        }
        return withoutStaleRemoved(existing.getDetailJson(), existing.getId());
    }

    /** 在既有 detail 上原地换掉依赖列名单。序列化失败就原样返回——少一次 re-baseline，好过把整份 detail 写丢。 */
    private String withAnchorColumns(String detailJson, Long id, List<AnchorColumn> columns) {
        Map<String, Object> d = new LinkedHashMap<>(readDetail(detailJson, id));
        d.put(KEY_ANCHOR_COLUMNS, detailColumns(columns));
        String json = toJson(d);
        return json == null ? detailJson : json;
    }

    /** 形状由 {@link AnchorColumn#toDetailMap} 一处产出，写入侧不要在别处各拼各的键名。 */
    private static List<Map<String, Object>> detailColumns(List<AnchorColumn> columns) {
        List<Map<String, Object>> out = new ArrayList<>(columns.size());
        for (AnchorColumn c : columns) {
            out.add(c.toDetailMap());
        }
        return out;
    }

    /**
     * 按当前快照重新给每一列盖一次指纹（re-baseline 的那一步）。
     *
     * @return {@code null} = 名单读不出来，或者有列已经不在快照里了。<b>两种都不动锚点</b>：
     *         后者意味着那条 STALE 是真的，人这一次确认改不了「列没了」这个事实
     */
    private static List<AnchorColumn> restamp(List<AnchorColumn> columns,
                                              Map<String, Map<String, FieldDetail>> snapshot) {
        if (columns == null || columns.isEmpty() || snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        List<AnchorColumn> out = new ArrayList<>(columns.size());
        for (AnchorColumn c : columns) {
            FieldDetail f = column(objectColumns(snapshot, c.objectName()), c.columnName());
            if (f == null) {
                return null;
            }
            out.add(new AnchorColumn(c.objectName(), c.columnName(), fieldAnchor(f)));
        }
        return out;
    }

    /**
     * 既有 detail 去掉过期名单。<b>没有名单时原样返回同一个字符串</b>——不传 detail 不等于「把 detail 清空」，
     * 也不该为了一次确认把人写的 JSON 重新排版一遍。
     */
    private String withoutStaleRemoved(String detailJson, Long id) {
        Map<String, Object> d = new LinkedHashMap<>(readDetail(detailJson, id));
        if (!d.containsKey(KEY_STALE_REMOVED)) {
            return detailJson;
        }
        d.remove(KEY_STALE_REMOVED);
        String json = toJson(d);
        return json == null ? detailJson : json;
    }

    @SuppressWarnings("unchecked")
    private String appendHistory(ConnectorSemantic row, String by, String byName, String traceId, Date at) {
        List<Object> hist = new ArrayList<>();
        if (row.getHistoryJson() != null && !row.getHistoryJson().isBlank()) {
            try {
                Object parsed = CommonUtil.getObjectMapper().readValue(row.getHistoryJson(), List.class);
                hist.addAll((List<Object>) parsed);
            } catch (Exception e) {
                // 历史坏了不该挡住这次覆盖——留痕是取证材料，不是前置条件。
                log.warn("解析口径变更留痕失败，本次从空开始 id={}", row.getId(), e);
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", at);
        entry.put("by", by);
        entry.put("by_name", byName);
        entry.put("from_gloss", row.getGloss());
        entry.put("from_detail", row.getDetailJson());
        // ★ 旧的采样结论必须单独留一份。覆盖路径一次都不 set verified（见 upsertHuman），
        //   所以列上那个值不会被这次写动到；但下一次推导 / 验证可能会改它，届时「人改这条关系之前
        //   平台到底验出过什么」就只剩这里了。JOIN 被人改掉对端之后，那个结论再也回不去。
        entry.put("from_verified", row.getVerified());
        // 对端单独抄一份，不指望读留痕的人再去解一次 from_detail 那串 JSON。
        Map<String, Object> old = readDetail(row);
        if (old.get(KEY_TO_OBJECT) != null || old.get(KEY_TO_COLUMN) != null) {
            entry.put("from_to_object", old.get(KEY_TO_OBJECT));
            entry.put("from_to_column", old.get(KEY_TO_COLUMN));
        }
        entry.put("trace_id", traceId);
        hist.add(entry);
        while (hist.size() > HISTORY_MAX) {
            hist.remove(0);
        }
        return toJson(hist);
    }

    // ================================================================ 写（数据出库档位下调）

    /**
     * ★ 删掉这条连接在我们库里存着的第 3 档真实取值（契约 K-5）。<b>只走 mapper。</b>
     *
     * <h3>为什么档位一降就必须删，不能只靠读出侧拦着</h3>
     * 注入路径在读出时已经按档位拦了值域。那只保证「不再给模型看」，不保证「不在我们库里」：
     * 客户收回第 3 档的授权，意思是他的真实取值不该再留在平台上；而库里那些取值集合、判别值会一直躺着，
     * 直到有人整层重新生成——没有任何信号提醒谁去做这件事。
     *
     * <h3>删什么</h3>
     * <ul>
     *   <li>{@code value_domain}：取值列表（以及任何列表形态的键）拿掉，片段保留成「不完整 + 一句为什么」。
     *       <b>不整段删</b>：注入层对「有片段、没取值」会告诉模型不要写死条件；整段删了，这一列被读成「平台对它没有记录」，
     *       最要紧的那句提醒就没了。{@code distinct_count} 是第 2 档本来就允许的统计量，留着。</li>
     *   <li>{@code discriminator_value}：拿掉；{@code care_reason} 等文本里按 {@code = '取值'} 原样写进去的换成占位，
     *       换完仍含这个取值就整句换成不带取值的警告；{@code probed_with_sample_values} 置 false——
     *       档位将来再开到第 3 档时这条多态外键要能重探一次判别值，否则它永远不会再有。</li>
     *   <li>值域阶段补写的 FIELD 行（契约 K-3 的识别规则，见 {@link #isValueProfileRow}）：gloss 换成 {@link #PURGED_VALUE_GLOSS}。
     *       <b>与值域在同一条 UPDATE 里改</b>：注入层判「这句 gloss 会不会带出取值」看的是值域里还有没有取值，
     *       先删值域、后改 gloss，中间那一刻旧 gloss 会原样出去。</li>
     * </ul>
     *
     * <h3>回写纪律</h3>
     * 与漂移处置同一条：只写改的列，以读到时的样子为条件；被别处抢先写过的行重读重来，最多 {@value #WRITE_ATTEMPTS} 轮。
     * 在调用方的事务里重读看到的是快照、重来无济于事——所以改档位的那一处在事务提交之后还会再调一次，
     * 结构刷新也会在档位不允许时顺手再扫一遍（晚到的采集结果不会因此长期留在库里）。
     *
     * @return 改动的行数
     */
    public int purgeSampleValues(Long connectorId) {
        requireOwned(connectorId);
        int changed = 0;
        Set<Long> retry = null;
        for (int round = 1; round <= WRITE_ATTEMPTS; round++) {
            LambdaQueryWrapper<ConnectorSemantic> q = new LambdaQueryWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getConnectorId, connectorId)
                    // 粗筛在库里做（LIKE 里的 _ 是单字符通配，只会多捞、不会漏），细判在下面逐行做。
                    .and(w -> w.like(ConnectorSemantic::getDetailJson, SemanticValueProfiler.DETAIL_KEY)
                            .or().like(ConnectorSemantic::getDetailJson, KEY_DISCRIMINATOR_VALUE)
                            .or().like(ConnectorSemantic::getDetailJson, ORIGIN_VALUE_PROFILE)
                            // 这一支只为捞到 detail 读不出来的存量值域行（isValueProfileRow 按「带着值域键」算）。
                            // 它同时会捞上来模型看结构写、标了 DATA 的说明行：捞上来不等于能改，改不改只听 purgeOf 里那条判据。
                            .or(x -> x.eq(ConnectorSemantic::getScope, SCOPE_FIELD)
                                    .eq(ConnectorSemantic::getSource, SOURCE_INFERRED)
                                    .eq(ConnectorSemantic::getEvidence, EV_DATA)));
            if (retry != null) {
                q.in(ConnectorSemantic::getId, retry);
            }
            List<ConnectorSemantic> rows = semanticMapper.selectList(q);
            Set<Long> conflicted = new LinkedHashSet<>();
            for (ConnectorSemantic r : rows == null ? List.<ConnectorSemantic>of() : rows) {
                Purge p = purgeOf(r);
                if (p == null) {
                    continue;
                }
                LambdaUpdateWrapper<ConnectorSemantic> w = new LambdaUpdateWrapper<ConnectorSemantic>()
                        .eq(ConnectorSemantic::getId, r.getId());
                unchangedSince(w, r);
                if (p.detailJson() != null) {
                    w.set(ConnectorSemantic::getDetailJson, p.detailJson());
                }
                if (p.gloss() != null) {
                    w.set(ConnectorSemantic::getGloss, p.gloss());
                }
                if (semanticMapper.update(null, w) > 0) {
                    changed++;
                } else {
                    conflicted.add(r.getId());
                }
            }
            retry = conflicted.isEmpty() ? null : conflicted;
            if (retry == null) {
                break;
            }
        }
        if (retry != null) {
            // WARN：这几行上的真实取值这一次没删掉。调用方（改档位的事务提交后、结构刷新）还会再扫，但必须看得见。
            log.warn("删除第 3 档真实取值时有行反复被别处改写，这一次没删掉 connectorId={} 行id={}", connectorId, retry);
        }
        if (changed > 0) {
            log.info("已删除第 3 档真实取值 connectorId={} 改动行数={}", connectorId, changed);
        }
        return changed;
    }

    /** 一行该怎么改；{@code null} = 这一行上没有要删的。两个字段 {@code null} 表示那一列不动。 */
    private Purge purgeOf(ConnectorSemantic r) {
        Map<String, Object> parsed = parseDetailOrNull(r.getDetailJson());
        String detailJson = null;
        Map<String, Object> d;
        if (parsed == null) {
            // 解析不了：读出侧同样读不出，但它躺在库里。写着取值键就整份不要，不去猜里面有没有取值。
            d = new LinkedHashMap<>();
            if (mentionsSampleValues(r.getDetailJson())) {
                detailJson = "{}";
            }
        } else {
            d = new LinkedHashMap<>(parsed);
        }
        // 按读到的原行判：detail 读不出来时判据算「带着值域键」，gloss 与上面的 "{}" 落在同一条 UPDATE 里。
        boolean valueRow = isValueProfileRow(r);
        String discriminator = text(d.get(KEY_DISCRIMINATOR_VALUE));
        String toObject = text(d.get("to_object"));
        if (parsed != null && scrubSampleValues(d, r.getObjectName(), r.getFieldName())) {
            detailJson = requireJson(d);
        }
        String gloss = discriminator == null ? r.getGloss() : scrubQuoted(r.getGloss(), discriminator, toObject);
        if (valueRow && !PURGED_VALUE_GLOSS.equals(gloss)) {
            gloss = PURGED_VALUE_GLOSS;
        }
        boolean glossChanged = !Objects.equals(gloss, r.getGloss());
        if (detailJson == null && !glossChanged) {
            return null;
        }
        return new Purge(detailJson, glossChanged ? gloss : null);
    }

    /**
     * ★「这一行是值域阶段写下的、可能带着第 3 档真实取值」的<b>唯一</b>判据（契约 K-3）。
     * 删取值（{@link #purgeOf}）、增量写入剥取值（{@link #withoutSampleValues}）、注入路径挡 gloss 都问这一句，不许各写一份：
     * 几份判据一旦分叉，要么一处删了、另一处照样放出去，要么一处把模型的正常说明当成取值改写掉。
     * <ol>
     *   <li>{@code detail_json} 里 {@code origin = value_profile} → 是，<b>不看来源</b>。这是标记之后写下的行。</li>
     *   <li>标记之前的存量行：{@code source=INFERRED} 且 {@code scope=FIELD} 且 {@code evidence=DATA}，
     *       <b>并且 {@code detail_json} 带着 {@code value_domain} 这个键</b>（值是什么不论，null 也算；detail 读不出来按「带着」算）。</li>
     * </ol>
     *
     * <h4>★ 为什么存量行必须带着 value_domain 键，不能只看 INFERRED + FIELD + DATA</h4>
     * {@code EV_DATA} 不是值域阶段独有的：推导提示词（{@code SemanticPrompts}）让模型给「从类型 / 约束读得出来」的字段说明也标 DATA
     * （「自增主键，一行一个订单」），这类行 detail 为空。只按三列判，默认档连接每次结构刷新都会跑一遍删取值，
     * 把这些说明整句换成 {@link #PURGED_VALUE_GLOSS}——一句「曾在第 3 档采集过」的假话；INFERRED 行不留痕，原来那句永久丢失。
     * 值域阶段补写的行从诞生起就带着 value_domain（上线版 7884bc1 起就是这样），多出的这一条一条补写的存量行都不漏。
     *
     * <p>比较去首尾空白、大小写不敏感：与删取值时库里按 utf8mb4_unicode_ci 做的粗筛一致，也不让注入路径的闸因为一次大小写差异就放行。
     * detail 读不出来往「是」的方向错：错成「是」丢一句注解，错成「否」是取值留在库里、进了提示词，而且没有信号。
     */
    public static boolean isValueProfileRow(ConnectorSemantic row) {
        if (row == null) {
            return false;
        }
        Map<String, Object> detail = parseDetailOrNull(row.getDetailJson());
        if (detail != null && ORIGIN_VALUE_PROFILE.equalsIgnoreCase(text(detail.get(KEY_ORIGIN)))) {
            return true;
        }
        if (!SOURCE_INFERRED.equalsIgnoreCase(text(row.getSource()))
                || !SCOPE_FIELD.equalsIgnoreCase(text(row.getScope()))
                || !EV_DATA.equalsIgnoreCase(text(row.getEvidence()))) {
            return false;
        }
        return detail == null || detail.containsKey(SemanticValueProfiler.DETAIL_KEY);
    }

    /**
     * 就地剥掉一份 detail 里的第 3 档真实取值。删什么、为什么这样删，见 {@link #purgeSampleValues}。
     *
     * @return 真的改了
     */
    static boolean scrubSampleValues(Map<String, Object> d, String fromObject, String fromColumn) {
        boolean changed = false;
        Object vd = d.get(SemanticValueProfiler.DETAIL_KEY);
        if (vd instanceof Map<?, ?> fragment) {
            Map<String, Object> neutral = new LinkedHashMap<>();
            neutral.put("complete", false);
            if (fragment.get("outcome") != null) {
                neutral.put("outcome", fragment.get("outcome"));
            }
            if (fragment.get("distinct_count") != null) {
                neutral.put("distinct_count", fragment.get("distinct_count"));
            }
            neutral.put("note", PURGED_VALUE_NOTE);
            if (!neutral.equals(fragment)) {
                d.put(SemanticValueProfiler.DETAIL_KEY, neutral);
                changed = true;
            }
        } else if (vd != null) {
            // 形状认不出来：整段拿掉，不去猜里面有没有取值。
            d.remove(SemanticValueProfiler.DETAIL_KEY);
            changed = true;
        }

        if (d.containsKey(KEY_DISCRIMINATOR_VALUE)) {
            String value = text(d.remove(KEY_DISCRIMINATOR_VALUE));
            changed = true;
            if (value != null) {
                String toObject = text(d.get("to_object"));
                for (String k : List.of(KEY_CARE_REASON, "basis", "verify_note", "note")) {
                    if (d.get(k) instanceof String s) {
                        d.put(k, scrubQuoted(s, value, toObject));
                    }
                }
                // 占位替换只认得 {@code = '取值'} 这一种写法。换完仍然含有这个取值，就不再赌措辞：整句换成不带取值的警告。
                if (d.get(KEY_CARE_REASON) instanceof String care && care.contains(value)) {
                    d.put(KEY_CARE_REASON, neutralPolymorphicCare(fromObject, fromColumn, d));
                }
                if (Boolean.TRUE.equals(d.get(KEY_PROBED_WITH_SAMPLE_VALUES))) {
                    d.put(KEY_PROBED_WITH_SAMPLE_VALUES, false);
                }
            }
        }
        return changed;
    }

    /** 把文本里按 {@code '取值'} 原样写进去的判别值换成占位。单引号按结构判定那边的写法加倍后再找。 */
    static String scrubQuoted(String text, String value, String toObject) {
        if (text == null || value == null || value.isEmpty()) {
            return text;
        }
        String quoted = "'" + value.replace("'", "''") + "'";
        if (!text.contains(quoted)) {
            return text;
        }
        return text.replace(quoted, "<代表 " + (toObject == null ? "目标表" : toObject)
                + " 的取值（第 3 档采样记录的判别值已删除，先按判别列分组确认）>");
    }

    private static String neutralPolymorphicCare(String fromObject, String fromColumn, Map<String, Object> d) {
        String disc = text(d.get("discriminator_column"));
        String discRef = disc == null ? "同表的判别列" : fromObject + "." + disc;
        String target = text(d.get("to_object")) + "." + text(d.get("to_column"));
        return "多态外键：" + fromObject + "." + fromColumn + " 指向哪张表由 " + discRef + " 的取值决定，关联 " + target
                + " 时必须带上 " + discRef + " 的条件，否则指向别的表的行也会被连上，而且不报错。"
                + "判别值原先按第 3 档采样记录过，已随数据出库档位下调从平台删除：先按 " + discRef + " 分组看一眼再决定。";
    }

    /** 解析不了的 detail 里是否写着取值键（粗判，只用于「解析不了时要不要整份丢掉」）。 */
    private static boolean mentionsSampleValues(String json) {
        return json != null && (json.contains(SemanticValueProfiler.DETAIL_KEY) || json.contains(KEY_DISCRIMINATOR_VALUE));
    }

    // ================================================================ 漂移

    /** 旧调用形状：只知道描述到的那些表，并且当目录是完整的。见 {@link #applyDrift(Long, Map, SnapshotPresence, List)}。 */
    @Transactional
    public StaleResult applyDrift(Long connectorId,
                                  Map<String, Map<String, FieldDetail>> fieldsByObject,
                                  List<String> removedObjects) {
        return applyDrift(connectorId, fieldsByObject, null, removedObjects);
    }

    /**
     * 结构变了之后重挂锚点。<b>粒度按 scope 分，不能统一按表。</b>
     *
     * <p>{@code connector_schema.content_hash} 是整表指纹（表名|类型 + 每列
     * name:type:nullable:comment），客户加一列它就变。若 OBJECT 行也锚它，「这张表是订单主表」
     * 会因为一个无关列被标成 STALE——而加一列并不改变这句话。所以：
     * OBJECT 不锚结构（只有整张表消失才失效）、FIELD 锚自己那一列、JOIN 锚两端列。
     *
     * <h3>★「这次没看到」不等于「没了」：四种处境</h3>
     * 快照最多<b>描述</b> 200 个对象，而目录<b>列出</b>最多 500 个、按重要性排序。一张表只因为 InnoDB 的行数估算
     * 跨过了一个数量级、被挤到第 201 名，就不在描述到的那批里——从前这里把它当成「表没了」：挂在上面的行全部 STALE，
     * {@code applies_to} 写着它的口径停止注入，管理台还叫业务方去重答口径，而那张表好好地躺在客户库里。
     * 所以每个对象名（大小写不敏感）先归到 {@link Presence} 的四种之一：
     * <ul>
     *   <li>{@code DESCRIBED}：有列，照锚点判；</li>
     *   <li>{@code LISTED_ONLY}：目录列了（或单独确认过存在）、没描述——表在，只是这次没有列可比；</li>
     *   <li>{@code ABSENT}：目录没列、目录本身完整（或单独确认过不存在）——真没了；</li>
     *   <li>{@code UNKNOWN}：目录没列、目录本身被截断、也没能确认——不知道。</li>
     * </ul>
     * <b>只有 ABSENT 让行过期。</b>UNKNOWN 上的行一个字不碰：不标过期，也不复活——复活同样是一个结论，
     * 而这次手上没有下结论的依据。LISTED_ONLY 要分行的种类：
     * <ul>
     *   <li><b>只依赖「表在不在」的行</b>（不锚列的 OBJECT / CAVEAT 这类行，以及口径过期名单里的表）：LISTED_ONLY 已经证明表在，
     *       照常判定、可以复活。一概不碰的话，一张被挤出描述上限的表，它那句「这是订单主表」就永远停在 STALE。</li>
     *   <li><b>锚在列上的行</b>（FIELD / JOIN）：没有列可比，不标过期也不复活。</li>
     * </ul>
     * 关系任一端 ABSENT 时照样过期：「指向一张已经不存在的表」是确定的，不需要列来判。
     * <b>描述到了的那一端上列没了</b>同样照样过期，哪怕另一端没看到：{@code orders.user_id} 被删掉之后，这条关系不管
     * {@code users} 在不在都已经不成立。只有真正没看到的那一端可以让判定跳过——从前这里先看到对端没描述就直接跳过，
     * 左列早被删掉的关系于是一直注入。
     *
     * <h3>表消失：按「这次快照里还有没有它」判，不按「这次 diff 里有没有 REMOVED」判</h3>
     * 只看 diff 的话，表在第一次刷新里消失、OBJECT 行被标 STALE；<b>第二次</b>刷新 diff 是空的，
     * 这张表不在 removedObjects 里，OBJECT 行又不锚结构——于是它被当成「结构改回来了」<b>复活</b>，
     * 一张已经不存在的表的说明重新变成有效。所以「表还在不在」以本次的处境为准（ABSENT 就一直过期）。
     *
     * <h3>★「这一次消失的表」只认调用方报的名单</h3>
     * 它是 {@code removedObjects} 里确实 ABSENT 的那些。名单由刷新方给：本次 diff 的 REMOVED，
     * 加上<b>此前某次刷新报过、漂移处置却没做成</b>的待补报名单（刷新方持久化，见 {@code ConnectorSchemaService}）。
     * <p>从前这里还把「本轮有行被新标过期的 ABSENT 表」也算进来，想以此给重试补报。那条路错在两处：
     * <ul>
     *   <li>一张只被口径引用、没有别的语义行的表，重试时一条行都不会「新标过期」，口径就永远补不上——管理台那句「再刷新一次会补报」是假话；</li>
     *   <li>从旧版本升级上来的第一次刷新，会碰到旧代码在早已消失的表上复活过的行。它们被「新标过期」，于是一张几个月前就没了的表
     *       被当成<b>这一次</b>消失：几个月后才由人确认过的口径被牵连标过期，管理台叫业务方把口径重答一遍。</li>
     * </ul>
     * 现在 ABSENT 表上被新标过期、却不在名单里的行照样标过期（表确实不在），但<b>不</b>归因到口径、也不进影响面。
     *
     * <h3>挂在整条连接上的口径（METRIC / CAVEAT）</h3>
     * 它们的 object_name 是空串，从前一张表消失永远牵连不到它们。但 {@code applies_to} 里写着这张表的口径
     * <b>就挂在这张表上</b>：表没了，那段 SQL 片段跑不通或者跑出别的东西，而错的口径不报错。所以：
     * <ul>
     *   <li>「这一次消失的表」出现在 {@code applies_to} 里 → 记进 detail_json 的 {@link #KEY_STALE_REMOVED}，标 STALE；</li>
     *   <li>记下的表这次确认还在（DESCRIBED 或 LISTED_ONLY）→ 从名单里划掉；名单空了就复活（HUMAN 回 CONFIRMED）。
     *       UNKNOWN 不划：与挂在表上的行同一条「不知道就不下结论」的规矩。</li>
     * </ul>
     * <b>为什么不能「applies_to 里的表 ABSENT 就算」</b>：{@code applies_to} 是人或模型写的，可能是笔误——
     * 笔误的名字在完整目录里同样是 ABSENT，一个笔误就会永久停用人答的口径；而人在对话里重新确认过的口径
     * （名单已被 {@link #defineMetric} 清掉），也不该在下一次刷新时被一张早已消失的表重新拖下水。
     * 名单里只会出现<b>在某一次刷新里真的消失过</b>的表。
     *
     * <h3>★ 影响面只数「这一轮新归到这张表名下的」（契约 K-4）</h3>
     * 表上的行：本轮新标成 STALE、且挂着这张表的；口径：这张表本轮才新进它过期名单的。
     * 从前按「现在是 STALE 的」数，一条早就过期的行会被重新算进「这张表带走的」，管理台表头（本轮标过期 N 条）
     * 与按表的口径告警就对不上了。
     *
     * <h3>★ 回写只写这里改的两列，并以「这一行仍是读到时的样子」为条件</h3>
     * 从前读全部行、逐行 {@code updateById} 整行写回。人在对话里确认口径恰好落在这一轮中间时，他新写的 gloss、留痕、
     * 回答人会被这里读到的旧值整行盖掉，没有任何痕迹。现在只写 {@code status} / {@code detail_json}，
     * 条件见 {@link #unchangedSince}；影响 0 行 = 别处刚写过、它赢，本轮跳过这一行、不计数、不归因
     * （计入 {@link StaleResult#concurrentSkips()}，下一次刷新按那时的样子重判）。
     *
     * @param fieldsByObject 本次<b>描述到的</b>每张表的字段（表名 → 列名 → FieldDetail）
     * @param presence       本次目录看到了什么；{@code null} = 只知道描述到的那些、并当目录完整（旧调用形状）
     * @param removedObjects 这一次要按「消失」归因的表名（diff 的 REMOVED + 待补报名单）；其中不是 ABSENT 的会被忽略
     */
    @Transactional
    public StaleResult applyDrift(Long connectorId,
                                  Map<String, Map<String, FieldDetail>> fieldsByObject,
                                  SnapshotPresence presence,
                                  List<String> removedObjects) {
        requireOwned(connectorId);
        List<ConnectorSemantic> rows = semanticMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemantic>().eq(ConnectorSemantic::getConnectorId, connectorId));

        DriftView view = new DriftView(fieldsByObject == null ? Map.of() : fieldsByObject, presence);

        // 「这一次消失的表」：折叠名 → 原写法。保持入参顺序（管理台按这个顺序列）；不是 ABSENT 的不收。
        Map<String, String> vanished = new LinkedHashMap<>();
        if (removedObjects != null) {
            for (String name : removedObjects) {
                if (name != null && !name.isBlank() && view.state(name) == Presence.ABSENT) {
                    vanished.putIfAbsent(ciFold(name), name);
                }
            }
        }

        int staled = 0;
        int revived = 0;
        int skipped = 0;
        Map<String, Integer> rowsHit = new HashMap<>();
        Map<String, List<String>> metricsHit = new HashMap<>();
        List<ConnectorSemantic> onConnection = new ArrayList<>();

        // ── 第一遍：挂在具体表上的行 ──
        for (ConnectorSemantic r : rows) {
            String obj = r.getObjectName() == null ? "" : r.getObjectName();
            if (obj.isEmpty() && (SCOPE_METRIC.equals(r.getScope()) || SCOPE_CAVEAT.equals(r.getScope()))) {
                onConnection.add(r);
                continue;
            }
            AnchoredVerdict v = judgeAnchored(r, obj, view);
            if (v == null) {
                // 没看到、又不能只凭「表在」判：这次没有下结论的依据，状态原样留着。
                continue;
            }
            String target = flippedStatus(r, v.stale());
            // COLUMN_SET 行过期时顺手记下是哪几列变了，复活时清掉。别的锚点一律返回 null（detail 不动）。
            String newDetail = staleColumnsDetail(r, v.stale() ? v.changedColumns() : List.of());
            if (target == null && newDetail == null) {
                continue;
            }
            if (!writeDrift(r, target == null ? r.getStatus() : target, newDetail)) {
                skipped++;
                continue;
            }
            if (newDetail != null) {
                r.setDetailJson(newDetail);
            }
            if (target == null) {
                continue;
            }
            r.setStatus(target);
            if (!v.stale()) {
                revived++;
                continue;
            }
            staled++;
            for (String k : v.absent().keySet()) {
                if (vanished.containsKey(k)) {
                    hit(r, k, rowsHit, metricsHit);
                }
            }
        }

        // ── 第二遍：挂在整条连接上的口径 / 告诫 ──
        for (ConnectorSemantic r : onConnection) {
            Map<String, Object> d = new LinkedHashMap<>(readDetail(r));
            List<String> before = stringList(d.get(KEY_STALE_REMOVED));
            Map<String, String> c = new LinkedHashMap<>();
            for (String x : before) {
                c.putIfAbsent(ciFold(x), x);
            }
            List<String> added = new ArrayList<>();
            for (String a : stringList(d.get("applies_to"))) {
                String k = ciFold(a);
                if (vanished.containsKey(k) && c.putIfAbsent(k, a) == null) {
                    added.add(k);
                }
            }
            // 名单只关心「表在不在」：确认还在（描述到，或目录列出 / 单独确认存在）就划掉。
            c.values().removeIf(x -> view.state(x).exists());
            List<String> causes = new ArrayList<>(c.values());

            // 锚点这一次判成什么。COLUMN_SET 会答三值，其余锚点只有 OK / STALE。
            AnchorJudgement j = anchorJudgement(r, view);
            boolean noBasis = j.verdict() == AnchorVerdict.NO_BASIS;
            // 「是哪几列变了」的名单：没有依据的这一轮一个字都不改（改成空 = 悄悄说「已经不变了」）。
            List<String> changedCols = j.stale() ? j.changedColumns() : List.of();
            boolean colsChanged = !noBasis && ANCHOR_COLUMN_SET.equals(r.getAnchorKind())
                    && !stringList(d.get(KEY_STALE_COLUMNS)).equals(changedCols);

            String newDetail = null;
            if (!causes.equals(before) || colsChanged) {
                if (causes.isEmpty()) {
                    d.remove(KEY_STALE_REMOVED);
                } else {
                    d.put(KEY_STALE_REMOVED, causes);
                }
                if (colsChanged) {
                    if (changedCols.isEmpty()) {
                        d.remove(KEY_STALE_COLUMNS);
                    } else {
                        d.put(KEY_STALE_COLUMNS, changedCols);
                    }
                }
                newDetail = toJson(d);
                if (newDetail == null) {
                    // 名单写不下去就整条不动：状态翻了、名单没记，下一次刷新就不知道它为什么过期。
                    skipped++;
                    continue;
                }
            }
            // 「这一次消失的表」是与锚点无关的另一份证据：表确实不在了，口径必然不成立，锚点有没有依据都一样。
            boolean shouldStale = !causes.isEmpty() || j.stale();
            String target = flippedStatus(r, shouldStale);
            if (noBasis && !shouldStale) {
                // ★ 没有依据就一个字不动，尤其是不复活：复活同样是一个结论，而这次手上没有下结论的材料。
                target = null;
            }
            if (target == null && newDetail == null) {
                continue;
            }
            if (!writeDrift(r, target == null ? r.getStatus() : target, newDetail)) {
                skipped++;
                continue;
            }
            if (newDetail != null) {
                r.setDetailJson(newDetail);
            }
            if (target != null) {
                r.setStatus(target);
                if (shouldStale) {
                    staled++;
                } else {
                    revived++;
                }
            }
            for (String k : added) {
                hit(r, k, rowsHit, metricsHit);
            }
        }

        List<ObjectStale> impact = new ArrayList<>();
        for (Map.Entry<String, String> e : vanished.entrySet()) {
            int n = rowsHit.getOrDefault(e.getKey(), 0);
            List<String> terms = metricsHit.getOrDefault(e.getKey(), List.of());
            if (n + terms.size() > 0) {
                impact.add(new ObjectStale(e.getValue(), n, terms.size(), List.copyOf(terms)));
            }
        }
        if (skipped > 0) {
            log.info("漂移处置有 {} 行在回写时已被别处改过，本轮没有覆盖，下一次刷新重判 connectorId={}", skipped, connectorId);
        }
        return new StaleResult(staled, revived, List.copyOf(impact), skipped);
    }

    /** METRIC / CAVEAT 的 detail_json 里记「是哪几张消失的表让它过期的」。追加键，不在共享契约里。 */
    static final String KEY_STALE_REMOVED = "stale_removed_objects";

    /** 把一行记到一张「这一次消失的表」名下：口径记词条，其余记条数。 */
    private static void hit(ConnectorSemantic r, String foldedTable,
                            Map<String, Integer> rowsHit, Map<String, List<String>> metricsHit) {
        if (SCOPE_METRIC.equals(r.getScope())) {
            metricsHit.computeIfAbsent(foldedTable, x -> new ArrayList<>()).add(r.getTerm());
        } else {
            rowsHit.merge(foldedTable, 1, Integer::sum);
        }
    }

    /**
     * 漂移处置的回写：只写 {@code status}（和改了的 {@code detail_json}），以读到时的样子为条件。
     *
     * @return 写进去了；{@code false} = 别处刚改过这一行，本轮不覆盖
     */
    private boolean writeDrift(ConnectorSemantic asRead, String status, String detailJson) {
        LambdaUpdateWrapper<ConnectorSemantic> w = new LambdaUpdateWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getId, asRead.getId());
        unchangedSince(w, asRead);
        w.set(ConnectorSemantic::getStatus, status);
        if (detailJson != null) {
            w.set(ConnectorSemantic::getDetailJson, detailJson);
        }
        if (semanticMapper.update(null, w) > 0) {
            return true;
        }
        log.info("漂移处置回写时这一行已被别处改过（人刚确认了口径 / 采样验证刚落了结论），本轮不覆盖 "
                        + "id={} scope={} object={} term={}",
                asRead.getId(), asRead.getScope(), asRead.getObjectName(), asRead.getTerm());
        return false;
    }

    /**
     * 挂在具体表上的一行，这次该不该过期。
     *
     * @return {@code null} = 没有依据、一个字不碰（它挂的表或关系另一端没看到、
     *         或者它是 COLUMN_SET 而列集合里有表这次没描述到，而它又不是只看「表在不在」的行）；
     *         否则 {@code absent} 是它挂着的、这次 ABSENT 的表（折叠名 → 原写法），非空时必然过期
     */
    private AnchoredVerdict judgeAnchored(ConnectorSemantic r, String obj, DriftView view) {
        Map<String, String> absent = new LinkedHashMap<>();
        boolean unseen = false;
        boolean existenceOnly = existenceOnly(r);
        if (!obj.isEmpty()) {
            Presence p = view.state(obj);
            if (p == Presence.ABSENT) {
                absent.put(ciFold(obj), obj);
            } else if (p == Presence.UNKNOWN || (p == Presence.LISTED_ONLY && !existenceOnly)) {
                unseen = true;
            }
        }
        // 关系行也挂在对端表上：orders.user_id → users.id 在 users 消失时同样作废，而它的 object_name 是 orders。
        boolean join = SCOPE_JOIN.equals(r.getScope()) || ANCHOR_JOIN.equals(r.getAnchorKind());
        String to = null;
        String toCol = null;
        if (join) {
            Map<String, Object> d = readDetail(r);
            to = text(d.get("to_object"));
            toCol = text(d.get("to_column"));
            if (to != null) {
                Presence p = view.state(to);
                if (p == Presence.ABSENT) {
                    absent.putIfAbsent(ciFold(to), to);
                } else if (p != Presence.DESCRIBED) {
                    unseen = true;
                }
            }
        }
        if (!absent.isEmpty()) {
            // ABSENT 压过「没看到」：一端确定没了，另一端看没看到都不改变结论。
            return new AnchoredVerdict(true, absent, List.of());
        }
        if (unseen) {
            // ★ 只有真没看到的那一端可以让判定跳过。描述到了的那一端上列没了，与 ABSENT 一样确定。
            if (join && describedEndLostColumn(r, obj, to, toCol, view)) {
                return new AnchoredVerdict(true, Map.of(), List.of());
            }
            return null;
        }
        AnchorJudgement j = anchorJudgement(r, view);
        if (j.verdict() == AnchorVerdict.NO_BASIS) {
            // COLUMN_SET 的列集合这次读不出来、或里面有表没描述到：没有依据，与上面「没看到」同一条规矩。
            return null;
        }
        return new AnchoredVerdict(j.stale(), Map.of(), j.changedColumns());
    }

    /**
     * 只依赖「表在不在」的行：不是 FIELD / JOIN，也不锚任何列（锚点 NONE 或空）。
     * {@code COLUMN_SET} 要重算列集合，不算这一类。
     */
    private static boolean existenceOnly(ConnectorSemantic r) {
        if (SCOPE_FIELD.equals(r.getScope()) || SCOPE_JOIN.equals(r.getScope())) {
            return false;
        }
        String k = r.getAnchorKind();
        return k == null || k.isBlank() || ANCHOR_NONE.equals(k);
    }

    /** 关系的某一端这次描述到了，而它指着的那一列不在了。 */
    private static boolean describedEndLostColumn(ConnectorSemantic r, String obj, String to, String toCol,
                                                  DriftView view) {
        if (!obj.isEmpty() && view.state(obj) == Presence.DESCRIBED
                && column(view.columns(obj), r.getFieldName()) == null) {
            return true;
        }
        return to != null && toCol != null && view.state(to) == Presence.DESCRIBED
                && column(view.columns(to), toCol) == null;
    }

    /** @return 该翻成的状态；{@code null} = 不用动 */
    private static String flippedStatus(ConnectorSemantic r, boolean shouldStale) {
        if (shouldStale && !ST_STALE.equals(r.getStatus())) {
            return ST_STALE;
        }
        if (!shouldStale && ST_STALE.equals(r.getStatus())) {
            // 结构改回来了：STALE 应该能撤销，否则一次误报会永久污染这条语义。
            return SOURCE_HUMAN.equals(r.getSource()) ? ST_CONFIRMED : ST_DRAFT;
        }
        return null;
    }

    /**
     * 这一行的锚点这次判成什么。<b>三值，不是布尔。</b>
     *
     * <p>两值的时候「没有依据」只能并进「没变」，于是一条其实没人验证过的行被当成「结构照旧」——
     * 已经 STALE 的还会被 {@link #flippedStatus} 复活。第三个值就是为了让「这次不知道」说得出口。
     * 只有 COLUMN_SET 会答 {@code NO_BASIS}：FIELD / JOIN 的「没看到」在 {@link #judgeAnchored} 上游
     * 就按表拦住了，而 COLUMN_SET 的列散在好几张表上，那道门拦不住。
     */
    private AnchorJudgement anchorJudgement(ConnectorSemantic r, DriftView view) {
        if (ANCHOR_COLUMN_SET.equals(r.getAnchorKind())) {
            return columnSetVerdict(r, view);
        }
        return anchorStale(r, view) ? new AnchorJudgement(AnchorVerdict.STALE, List.of()) : AnchorJudgement.OK;
    }

    /**
     * 口径 SQL 片段引用的那几列，这次还是不是原来的样子。
     *
     * <h3>★ 为什么「没描述到」必须答 NO_BASIS，而不是 STALE</h3>
     * 快照最多描述 200 张表。口径引用的表只要有一张这次没排进去，列就没得比——
     * 此时判过期，人答的口径会在一次与结构无关的刷新里停止注入、管理台还去叫业务方重答；
     * 判没变则更糟：它会把一条真该过期的行复活。<b>没描述到就是没有依据，状态一个字都不许动。</b>
     * 表这次 ABSENT 是另一回事：表都没了，它的列当然也没了，那是确定的结论，照常按 STALE 走。
     *
     * <h3>为什么坏掉的列集合也只算 NO_BASIS</h3>
     * detail_json 解析失败、名单不是列表、元素缺字段——这些是我们自己写坏了，不是客户库改了结构。
     * 拿自己的 bug 去停用人答的口径是最难查的一种误报，所以只 warn，不动行。
     */
    private AnchorJudgement columnSetVerdict(ConnectorSemantic r, DriftView view) {
        List<AnchorColumn> cols = parseAnchorColumns(readDetail(r).get(KEY_ANCHOR_COLUMNS));
        if (cols == null || r.getAnchorHash() == null || r.getAnchorHash().isBlank()) {
            log.warn("COLUMN_SET 行的列集合读不出来（缺 {} 或缺 anchor_hash），本轮既不判过期也不复活 "
                            + "id={} scope={} term={}",
                    KEY_ANCHOR_COLUMNS, r.getId(), r.getScope(), r.getTerm());
            return AnchorJudgement.NO_BASIS;
        }
        for (AnchorColumn c : cols) {
            Presence p = view.state(c.objectName());
            if (p != Presence.DESCRIBED && p != Presence.ABSENT) {
                // 只被目录列出、或根本没看到：这次没有列可比。★ 不能把「没看」当成「没了」。
                log.debug("COLUMN_SET 行依赖的表这次没描述到（{}），本轮不判定 id={} object={} term={}",
                        p, r.getId(), c.objectName(), r.getTerm());
                return AnchorJudgement.NO_BASIS;
            }
        }
        String now = computeColumnSetAnchor(cols, view::columns);
        if (now != null && now.equals(r.getAnchorHash())) {
            return AnchorJudgement.OK;
        }
        // 到这里已经确定变了。再逐列过一遍只为了说得出「是哪几列」：
        // 列不在了一定报得出；fieldAnchor 变了要看写入侧有没有存下当时的每列指纹（存了才点得出名字）。
        List<String> changed = new ArrayList<>();
        for (AnchorColumn c : dedupSorted(cols)) {
            FieldDetail f = column(view.columns(c.objectName()), c.columnName());
            if (f == null || (c.anchor() != null && !c.anchor().equals(fieldAnchor(f)))) {
                changed.add(c.qualifiedName());
            }
        }
        return new AnchorJudgement(AnchorVerdict.STALE, List.copyOf(changed));
    }

    /**
     * COLUMN_SET 行的「是哪几列变了」名单：过期时记下、复活时清掉。
     *
     * @return 要写的新 detail_json；{@code null} = 名单没变化（或序列化失败），detail 不动
     */
    private String staleColumnsDetail(ConnectorSemantic r, List<String> changed) {
        if (!ANCHOR_COLUMN_SET.equals(r.getAnchorKind())) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>(readDetail(r));
        if (stringList(d.get(KEY_STALE_COLUMNS)).equals(changed)) {
            return null;
        }
        if (changed.isEmpty()) {
            d.remove(KEY_STALE_COLUMNS);
        } else {
            d.put(KEY_STALE_COLUMNS, changed);
        }
        // 序列化失败返回 null：名单没记下来，但状态该翻还是要翻——名单是取证材料，不是判定依据。
        return toJson(d);
    }

    /**
     * 从 detail_json 读出 COLUMN_SET 的列集合。
     *
     * @return {@code null} = 读不出来（不是列表、空列表、元素不是对象或缺字段）。
     *         <b>调用方必须按「没有依据」处理，不许当成「结构变了」。</b>
     */
    private static List<AnchorColumn> parseAnchorColumns(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<AnchorColumn> out = new ArrayList<>();
        for (Object e : list) {
            if (!(e instanceof Map<?, ?> m)) {
                return null;
            }
            AnchorColumn c = new AnchorColumn(text(m.get(AnchorColumn.F_OBJECT)), text(m.get(AnchorColumn.F_COLUMN)),
                    text(m.get(AnchorColumn.F_ANCHOR)));
            if (!c.valid()) {
                return null;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * 锚点算不出来（列没了）也算失效；算出来但对不上同样失效。
     *
     * <p><b>只回答两值，所以只能给「没看到」已经在上游拦掉的锚点用</b>（FIELD / JOIN：它们的表没描述到时，
     * {@link #judgeAnchored} 根本走不到这里）。COLUMN_SET 必须走 {@link #columnSetVerdict} 的三值判定——
     * 它的列散在好几张表上，上游那道「它挂的表在不在」的门拦不住「其中一张表这次没描述到」。
     */
    private boolean anchorStale(ConnectorSemantic r, DriftView view) {
        if (ANCHOR_NONE.equals(r.getAnchorKind()) || r.getAnchorKind() == null) {
            return false;
        }
        String now = recomputeAnchor(r, view);
        return now == null || !now.equals(r.getAnchorHash());
    }

    /**
     * 按本次描述到的列重算锚点。表名、列名都先按原样找、找不到再按唯一键的排序规则折叠着找：
     * 处境判定是大小写不敏感的，这里若大小写敏感，一张只改了大小写的表会被判成 DESCRIBED、锚点却「算不出来」，
     * 挂在它上面的字段与关系全部假过期。
     */
    private String recomputeAnchor(ConnectorSemantic r, DriftView view) {
        if (ANCHOR_FIELD.equals(r.getAnchorKind())) {
            FieldDetail left = leftColumn(r, view);
            return left == null ? null : fieldAnchor(left);
        }
        if (ANCHOR_JOIN.equals(r.getAnchorKind())) {
            FieldDetail left = leftColumn(r, view);
            if (left == null) return null;
            Map<String, Object> d = readDetail(r);
            String toObj = text(d.get("to_object"));
            String toCol = text(d.get("to_column"));
            if (toObj == null || toCol == null) return null;
            FieldDetail right = column(view.columns(toObj), toCol);
            if (right == null) return null;
            return joinAnchor(left, right);
        }
        if (ANCHOR_COLUMN_SET.equals(r.getAnchorKind())) {
            // 列集合读不出来时得到 null（= 算不出来），与「有列不在了」同一个返回值；
            // 两者的区别由 columnSetVerdict 分开，不在这里分。
            return computeColumnSetAnchor(parseAnchorColumns(readDetail(r).get(KEY_ANCHOR_COLUMNS)), view::columns);
        }
        return r.getAnchorHash();
    }

    /**
     * 这一行自己那一列（{@code object_name.field_name}）。
     *
     * <p><b>★ 这两行刻意留在 FIELD / JOIN 各自的分支里，不准上提到方法开头。</b>
     * METRIC / CAVEAT 行的 object_name 与 field_name 都是空串，取左列必然是 null；
     * 上提之后 COLUMN_SET 的口径行一挂上就当场被判「锚点算不出来」= 过期——
     * 明明是判定代码取错了列，看起来却像客户库真的改了结构。
     */
    private static FieldDetail leftColumn(ConnectorSemantic r, DriftView view) {
        Map<String, FieldDetail> cols = view.columns(r.getObjectName());
        if (cols == null) return null;
        return column(cols, r.getFieldName());
    }

    private static FieldDetail column(Map<String, FieldDetail> cols, String name) {
        if (cols == null || name == null) {
            return null;
        }
        FieldDetail f = cols.get(name);
        if (f != null) {
            return f;
        }
        String k = ciFold(name);
        for (Map.Entry<String, FieldDetail> e : cols.entrySet()) {
            if (e.getKey() != null && ciFold(e.getKey()).equals(k)) {
                return e.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> readDetail(ConnectorSemantic r) {
        return readDetail(r.getDetailJson(), r.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(String json, Long id) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            log.debug("解析语义 detail 失败 id={}", id, e);
            return Map.of();
        }
    }

    /** 与 {@link #readDetail(String, Long)} 的差别只有一处：解析失败返回 {@code null}，让调用方分得清「空」和「坏了」。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseDetailOrNull(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ 锚点指纹

    /**
     * 一列自己的指纹。与 {@code ConnectorSchemaService.structureFingerprint} 里每列那一段
     * <b>刻意同形</b>（name:type:nullable:comment），这样两边对"什么算结构变了"的判断一致。
     */
    public static String fieldAnchor(FieldDetail f) {
        return sha256(f.name() + ":" + f.type() + ":" + f.nullable() + ":" + nullToEmpty(f.comment()));
    }

    /** 关联的指纹：两端列任一变化都让这条关系不可信。 */
    public static String joinAnchor(FieldDetail left, FieldDetail right) {
        return sha256(fieldAnchor(left) + ">" + fieldAnchor(right));
    }

    /**
     * 一个列集合的指纹：口径的 SQL 片段引用到的那几列，任何一列改了名 / 类型 / 可空 / 注释都让这条口径不可信。
     *
     * <h4>为什么口径必须锚在列集合上</h4>
     * 列被删掉还算吵闹（查询直接报错）；<b>列被复用成别的含义才是要命的那一种</b>——
     * SQL 照跑、行数照出，数字静默地错，而模型会一直照着这条口径写下去。所以口径也要有能失效的锚。
     *
     * <h4>★ 必须排序，而且按折叠后的名字排</h4>
     * 列集合来自 detail_json，它的顺序是写入那一刻的书写顺序，重新确认一次顺序就可能不同。
     * 不排序 = 同一组列算出两个 hash = <b>每次刷新都判假过期</b>，人答的口径被反复停用。
     * 按折叠形态（{@link #ciFold}）排、也按折叠形态拼，则是因为处境判定与列查找本来就是大小写不敏感的：
     * 只改了大小写的表名 / 列名必须算同一列，不能既判 DESCRIBED 又算出另一个 hash。
     *
     * @param fieldsByObject 本次描述到的每张表的字段（表名 → 列名 → FieldDetail），表名按折叠形态兜底匹配
     * @return {@code null} = 集合为空、或有列这次找不到。调用方要分得清「算不出来」和「算出来但变了」
     */
    public static String columnSetAnchor(Collection<AnchorColumn> columns,
                                         Map<String, Map<String, FieldDetail>> fieldsByObject) {
        return computeColumnSetAnchor(columns, o -> objectColumns(fieldsByObject, o));
    }

    /** {@link #columnSetAnchor} 的内部形状：漂移处置手上是 {@code DriftView}，不是一张 map。 */
    private static String computeColumnSetAnchor(Collection<AnchorColumn> columns,
                                                 Function<String, Map<String, FieldDetail>> columnsOf) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (AnchorColumn c : dedupSorted(columns)) {
            FieldDetail f = column(columnsOf.apply(c.objectName()), c.columnName());
            if (f == null) {
                return null;
            }
            b.append(c.foldedKey()).append('=').append(fieldAnchor(f)).append(',');
        }
        return sha256(b.toString());
    }

    /** 按折叠名去重并排序。重复的列只算一次：同一列写两遍不该算出另一个 hash。 */
    private static List<AnchorColumn> dedupSorted(Collection<AnchorColumn> columns) {
        Map<String, AnchorColumn> byKey = new LinkedHashMap<>();
        for (AnchorColumn c : columns) {
            if (c != null && c.valid()) {
                byKey.putIfAbsent(c.foldedKey(), c);
            }
        }
        List<AnchorColumn> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparing(AnchorColumn::foldedKey));
        return out;
    }

    /** 表名先按原样找、找不到再按折叠形态找。理由同 {@link #recomputeAnchor}：处境判定是大小写不敏感的。 */
    private static Map<String, FieldDetail> objectColumns(Map<String, Map<String, FieldDetail>> src, String object) {
        if (src == null || object == null) {
            return null;
        }
        Map<String, FieldDetail> c = src.get(object);
        if (c != null) {
            return c;
        }
        String k = ciFold(object);
        for (Map.Entry<String, Map<String, FieldDetail>> e : src.entrySet()) {
            if (e.getKey() != null && ciFold(e.getKey()).equals(k)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ================================================================ 结构指纹（按表）

    /**
     * 一张表的结构指纹：表名 + 按快照列顺序的每列锚点指纹。64 位十六进制 sha256，不截短。
     *
     * <h4>★ 为什么由 {@link #fieldAnchor} 拼，而不是直接用 {@code connector_schema.content_hash}</h4>
     * {@code content_hash} 在落库前由 {@code ObjectDetail} 算出，含对象类型、拼的是原始列文本；
     * 锚点则由推导类 {@code parseFields} 从 {@code detail_json} 解析回来的 {@link FieldDetail} 计算
     * （{@code nullable} 缺失按可空）。指纹必须与锚点<b>同源</b>，才能保证
     * 「指纹没变 ⇒ 这张表上 FIELD 行写入时算的锚点在新快照上照样成立」——两边各算各的，
     * 就会出现指纹说没变、锚点却对不上（或反过来）的静默分叉。
     *
     * <h4>为什么按表，不再只有整份快照一个值</h4>
     * 语义层生成 agent 逐表提交、按表对账、续跑时按表判「已覆盖」。整份快照一个指纹时，
     * 任何一张无关表被刷新都会作废整轮已生成的表；按表比，只有本表结构真的变了才重来。
     *
     * <p>列顺序算进指纹（按 {@code cols} 的迭代顺序，即快照里的列顺序）：{@code SELECT *} 与按位置的写法依赖它。
     *
     * @param cols 取自 {@code parseFields}，不为 null；没有列的表是空 map
     */
    public static String tableStamp(String objectName, Map<String, FieldDetail> cols) {
        StringBuilder b = new StringBuilder(objectName).append('|');
        for (FieldDetail f : cols.values()) {
            b.append(fieldAnchor(f)).append(',');
        }
        return sha256(b.toString());
    }

    /** 表名 → {@link #tableStamp}。保持入参的迭代顺序。 */
    public static Map<String, String> tableStamps(Map<String, Map<String, FieldDetail>> fieldsByObject) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, FieldDetail>> e : fieldsByObject.entrySet()) {
            out.put(e.getKey(), tableStamp(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * 整份快照的结构指纹，由按表指纹拼成：表名排序后逐表 {@code name=tableStamp;}，再 sha256。
     *
     * <p>增量补写（{@code ConnectorSemanticDeriveService.deriveAdded}）用它判断「推导期间结构变没变」。
     * <b>不含 synced_at</b>——一次什么都没变的刷新不该让一批增量作废。
     * 它从不落库、只在一次推导内做内存比较，所以从旧算法（逐表直接拼列锚点）换成由按表指纹拼成，数值变化没有兼容性问题。
     */
    public static String structureStamp(Map<String, Map<String, FieldDetail>> fieldsByObject) {
        // 排序：parseFields 按快照行的顺序给表，而调用方拿到的快照可能是字母序也可能是重要性序，指纹不能跟着变。
        List<String> names = fieldsByObject.keySet().stream().sorted().toList();
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            b.append(n).append('=').append(tableStamp(n, fieldsByObject.get(n))).append(';');
        }
        return sha256(b.toString());
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ================================================================ 杂项

    /**
     * 写回条件：这一行<b>仍是读到时的样子</b>。
     *
     * <h4>为什么不只比 {@code update_time}</h4>
     * 它是秒级精度的 {@code datetime}；更要紧的是 {@code updateById(整行)} 这种写法会把读到的旧 {@code update_time}
     * <b>原样写回去</b>（自动填充只在字段为空时才填），MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 遇到显式赋值也不会生效——
     * 只比它，别处的整行写回在这里看起来「没人动过」。所以把写者会改的内容列一起比上：
     * 状态、来源、采样结论、锚点、gloss、detail、留痕。人每确认一次口径，留痕必然多一条；推导侧每落一次结论，detail 必然变。
     *
     * <p>可空列按「读到是 null 就要求仍是 null」比，不能用 {@code = NULL}（永远不成立，那等于从此写不进去）。
     */
    private static void unchangedSince(LambdaUpdateWrapper<ConnectorSemantic> w, ConnectorSemantic asRead) {
        sameAs(w, ConnectorSemantic::getUpdateTime, asRead.getUpdateTime());
        sameAs(w, ConnectorSemantic::getStatus, asRead.getStatus());
        sameAs(w, ConnectorSemantic::getSource, asRead.getSource());
        sameAs(w, ConnectorSemantic::getVerified, asRead.getVerified());
        sameAs(w, ConnectorSemantic::getAnchorHash, asRead.getAnchorHash());
        sameAs(w, ConnectorSemantic::getGloss, asRead.getGloss());
        sameAs(w, ConnectorSemantic::getDetailJson, asRead.getDetailJson());
        sameAs(w, ConnectorSemantic::getHistoryJson, asRead.getHistoryJson());
    }

    private static void sameAs(LambdaUpdateWrapper<ConnectorSemantic> w, SFunction<ConnectorSemantic, ?> column,
                               Object value) {
        if (value == null) {
            w.isNull(column);
        } else {
            w.eq(column, value);
        }
    }

    /**
     * 按 {@code uk_connector_semantic} 的排序规则（utf8mb4_unicode_ci）折叠：大小写与重音不敏感、尾随空格不计。
     * 是近似，不是逐字符复刻——覆盖不到的字符落到库里撞键，由 {@link #upsertInferred} 单条接住。
     *
     * <p>{@code public}：语义层生成的一致性规则（{@code generation.consistency}）按同一个口径判「同一左列」，行为不变。
     */
    public static String ciFold(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).stripTrailing();
    }

    /** 唯一键（租户与连接之外的四列）的折叠形态。分隔符用不可打印字符，理由同推导侧的 KEY_SEP。 */
    public static String uniqueKey(ConnectorSemantic r) {
        return ciFold(r.getScope()) + '' + ciFold(r.getObjectName()) + ''
                + ciFold(r.getFieldName()) + '' + ciFold(r.getTerm());
    }

    private static List<String> stringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e != null && !String.valueOf(e).isBlank()) {
                out.add(String.valueOf(e).trim());
            }
        }
        return out;
    }

    /**
     * 确认连接在当前真实租户上下文中可见并返回连接行。
     *
     * <p>只有在真实 {@code TenantContext} 下，“查得到”才等于“属于当前租户”；后台系统模式调用者不得把它当越权校验。
     */
    public Connection requireOwned(Long connectorId) {
        Connection c = connectionMapper.selectById(connectorId);
        if (c == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return c;
    }

    private static String toJson(Object o) {
        if (o == null) return null;
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化语义 detail 失败", e);
            return null;
        }
    }

    /**
     * 删取值这条路上的序列化：失败就抛。{@link #toJson} 失败返回 null 的约定在这里不能用——
     * 调用方拿到 null 只能「这一列不动」，而不动就是让真实取值留在库里。
     */
    private static String requireJson(Object o) {
        String json = toJson(o);
        if (json == null) {
            throw new IllegalStateException("删除真实取值后的 detail 序列化失败");
        }
        return json;
    }

    /** 注入 catalog 时用的短版本。 */
    public static String shortGloss(String gloss) {
        if (gloss == null) return null;
        String g = gloss.trim();
        return g.length() <= CATALOG_GLOSS_MAX ? g : g.substring(0, CATALOG_GLOSS_MAX) + "…";
    }

    // ================================================================ 返回形状

    @Data
    @Builder
    public static class CatalogSemantics {
        /** 表名 → OBJECT 行 */
        private Map<String, ConnectorSemantic> objects;
        /** METRIC 行。口径必须在"选表之前"被看见——扣不扣退款决定了要不要 join 退款表。 */
        private List<ConnectorSemantic> glossary;
    }

    @Data
    @Builder
    public static class ObjectSemantics {
        /** 列名 → FIELD 行 */
        private Map<String, ConnectorSemantic> fields;
        /** 从这张表出发的 JOIN 行 */
        private List<ConnectorSemantic> joins;
    }

    /**
     * @param removedImpact   这一次消失的每张表（调用方报的、且确实 ABSENT）<b>本轮新归到它名下</b>的语义。
     *                        只列真有东西的表；空列表 = 本轮没有任何语义因表消失而新失效
     * @param concurrentSkips 回写时发现已被别处改过、本轮没有覆盖的行数。非 0 时调用方不该把待补报名单当成已处置完
     */
    public record StaleResult(int staled, int revived, List<ObjectStale> removedImpact, int concurrentSkips) {
        public StaleResult(int staled, int revived) {
            this(staled, revived, List.of(), 0);
        }

        public StaleResult(int staled, int revived, List<ObjectStale> removedImpact) {
            this(staled, revived, removedImpact, 0);
        }
    }

    /**
     * 一张消失的表的影响面。<b>三个数都只算本轮新归到这张表名下的</b>（契约 K-4），与本轮的 {@code staled} 同一个口径。
     *
     * @param staledRows    挂在它上面、<b>本轮</b>新标成 STALE 的非口径行（表用途、字段、关系、告诫）
     * @param staledMetrics 这张表<b>本轮</b>才新进过期名单的口径（METRIC）条数
     * @param metricTerms   那些口径的词条
     */
    public record ObjectStale(String objectName, int staledRows, int staledMetrics, List<String> metricTerms) {
    }

    /**
     * @param skippedNotInferred 撞上 HUMAN / IMPORTED 行而没动的条数
     * @param keptExisting       撞上别的表上已有的 INFERRED 行而保持原样的条数
     * @param conflicts          折叠规则没覆盖到、落库时才撞键的条数
     * @param concurrentSkips    回写时发现那一行已被别处改过（人答成 HUMAN / 采样验证刚落结论）而没覆盖的条数
     */
    public record UpsertResult(int inserted, int updated, int skippedNotInferred, int keptExisting, int conflicts,
                               int concurrentSkips) {
        public UpsertResult(int inserted, int updated, int skippedNotInferred, int keptExisting, int conflicts) {
            this(inserted, updated, skippedNotInferred, keptExisting, conflicts, 0);
        }
    }

    /**
     * 一个对象名在本次刷新里的处境。<b>只有 {@link #ABSENT} 算「表没了」。</b>
     * 为什么要分四种而不是「在不在快照里」两种，见 {@link #applyDrift(Long, Map, SnapshotPresence, List)}。
     */
    public enum Presence {
        /** 在本次描述到的那批里：有列，锚点可以重算。 */
        DESCRIBED,
        /** 目录列出来了（或单独确认过存在）、但没描述：表还在，只是这次没有列可比。 */
        LISTED_ONLY,
        /** 目录没列、而目录本身完整（或单独确认过不存在）：真没了。 */
        ABSENT,
        /** 目录没列、目录本身被截断、也没能单独确认：不知道。 */
        UNKNOWN;

        /** 确定表还在（描述到或列出 / 确认存在）。只依赖「表在不在」的判断用它。 */
        public boolean exists() {
            return this == DESCRIBED || this == LISTED_ONLY;
        }
    }

    /**
     * 本次刷新「看到了什么」：描述到的名字、目录列出的名字、目录本身截没截断，以及目录截断时单独确认过的名字。
     * 名字一律按唯一键的排序规则折叠比较。
     *
     * <p>不是 record：折叠后的集合不该作为组件暴露出去——拿到它的人会拿原写法去 {@code contains}，大小写一变就判错。
     * 只在服务之间传，从不序列化。
     */
    public static final class SnapshotPresence {
        private final Set<String> described;
        private final Set<String> listed;
        private final Set<String> confirmedAbsent;
        private final boolean listingTruncated;

        private SnapshotPresence(Set<String> described, Set<String> listed, Set<String> confirmedAbsent,
                                 boolean listingTruncated) {
            this.described = described;
            this.listed = listed;
            this.confirmedAbsent = confirmedAbsent;
            this.listingTruncated = listingTruncated;
        }

        /**
         * @param describedNames   本次描述到（有结构快照行）的对象名
         * @param listedNames      本次目录列出的对象名。描述到的一定算列出了，不必重复传
         * @param listingTruncated 目录本身是否被截断（没列出来的名字只能是 UNKNOWN，不能是 ABSENT）
         */
        public static SnapshotPresence of(Collection<String> describedNames, Collection<String> listedNames,
                                          boolean listingTruncated) {
            return of(describedNames, listedNames, listingTruncated, null, null);
        }

        /**
         * 带上目录截断时的单独确认（契约 K-1 {@code DescribeCapable.existingObjects}）。
         *
         * @param checkedNames  单独问过「存不存在」的名字；{@code null} / 空 = 没问
         * @param existingNames 连接器答「存在」的那些。<b>{@code null} = 连接器答不了</b>：问过的名字仍然是 UNKNOWN，
         *                      绝不能读成「都不存在」——那会让一次刷新把一批好好的表判成消失
         */
        public static SnapshotPresence of(Collection<String> describedNames, Collection<String> listedNames,
                                          boolean listingTruncated, Collection<String> checkedNames,
                                          Collection<String> existingNames) {
            Set<String> d = foldAll(describedNames);
            Set<String> l = foldAll(listedNames);
            l.addAll(d);
            Set<String> absent = new HashSet<>();
            if (checkedNames != null && existingNames != null) {
                Set<String> exists = foldAll(existingNames);
                for (String k : foldAll(checkedNames)) {
                    if (l.contains(k)) {
                        continue;
                    }
                    if (exists.contains(k)) {
                        l.add(k);
                    } else {
                        absent.add(k);
                    }
                }
            }
            return new SnapshotPresence(Set.copyOf(d), Set.copyOf(l), Set.copyOf(absent), listingTruncated);
        }

        /** 只知道描述到的那些，并当目录完整：描述之外的名字一律 ABSENT。旧调用形状用。 */
        public static SnapshotPresence describedOnly(Collection<String> names) {
            return of(names, names, false);
        }

        /** 空名字答 UNKNOWN：一个没有名字的东西不该让任何行过期。 */
        public Presence stateOf(String name) {
            if (name == null || name.isBlank()) {
                return Presence.UNKNOWN;
            }
            String k = ciFold(name);
            if (described.contains(k)) {
                return Presence.DESCRIBED;
            }
            if (listed.contains(k)) {
                return Presence.LISTED_ONLY;
            }
            if (!listingTruncated || confirmedAbsent.contains(k)) {
                return Presence.ABSENT;
            }
            return Presence.UNKNOWN;
        }

        public boolean listingTruncated() {
            return listingTruncated;
        }

        private static Set<String> foldAll(Collection<String> names) {
            Set<String> out = new HashSet<>();
            if (names != null) {
                for (String n : names) {
                    if (n != null && !n.isBlank()) {
                        out.add(ciFold(n));
                    }
                }
            }
            return out;
        }

        @Override
        public String toString() {
            return "SnapshotPresence{described=" + described.size() + ", listed=" + listed.size()
                    + ", confirmedAbsent=" + confirmedAbsent.size() + ", listingTruncated=" + listingTruncated + "}";
        }
    }

    /**
     * 漂移处置手上的「这次看到了什么」：描述到的列（原名、折叠名两套索引）+ 目录的处境。
     * <b>手上真有列的表永远算 DESCRIBED</b>，不管 presence 怎么说——两份输入对不上时以真有的列为准。
     */
    private static final class DriftView {
        private final Map<String, Map<String, FieldDetail>> exact;
        private final Map<String, Map<String, FieldDetail>> folded = new HashMap<>();
        private final SnapshotPresence presence;

        DriftView(Map<String, Map<String, FieldDetail>> described, SnapshotPresence presence) {
            this.exact = described;
            for (Map.Entry<String, Map<String, FieldDetail>> e : described.entrySet()) {
                if (e.getKey() != null) {
                    folded.putIfAbsent(ciFold(e.getKey()), e.getValue());
                }
            }
            this.presence = presence != null ? presence : SnapshotPresence.describedOnly(described.keySet());
        }

        Presence state(String name) {
            if (name == null || name.isBlank()) {
                return Presence.UNKNOWN;
            }
            if (columns(name) != null) {
                return Presence.DESCRIBED;
            }
            return presence.stateOf(name);
        }

        Map<String, FieldDetail> columns(String object) {
            if (object == null) {
                return null;
            }
            Map<String, FieldDetail> c = exact.get(object);
            return c != null ? c : folded.get(ciFold(object));
        }
    }

    /**
     * 挂在具体表上的一行的判定。
     *
     * @param absent         它挂着的、这次 ABSENT 的表（折叠名 → 原写法）
     * @param changedColumns COLUMN_SET 判过期时是哪几列变了（原写法，给人看）；其余锚点一律空
     */
    private record AnchoredVerdict(boolean stale, Map<String, String> absent, List<String> changedColumns) {
    }

    /**
     * 锚点这一次的判定。<b>{@code NO_BASIS} 与 {@code OK} 必须分开</b>：
     * 把「没依据」并进「没变」，一条早该过期的行会被当成「结构改回来了」复活。
     */
    public enum AnchorVerdict {
        /** 重算出来与存着的锚点一致：结构没变。 */
        OK,
        /** 重算出来对不上（或列已经不在了）：结构变了，这一行不能再注入。 */
        STALE,
        /** 这次没有下结论的材料（依赖的表没描述到、列集合坏了）：状态一个字都不许动。 */
        NO_BASIS
    }

    /** 一次锚点判定的结果。{@code changedColumns}：COLUMN_SET 判 STALE 时是哪几列变了（原写法，给人看）。 */
    private record AnchorJudgement(AnchorVerdict verdict, List<String> changedColumns) {
        static final AnchorJudgement OK = new AnchorJudgement(AnchorVerdict.OK, List.of());
        static final AnchorJudgement NO_BASIS = new AnchorJudgement(AnchorVerdict.NO_BASIS, List.of());

        boolean stale() {
            return verdict == AnchorVerdict.STALE;
        }
    }

    /**
     * 锚点依赖的一列：表名 + 列名（+ 写入那一刻这一列的 {@link #fieldAnchor}，可为空）。
     *
     * <p>原写法一律保留：写回「是哪几列变了」和日志都要给人看。
     * <b>比较、去重、排序一律按 {@link #foldedKey}</b>，与唯一键、处境判定同一个口径。
     *
     * @param anchor 写入那一刻这一列的指纹。<b>只用来说得出「是哪一列变了」，不参与判定</b>——
     *               判定看的是整个集合的 hash（它就是行上的 {@code anchor_hash}）。为空时名单只报得出没了的列。
     */
    public record AnchorColumn(String objectName, String columnName, String anchor) {

        static final String F_OBJECT = "object";
        static final String F_COLUMN = "column";
        static final String F_ANCHOR = "anchor";

        public AnchorColumn {
            objectName = objectName == null ? "" : objectName.trim();
            columnName = columnName == null ? "" : columnName.trim();
        }

        public AnchorColumn(String objectName, String columnName) {
            this(objectName, columnName, null);
        }

        /** 折叠后的 {@code obj.col}：排序、去重、拼指纹都用它。 */
        public String foldedKey() {
            return ciFold(objectName) + '.' + ciFold(columnName);
        }

        /** 给人看的 {@code obj.col}（原写法）。 */
        public String qualifiedName() {
            return objectName + "." + columnName;
        }

        /** 表名、列名都得有。缺一个就没法找回这一列，整份名单按「读不出来」处理。 */
        public boolean valid() {
            return !objectName.isEmpty() && !columnName.isEmpty();
        }

        /** 写进 detail_json 的形状。<b>写入侧用这一个，别在别处各拼各的键名。</b> */
        public Map<String, Object> toDetailMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(F_OBJECT, objectName);
            m.put(F_COLUMN, columnName);
            if (anchor != null && !anchor.isBlank()) {
                m.put(F_ANCHOR, anchor);
            }
            return m;
        }
    }

    /** 删取值时一行的改法。字段为 {@code null} = 那一列不动。 */
    private record Purge(String detailJson, String gloss) {
    }

    /** 一次原地更新最终写下去的内容。{@code preserved} 只给日志与测试用：留住了什么。 */
    record Merged(String gloss, String detailJson, String evidence, Integer confidence,
                  String anchorKind, String anchorHash, String verified, String status,
                  List<String> preserved) {
    }
}
