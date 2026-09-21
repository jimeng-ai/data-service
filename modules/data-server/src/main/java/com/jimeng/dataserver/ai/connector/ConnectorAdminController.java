package com.jimeng.dataserver.ai.connector;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.generation.ConnectorSemanticGenerationService;
import com.jimeng.dataserver.ai.connector.generation.GenerationAck;
import com.jimeng.dataserver.ai.connector.generation.GenerationTrigger;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditQuery;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditView;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticView;
import com.jimeng.dataserver.ai.connector.service.GrantScriptRequestDto;
import com.jimeng.dataserver.ai.connector.service.GrantScriptService;
import com.jimeng.dataserver.ai.connector.service.PendingWriteQuery;
import com.jimeng.dataserver.ai.connector.service.PendingWriteRejectDto;
import com.jimeng.dataserver.ai.connector.service.PendingWriteService;
import com.jimeng.dataserver.ai.connector.service.ConnectorService;
import com.jimeng.dataserver.ai.connector.service.ConnectorUpsert;
import com.jimeng.dataserver.ai.connector.service.ProbeOutcome;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaView;
import com.jimeng.dataserver.ai.connector.service.PendingWriteView;
import com.jimeng.dataserver.ai.connector.spi.GrantScript;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 连接器实例管理（类型感知）。
 *
 * <h3>两个入口，一张表</h3>
 * 旧的 {@code /data/admin/connections} <b>原样保留</b>——它服务沙箱 egress 那条链路，
 * 现有前端页和存量数据都还在用，写出来的行 {@code kind} 默认是 {@code HTTP}。
 * 本控制器是类型感知的新入口，覆盖全部 kind，两者读写<b>同一张 {@code connection} 表</b>。
 * 这是「演化 connection 而不是新建平行表」这个决策的落点：两个 API，一张表，<b>一套横切</b>
 * （租户隔离、凭据加解密、Agent 授权、审计都只有一份实现）。
 *
 * <h3>全部限企业超管</h3>
 * 理由与 {@code ConnectionAdminController} 完全一致，原样延续：一条连接 = 一份可用凭据 +
 * 一条访问许可，谁能建连接谁就能让任意被授权的 Agent 以该身份访问客户的生产系统。
 * <b>在连接器进入 RBAC 资源体系之前，权限上的默认值取最严的那个——收紧容易，放开难。</b>
 */
@Slf4j
@Tag(name = "连接器", description = "客户系统接入：数据库 / HTTP 接口等外部资源的注册与探测")
@RestController
@RequestMapping("/data/admin/connectors")
@RequiredArgsConstructor
public class ConnectorAdminController {

    private final ConnectorService connectorService;
    private final ConnectorAuditService connectorAuditService;
    private final ConnectorSchemaService connectorSchemaService;
    private final ConnectorSemanticService connectorSemanticService;
    private final ConnectorSemanticDeriveService connectorSemanticDeriveService;
    private final ConnectorSemanticGenerationService semanticGenerationService;
    private final PendingWriteService pendingWriteService;
    private final GrantScriptService grantScriptService;
    private final SuperAdminGuard superAdminGuard;

    /**
     * 所有连接器类型及其<b>表单 schema</b>。
     *
     * <p>前端据此渲染新建/编辑表单，不硬编码任何字段名。
     * <b>这是「新增一种连接器类型，前端零改动」的兑现点</b>——加 PostgreSQL 时，
     * 后端多一个实现类，这个接口自动多返回一项，前端一行都不用改。
     */
    @Operation(summary = "连接器类型清单（含表单 schema）")
    @GetMapping("/kinds")
    public List<Map<String, Object>> kinds() {
        superAdminGuard.requireSuperAdmin();
        return connectorService.kinds();
    }

    @Operation(summary = "连接器列表（不含凭据）")
    @GetMapping
    public List<ConnectorView> list() {
        superAdminGuard.requireSuperAdmin();
        return connectorService.list();
    }

    @Operation(summary = "连接器详情（不含凭据）")
    @GetMapping("/{id}")
    public ConnectorView get(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectorService.get(id);
    }

    /**
     * 取回凭据明文（管理台的「查看密码」）。
     *
     * <h3>为什么是 POST 而不是 GET</h3>
     * 它读起来像个读接口，但两点决定了它不是：
     * <ul>
     *   <li><b>每次调用都写一条审计</b>。一个有副作用的端点不该是 GET——将来任何一处
     *       预取、重试、或把 GET 当幂等的中间件，都会凭空制造出「有人查看了密码」的记录，
     *       而这张表的全部价值就在于它记的每一条都真的发生过。</li>
     *   <li>GET 的响应可能被中间层缓存。响应体里装的是客户生产库的口令。</li>
     * </ul>
     *
     * <h3>它和上面那个 {@code get} 的关系，别搞混</h3>
     * {@code get} 刻意不返回凭据，<b>连占位串都不给</b>。本接口是那条纪律唯一的豁免口，
     * 所以它是<b>单独一次请求</b>：编辑弹窗打开时走 {@code get}，明文只在用户点「查看」那一刻
     * 才从这里取。<b>把明文并进 {@code get} 的返回值是这个功能最容易做错的一步</b>——
     * 那样一来"藏起来"只发生在界面上，而泄露发生在 HTTP 上。
     *
     * <p>权限与编辑同级（企业超管）。不额外要求二次验证是一个<b>当前</b>的取舍：这个身份本来就能
     * 直接改掉这条连接的凭据。但两者的代价并不对等——改凭据是可发现、可撤销的，
     * 取走凭据是不可发现的。所以如果以后要收紧，收紧点在这里，不在别处。
     */
    @Operation(summary = "取回凭据明文（企业超管；每次调用都会写一条审计记录）")
    @PostMapping("/{id}/credential")
    public Map<String, String> credential(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectorService.revealCredential(id);
    }

    /**
     * 试连：按表单里的参数实际连一次，<b>不落库</b>。
     *
     * <p>「创建并验证」本来就会先探测、探不过就拒绝落库，所以配错的连接进不了库。
     * 但那要求你<b>先提交才知道对不对</b>，改一版就得再提交一版。这个接口把验证从提交里拆出来，
     * 让人能在表单上反复调到通为止。
     *
     * <p>跑的是同一套三步探测，所以这里过了创建就一定过——不存在「试连说行、创建又说不行」的分叉。
     *
     * <p>探测失败<b>不返回错误状态</b>：失败原因本来就是这个接口要回答的东西，
     * 抛异常会让前端拿不到「探到了哪些能力」「只读判定是哪一种」这些同样有用的信息。
     *
     * @param id 编辑态传原连接 id，用于「敏感参数留空 = 沿用原值」；新建不传
     */
    @Operation(summary = "试连（不落库；编辑态传 id 以沿用原凭据）")
    @PostMapping("/probe")
    public ProbeOutcome probe(@RequestBody ConnectorUpsert req,
                              @RequestParam(value = "id", required = false) Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectorService.dryRun(req, id);
    }

    /**
     * 新建。<b>建完就地派发语义层推导——这是「接入即触发」的落点。</b>
     *
     * <p>派发放在这里而不是 {@code ConnectorService.create()} 里面，理由写在那个方法的 javadoc 上，
     * 一句话：推导必须在<b>事务提交之后</b>发生，而这里是唯一一个「提交已经完成、且不在任何事务里」
     * 的位置（{@code create} 的 {@code @Transactional} 代理在返回给本方法之前就已经提交了）。
     *
     * <h3>★ 生成不是零成本，且有两条路径</h3>
     * 两条路径都会在快照为空时先向客户库拉结构（1 次 catalog + 最多 200 次 describe）。agent 路径把表
     * 分片后做多次短模型调用，结果逐表提交，<b>不会</b>自动派发 S1 SQL 语料、S3 关系探查或 S4 取值采样；
     * 前置条件不满足时才回落到既有单次推导，后者仍会读 S1，并在成功后自动派发 S3/S4。因此只有回落路径
     * 才可能继续按节流配置对客户库做最长约半小时的采样验证。
     *
     * <p><b>建连接口不等生成结果。</b>agent 路径只落库排队并唤醒全局串行排空器；单次路径提交到既有线程池。
     *
     * <p><b>返回体里的 {@code semanticStatus} 恒为 {@code NONE}，这不代表推导没跑。</b>
     * 视图在 {@code create} 里就构造完了（那时行上这一列还是 NULL），派发之后没有人再刷它一次。
     * 前端不能拿这次响应判断语义层结果，只能轮询连接详情。
     *
     * <p>派发失败不影响本次新建：连接已经落库并探测通过，它<b>现在就是可用的</b>。
     * 语义层是叠加上去的注解，用一个可选增强的故障去否决一个已经成功的操作是错的。
     */
    @Operation(summary = "新建连接器（保存前会实际探测：连通性 + 只读校验 + 能力，探不过则拒绝保存）")
    @PostMapping
    public ConnectorView create(@RequestBody ConnectorUpsert req) {
        superAdminGuard.requireSuperAdmin();
        ConnectorView created = connectorService.create(req);
        dispatchSemanticDerive(created);
        return created;
    }

    /**
     * 接入时零人工：没有表单、没有勾选清单、没有确认页，说明书是背着人生成的。
     *
     * <p>三层保护，缺一不可：
     * <ul>
     *   <li><b>只给落了库的真实 id。</b>试连（{@code /probe}）用的是递减的<b>负数</b>合成 id 且从不落库
     *       （见 {@code ConnectorService.dryRun}）。它走的是另一个端点、返回的也不是 {@code ConnectorView}，
     *       今天到不了这里；但这个前提是别人代码里的，不值得依赖，所以在派发口上再挡一道。</li>
     *   <li><b>吞掉受理异常。</b>连接已经建好，不能让可选增强的故障推翻成功的新建。</li>
     *   <li><b>不等结果。</b>agent 只排队，单次路径只提交后台任务；结果由连接详情轮询。</li>
     * </ul>
     */
    private void dispatchSemanticDerive(ConnectorView view) {
        String raw = view == null ? null : view.getId();
        if (raw == null || raw.isBlank()) {
            return;
        }
        long id;
        try {
            id = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("连接 id 不是数字，跳过语义层推导 id={}", raw);
            return;
        }
        // 雪花 id 恒为正；负数只可能是试连的合成 id，而那种行从不落库，推它等于推一个不存在的连接。
        if (id <= 0) {
            return;
        }
        try {
            semanticGenerationService.trigger(id, GenerationTrigger.CONNECTOR_CREATED);
        } catch (RuntimeException e) {
            // 只记不抛：这条连接已经落库、已经探测通过，它现在就能用。
            log.warn("语义层推导派发失败，连接照常可用 connectorId={}", id, e);
        }
    }

    @Operation(summary = "编辑连接器（敏感参数留空则沿用原值；同样会重新探测）")
    @PutMapping("/{id}")
    public ConnectorView update(@PathVariable Long id, @RequestBody ConnectorUpsert req) {
        superAdminGuard.requireSuperAdmin();
        return connectorService.update(id, req);
    }

    /**
     * 重新探测。
     *
     * <p>探测失败<b>不返回错误状态</b>，而是把失败原因回填进 {@code healthState} / {@code healthReason}
     * 后正常返回——前端要的是「现在是什么状态」，抛异常会让它拿不到回填结果。
     */
    @Operation(summary = "测试连接（重新探测并回填健康态、能力、只读验证）")
    @PostMapping("/{id}/test")
    public ConnectorView test(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectorService.test(id);
    }

    @Operation(summary = "启用 / 停用（停用会立刻作废已建立的连接池）")
    @PostMapping("/{id}/status")
    public Map<String, Object> setStatus(@PathVariable Long id, @RequestParam String status) {
        superAdminGuard.requireSuperAdmin();
        connectorService.setStatus(id, status);
        return Map.of("status", status);
    }

    /**
     * 使用记录。回答「这个连接被谁、在什么时候、用来做了什么」——产品方案第 10 节的第四块。
     *
     * <p>用 {@code GET} + 查询参数而不是 {@code POST} + body：它是一次纯读取，
     * 前端要能把筛选条件放进 URL 以便刷新和分享。
     *
     * <p>注意路径是 {@code /audit} 而不是 {@code /{id}/audit}：连接维度只是最常用的一种筛选，
     * 「某个 Agent 都访问过什么」同样是要回答的问题，做成 {@code /{id}/audit} 就把它挡死了。
     */
    @Operation(summary = "已缓存的结构快照（表 / 列，含上次同步时间）")
    @GetMapping("/{id}/schema")
    public List<ConnectorSchemaView> schema(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectorSchemaService.current(id);
    }

    /**
     * 重新拉取结构并与上一份快照比对。
     *
     * <p>返回里的 {@code diffs} 才是这个接口的价值所在：<b>客户悄悄加了一个字段、删了一张表，
     * 我们应该知道</b>——语义层（指标口径、业务名、样例问答）都挂在具体的表和列上，
     * 结构一变挂在上面的口径就跟着失效，而这件事今天没有任何机制会发现。
     *
     * <p>注意它会对客户库发 1 + N 次 information_schema 查询（N = 对象数，封顶 200），
     * 所以是<b>手动触发</b>的，没有做成定时任务。
     */
    @Operation(summary = "刷新结构快照并返回与上次的差异")
    @PostMapping("/{id}/schema/refresh")
    public ConnectorSchemaService.SnapshotResult refreshSchema(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectorSchemaService.refresh(id);
    }

    // ================================================================ 语义层

    /**
     * 已生成的「说明书」全文。
     *
     * <p>结构快照回答「有哪些表、哪些列」，这个接口回答<b>「它们是什么意思」</b>——
     * 表是干什么的、字段什么含义、表之间怎么连、业务口径怎么算。客户给的只读账号里
     * 全是 {@code t_ord_mst} 这样的名字，没有这一层模型就只能猜，而猜错不会报错，
     * 只会返回一个看起来很正常的错数字。
     *
     * <p>界面上<b>必须把 {@code evidence} 和 {@code status} 一起显示出来</b>：
     * 「有没有外部依据」是这条断言能不能被当真的唯一判据，而 {@code STALE} 意味着它挂的结构
     * 已经变了。只显示 {@code gloss} 会让一句可能已经不成立的话看起来像事实。
     */
    @Operation(summary = "语义层：已生成的全部语义行（表用途 / 字段含义 / 表关系 / 业务口径）")
    @GetMapping("/{id}/semantic")
    public List<ConnectorSemanticView> semantic(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        // all() 内部会按 id 查连接做归属校验，跑在请求线程上，租户条件由拦截器注入 —— 越权取不到。
        return connectorSemanticService.all(id).stream().map(ConnectorSemanticView::of).toList();
    }

    /**
     * 手工重跑推导。
     *
     * <p>接入时是自动跑的，但它<b>会失败</b>（模型超时、模型返回的 JSON 坏了、拉客户库结构那一步没成），
     * 而失败之后没有重试入口就等于永久失败——只能删了连接重建。所以留这个口子。
     *
     * <p><b>立刻返回，不等生成结束。</b>前置条件满足时进入 agent 的持久化队列，按表分片做多次短模型调用；
     * 不满足时回落到既有单次推导。响应额外返回实际选择的 {@code generator}。
     *
     * <h3>重跑要花多少钱，取决于路径和结构快照</h3>
     * <b>已经有快照</b>（正常情况：建连时那次推导已经拉过，或者有人点过「刷新结构」）——只读我们自己库里的
     * {@code connector_schema}，对客户系统零字节访问，代价就是一次模型调用。
     * <b>快照是空的</b>（从没拉成过，或者被清了）——推导会自己先去客户库补拉一次，那是 1 次 catalog +
     * 最多 200 次 describe。
     * <p>两个方向记反了都会误导人：记成「永远要打客户库」，会让人不敢用这个多数时候很便宜的口子；
     * 记成「永远不打」，会让人以为对着一条没快照的连接连点十次也没有客户侧成本。
     *
     * <h3>★ 下面两笔额外客户侧成本只属于回落的单次推导</h3>
     * <ol>
     *   <li><b>推导之前</b>会同步读一次客户的视图 / 存储过程定义（S1，{@code platform.sql_corpus}）。
     *       纯元数据、不出一个业务值，解析总时长 20 秒封顶——很便宜，但它确实打了客户的库。</li>
     *   <li><b>推导成功之后</b>会自动派发一段<b>采样验证</b>（S3 + S4），跑在另一个线程池上，
     *       <b>不占</b> {@code semanticStatus}。S3 按 30 次/分钟探查表关系（200 条候选最坏约 20 分钟），
     *       S4 按 20 条/分钟采集列取值（200 列最坏约 30 分钟，且只在数据出库档位开到第 3 档时才跑）。
     *       它们在 {@code connector_audit} 里的动作名分别是 {@code platform.semantic_probe}
     *       和 {@code platform.semantic_values}。</li>
     * </ol>
     * 所以回落路径的真实代价是：一次模型调用 + 一次语料读取 +（异步地）最长约半小时的采样；
     * agent 路径是多次短模型调用且不派发这些采样。
     * 只想重跑采样、不想再花一次模型调用的，走 {@code POST /{id}/semantic/validate}。
     *
     * <p><b>{@code NOT_APPLICABLE} 不是失败，重跑也不会变。</b>连接器类型不支持自描述
     * （今天的 HTTP 就是，它只声明 INVOKE / HEALTH）就没有结构可推，语义层对这种连接本来就不适用。
     * 它和 {@code FAILED} 分开成两个状态，就是为了让人别在这里做无意义的重试。
     *
     * <p>真实进度写在连接详情的 {@code semanticStatus} / {@code semanticNote} 上，前端轮询那里。
     *
     * <h3>重跑<b>只覆盖机器推断的行</b></h3>
     * {@code ConnectorSemanticService.replaceInferred} 先执行 {@code physicalDeleteInferred}
     * ——那条 SQL 是 {@code DELETE FROM connector_semantic WHERE connector_id = ? AND source = 'INFERRED'}
     * ——再把新推出来的行整批插回去，且每一行的 {@code source} 都被强制写成 {@code INFERRED}。
     * 所以<b>人在对话里答过的口径（{@code source=HUMAN}）一行不动</b>，直接采信客户库注释的
     * {@code IMPORTED} 同样不动。否则每点一次「重新生成」，就把业务方辛辛苦苦确认过的口径抹掉一次。
     */
    @Operation(summary = "语义层：手工重新推导（异步，立即返回；进度看连接详情的 semanticStatus）")
    @PostMapping("/{id}/semantic/derive")
    public Map<String, Object> deriveSemantic(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        // ★ 先做一次租户内的存在性校验，再把 id 交给后台线程。
        // 后台那条路上的租户过滤只有一层：推导线程里的 selectById 靠 MyBatis 拦截器注入 tenant_id，
        // 而那依赖 MdcAsyncSupport.wrap 把请求线程的 TenantContext 捎带过去。捎带一旦断掉
        //（或者哪天有人为了读结构给推导套上 runAsSystem），后台的 selectById 就不再过滤租户，
        // 路径参数里的任意 id 立刻变成一个跨租户触发器。这一行在【还有请求上下文】的地方把边界钉死，
        // 不是「顺手查一下」。
        connectorService.get(id);
        GenerationAck ack = semanticGenerationService.trigger(id, GenerationTrigger.MANUAL_REGENERATE);
        return Map.of("started", true, "generator", ack.kind().name());
    }

    /**
     * 手工触发一次<b>采样验证</b>（S3 表关系验证 + S4 列取值域采集）。
     *
     * <p>与「重新推导」分开成两个端点，因为两者花的是<b>不同人的钱</b>：推导花一次模型调用（我们的），
     * 采样花客户库的配额和负载（客户的）。把它们绑在一起，就只剩「两样一起花」这一个选项——
     * 而实际最常见的两种需求恰恰是单边的：
     * <ul>
     *   <li>模型那次输出坏了 → 只想重推，<b>不想</b>再对客户的库打几百条探查；</li>
     *   <li>企业超管刚把数据出库档位从第 1 档调到第 2/3 档 → 说明书没问题，
     *       只是那些关系当初<b>没被探查过</b>（{@code verified=NONE}），现在想补上。</li>
     * </ul>
     *
     * <h3>它做什么、不做什么</h3>
     * <b>只验没决过的</b>：候选是按 {@code verified == NONE} 挑的，已经有结论的一条都不重验
     *（验证器本身没有跨轮次游标，过滤在接线这一侧做）。{@code source=HUMAN} 的行一行不碰。
     * 结论是<b>逐条</b>落库的：跑到一半被重启打断，已经验出来的结论一条不丢。
     *
     * <h3>要花多少</h3>
     * S3 自我节流 30 次探查/分钟，S4 自我节流 20 条语句/分钟，两段<b>先后</b>跑。
     * 200 条候选 + 200 列的连接，最坏约 20 + 30 分钟。
     * <b>档位不够就什么都不做</b>：第 1 档（仅结构）下 S3 一条探查都不会发，S4 同理要第 3 档。
     * 那种情况下返回 {@code started=true} 仍然是对的——任务确实派出去了，
     * 真实结局（包括「因为档位没开所以一条都没验」）写在连接详情的 {@code semanticNote} 后半段。
     *
     * <p><b>立刻返回，不等结果。</b>同一条连接上已有一轮在跑时本次会被跳过（也写进 note）。
     */
    @Operation(summary = "语义层：手工触发采样验证（异步；只验未决过的关系，进度看连接详情的 semanticNote）")
    @PostMapping("/{id}/semantic/validate")
    public Map<String, Object> validateSemantic(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        // ★ 与 deriveSemantic 同一条：在【还有请求上下文】的地方先做一次租户内的存在性校验。
        // 后台那条路上的租户过滤依赖 MdcAsyncSupport.wrap 把 TenantContext 捎过去，捎带一旦断掉，
        // 路径参数里的任意 id 就变成一个跨租户的触发器——而它会真的去打别的租户的客户库。
        connectorService.get(id);
        connectorSemanticDeriveService.validateAsync(id);
        return Map.of("started", true);
    }

    /**
     * 删掉<b>一行</b>语义（企业超管）。
     *
     * <h3>为什么要有这个口子</h3>
     * 一条口径一旦记下，之后所有人问到这个词都会被<b>悄悄</b>按它算。在这个端点之前，
     * 纠正的唯一办法是在对话里让模型用同一个 {@code term} 再答一次覆盖它——
     * 而那条路对「整条记错了、根本不该存在」的行无能为力：覆盖只能改 gloss，删不掉词条本身。
     * 机器推断出来的错行同理，重新生成只会把它原样再推一遍。
     *
     * <h3>★ 它是物理删除，删了就真没了</h3>
     * 不能用 {@code BaseMapper.deleteById}：全局 {@code @TableLogic} 下那是软删，而
     * {@code uk_connector_semantic} <b>不含 deleted</b>，一条软删死行会永久占住键位，
     * 此后同一条断言再也写不进去，而且现象会伪装成「口径正在被同时修改」这种并发错。
     * 理由写在 {@code ConnectorSemanticMapper#physicalDeleteRow} 上，别在这里绕开它。
     *
     * <h3>★ 审计摘要里绝不放 gloss 与 detail</h3>
     * {@code connector_audit.error_detail} 那一列是<b>会被展示给客户看</b>的脱敏说明文字，
     * 而第 3 档采到的客户库真实取值就嵌在 {@code gloss}（「care_reason 里带着 {@code = '取值'}」）
     * 和 {@code detail_json}（判别值、取值域样本）里面。把它们抄进审计摘要，等于把刚从语义层删掉的
     * 真实取值原样换个地方再存一份——而且存进了一张<b>保留期更长、更公开</b>的表。
     * 所以摘要只放定位信息：scope / 对象 / 字段 / 词条 / 来源。
     *
     * @return {@code deleted} = 这次有没有真的删掉东西；{@code removed} = 实际删除行数（0 表示这行本来就不在）
     */
    @Operation(summary = "语义层：删掉一行（企业超管；物理删除，不可恢复）")
    @DeleteMapping("/{id}/semantic/{rowId}")
    public Map<String, Object> deleteSemanticRow(@PathVariable Long id, @PathVariable Long rowId) {
        superAdminGuard.requireSuperAdmin();
        // 归属校验也在 requireOwned 里做一遍（租户不可见 ⇒ NOT_FOUND）；这里取它是为了拿 tenantId / name 写审计。
        Connection conn = connectorSemanticService.requireOwned(id);
        // ★ 必须在删之前读：物理删除之后没有任何地方能回答「删掉的是哪一条」。
        //   走 all(id) 是因为本类只能用语义服务的公共读接口；一条连接的语义行是管理台量级，不值得为它多开一个 API。
        ConnectorSemantic target = findSemanticRow(id, rowId);
        int removed = connectorSemanticService.deleteRow(id, rowId);
        if (removed > 0) {
            recordSemanticRowDelete(conn, rowId, target);
        }
        return Map.of("deleted", removed > 0, "removed", removed);
    }

    private ConnectorSemantic findSemanticRow(Long connectorId, Long rowId) {
        if (rowId == null) {
            return null;
        }
        for (ConnectorSemantic r : connectorSemanticService.all(connectorId)) {
            if (r != null && rowId.equals(r.getId())) {
                return r;
            }
        }
        return null;
    }

    /**
     * 写删除留痕。
     *
     * <h3>★ 这里吞异常，而凭据取回那边不吞——差别在于「还来不来得及不做」</h3>
     * {@code revealCredential} 的审计写在<b>送出明文之前</b>，写不进去就不给明文，是一道真的闸。
     * 这里的删除<b>已经发生了</b>：再把异常抛上去，前端会显示「删除失败」，而行其实已经没了——
     * 人多半会去查「为什么删不掉」，或者重试（第二次 {@code removed=0}，连这条审计都不会再试写）。
     * 用一个已经无法挽回的动作换一次误报，只会让排查更难。所以这里只把足以重建这条记录的信息
     * 留进日志（{@code recordAdminAction} 自己也会打一条 ERROR），照常返回真实结果。
     */
    private void recordSemanticRowDelete(Connection conn, Long rowId, ConnectorSemantic target) {
        try {
            connectorAuditService.recordAdminAction(conn.getId(), conn.getTenantId(), conn.getName(),
                    ConnectorAuditService.OP_SEMANTIC_ROW_DELETE, semanticDeleteSummary(rowId, target), true, null);
        } catch (RuntimeException e) {
            log.error("语义行删除留痕写入失败（行已删除，本次照常返回） connectorId={} rowId={}",
                    conn.getId(), rowId, e);
        }
    }

    /**
     * 审计摘要：<b>只有定位信息</b>。
     *
     * <p>不放 {@code gloss}、不放 {@code detail}，理由见 {@link #deleteSemanticRow} 的第三节——
     * 客户库的真实取值就嵌在那两处。往这里加字段之前先回答一个问题：这个值会不会出现在客户的数据里。
     */
    private static String semanticDeleteSummary(Long rowId, ConnectorSemantic r) {
        if (r == null) {
            // 删之前就读不到（并发里被别人先删了，或者这个 rowId 本来就不属于这条连接）。
            // 如实写「读不到」，不要编一条看起来完整的摘要。
            return "删除语义行 id=" + rowId + "：删除前已读不到这一行";
        }
        StringBuilder sb = new StringBuilder("删除语义行 id=").append(rowId)
                .append(" scope=").append(blankToDash(r.getScope()))
                .append(" 来源=").append(blankToDash(r.getSource()));
        if (r.getObjectName() != null && !r.getObjectName().isBlank()) {
            sb.append(" 对象=").append(r.getObjectName().trim());
        }
        if (r.getFieldName() != null && !r.getFieldName().isBlank()) {
            sb.append(" 字段=").append(r.getFieldName().trim());
        }
        if (r.getTerm() != null && !r.getTerm().isBlank()) {
            sb.append(" 词条=").append(r.getTerm().trim());
        }
        return sb.toString();
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "—" : s.trim();
    }

    @Operation(summary = "使用记录（分页；可按连接、Agent、能力、成败、时间筛选）")
    @GetMapping("/audit")
    public Page<ConnectorAuditView> audit(ConnectorAuditQuery query) {
        superAdminGuard.requireSuperAdmin();
        return connectorAuditService.query(query);
    }

    @Operation(summary = "删除（同时摘除所有 Agent 授权、清掉自描述缓存、作废连接池）")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        connectorService.delete(id);
        return Map.of("deleted", true);
    }

    // ================================================================ 授权脚本

    /**
     * 生成一段可直接复制执行的授权命令。
     *
     * <p><b>这个端点不碰任何一条已存在的连接</b>——它发生在连接建立<b>之前</b>：
     * 客户还没有账号，正是要靠这段脚本去建。所以既不收 connectorId，也不写库。
     *
     * <p>把 {@code writePolicy} 一起收进来，是为了让「平台侧的闸」和「数据库侧的授权」
     * 在同一个动作里对齐：策略选了只读，脚本就只 {@code GRANT SELECT}。
     * 两边分开配置迟早会分叉，而分叉的方向通常是数据库那边授得更宽。
     */
    @Operation(summary = "生成授权脚本（建连接之前用，不落库）")
    @PostMapping("/grant-script")
    public GrantScript grantScript(@RequestBody GrantScriptRequestDto body) {
        superAdminGuard.requireSuperAdmin();
        return grantScriptService.generate(body.getKind(), body.toRequest());
    }

    // ================================================================ 写操作审批

    @Operation(summary = "待审批写操作（分页；可按连接、Agent、状态筛选）")
    @GetMapping("/pending-writes")
    public Page<PendingWriteView> pendingWrites(PendingWriteQuery query) {
        superAdminGuard.requireSuperAdmin();
        return pendingWriteService.query(query);
    }

    /**
     * 批准并执行。
     *
     * <h3>★ 返回 200 不等于「已批准并改好了」</h3>
     * 这个方法<b>只有在记录不存在 / 不属于本租户时才抛异常</b>。其余四种结局都是正常返回，
     * 真实结局写在返回体的 {@code status} 里：
     * <ul>
     *   <li>{@code APPROVED} —— 真的批了，也真的执行成功了（{@code affectedRows} 是影响行数）。</li>
     *   <li>{@code FAILED} —— 批了，但在客户库上执行失败（{@code errorDetail} 是原因）。</li>
     *   <li>{@code EXPIRED} —— 已过期，<b>没有执行</b>。陈年请求的 WHERE 今天命中的可能是另一批行。</li>
     *   <li>{@code REJECTED} / 其它 —— 并发下被另一个操作者先处理了，本次<b>没有重复执行</b>。</li>
     * </ul>
     * 前端必须读 {@code status} 再决定提示语。无条件弹「已批准」是错的——
     * 那会让人以为数据改好了，而实际可能一行没动。
     */
    @Operation(summary = "批准并执行（真实结局看返回体的 status，不要按 HTTP 200 判断）")
    @PostMapping("/pending-writes/{id}/approve")
    public PendingWriteView approvePendingWrite(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return pendingWriteService.approve(id);
    }

    /** 拒绝。与批准同一套抢锁逻辑：已被别人处理过的直接返回真实状态，不覆盖别人的决定。 */
    @Operation(summary = "拒绝（真实结局同样看返回体的 status）")
    @PostMapping("/pending-writes/{id}/reject")
    public PendingWriteView rejectPendingWrite(@PathVariable Long id,
                                               @RequestBody(required = false) PendingWriteRejectDto body) {
        superAdminGuard.requireSuperAdmin();
        return pendingWriteService.reject(id, body == null ? null : body.getReason());
    }
}
