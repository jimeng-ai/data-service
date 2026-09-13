package com.jimeng.dataserver.ai.connector;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditQuery;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditView;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorService;
import com.jimeng.dataserver.ai.connector.service.ConnectorUpsert;
import com.jimeng.dataserver.ai.connector.service.ProbeOutcome;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
@Tag(name = "连接器", description = "客户系统接入：数据库 / HTTP 接口等外部资源的注册与探测")
@RestController
@RequestMapping("/data/admin/connectors")
@RequiredArgsConstructor
public class ConnectorAdminController {

    private final ConnectorService connectorService;
    private final ConnectorAuditService connectorAuditService;
    private final ConnectorSchemaService connectorSchemaService;
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

    @Operation(summary = "新建连接器（保存前会实际探测：连通性 + 只读校验 + 能力，探不过则拒绝保存）")
    @PostMapping
    public ConnectorView create(@RequestBody ConnectorUpsert req) {
        superAdminGuard.requireSuperAdmin();
        return connectorService.create(req);
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
}
