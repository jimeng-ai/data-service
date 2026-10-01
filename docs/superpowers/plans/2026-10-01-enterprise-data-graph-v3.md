# 数据星图 v3 实施计划：给业务人员看的对象关系图

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把数据星图从「给 IT 看的表清单」改成「业务人员和产品自己登录来看的业务对象关系图」：语义层补全关系，另存一份给人看的业务文字，前端按对象画、默认不出现代码，并改为可授予成员的模块。

**Architecture:** data-service 新增三块：① 关系发现（声明外键 + 通用命名规律 + 整库一次看全的模型一遍，按「弱推断可被确定性候选替换」的规则写 JOIN 行）；② 业务视图（`connector_business_view`：业务名、一句说明、业务领域、关系角色名，只补缺的和输入变了的）；③ 补全链（语义层生成完成后由事件触发，认领 + 定时对账补跑，按顺序跑 ①、派发原有的采样核对、②）。星图投影改为合并业务视图、按 §6.3 兜底；接口改为按 `DATA_GRAPH_MODULE` 模块授权。jm-agent-front 把卡片换成对象卡片、把边按对象两两合并，加领域筛选和三段式对象详情，入口改为模块授权。

**Tech Stack:** Java 17 / Spring Boot 3.0.2 / MyBatis-Plus / Lombok（运行时 jackson-databind 2.11）/ Spring 事件与 `@Scheduled`；React 18 / TypeScript 5.6 / antd 5 / @tanstack/react-query 5 / `@xyflow/react@12.12.0` / `@dagrejs/dagre@3.1.1`；Playwright（`e2e/` 独立依赖）。

**Spec:** `data-service/docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md`（分支 `feat/data-graph-v2`，v3 设计提交 `84af32e`；本计划所在的提交顺带把 spec 同验证结果对齐，见「开工前」）

> 本计划的全部代码都在两个仓库的草稿分支 `wip/data-graph-v3-validate` 上实跑验证过（2026-10-01），验证完两个 worktree 已切回 `feat/data-graph-v2`。计划本身也机械重放过一遍：从开工前的提交（data-service `84af32e`、jm-agent-front `6cacb44`）起，按顺序照做每一步的整份覆盖、逐段替换和删除，结果与草稿分支逐字节一致；每个「把这段 → 替换为」的原文在当时的文件里都恰好出现一次。验证结果：
> - 后端：全量 data-server 测试 2285 个用例、0 失败；数据星图 `/systems/{id}` 在 200 张表、200 条关系、400 行业务视图下（服务层 + 序列化）P95 = 10 ms。
> - 前端：typecheck、lint 通过，`npm run test:data-graph` 77/77；Task 11–14 每一步结束时的中间状态也实跑过，各任务 Expected 里的通过数是实测值。
> - 本地真实数据（Task 16）：接口验收 13/13，页面验收 41/41；两个系统 27 个对象的标题全部来自业务视图，没有一条退回兜底；补全链每个连接 10–20 秒跑完；认领的 CAS 在真库上第二次拿到 0。

## 工作区（先读）

- 后端：`/Users/hukui/Desktop/workspace/jm/.worktrees/data-service-data-graph-v2`，分支 `feat/data-graph-v2`。下文记作 `$DS`。
- 前端：`/Users/hukui/Desktop/workspace/jm/.worktrees/jm-agent-front-data-graph-v2`，分支 `feat/data-graph-v2`，`node_modules` 和 `e2e/node_modules` 已装好。下文记作 `$FE`。
- **不要在 `jm/` 根目录跑 git**：那里的 git 指向 home 目录下一个无关的仓库。一律 `cd` 进上面两个 worktree，或者用 `git -C`。
- 草稿分支 `wip/data-graph-v3-validate`（两个仓库各一个）只是验证记录：不合并、不改动，本计划执行完后可以删掉。
- 每个 Bash 调用开头先设好变量：`DS=/Users/hukui/Desktop/workspace/jm/.worktrees/data-service-data-graph-v2; FE=/Users/hukui/Desktop/workspace/jm/.worktrees/jm-agent-front-data-graph-v2`。
- 本地环境：dev MySQL 容器叫 `dev-mysql`（库名 `data-server`，root/123456），Nacos 在 `localhost:8849`。本地起 data-server 必须带 `NACOS_SERVER_ADDR=localhost:8849`，并且**先停掉正在跑的 data-server 再打包**（边跑边打包，运行中的进程懒加载类时会 `NoClassDefFoundError`）。

## Global Constraints

- 出网对象一律用 Lombok `@Value` 静态类：项目运行时的 jackson-databind 是 2.11，不认 record。进程内的事件、内部结果可以用 record。
- 两张新表 `connector_business_view` / `connector_enrichment_state`：唯一键不含 `deleted`，**不产生软删死行**——需要删除时物理删除（mapper 上的 `@Delete`，显式写死租户与连接），`HUMAN` 行只原地更新。两张表都进 `JimengTenantLineHandler.TENANT_AWARE_TABLES`。
- `ConnectorSemanticService` 不能依赖 `ClaudeService`（它被 `ConnectorToolExecutor` 注入，碰到会闭合启动期构造循环）。要调模型的新代码放在别的 bean 里（`SemanticModelCall`、`SemanticRelationDiscovery`、`BusinessViewGenerator`）。
- 推导服务与补全链之间**只用 Spring 事件**连：补全链要回调推导服务派发采样核对，互相构造器注入会成环。推导服务和 agent 定稿（`SemanticGenerationFinalizer`）靠 `ApplicationEventPublisherAware` 拿事件总线；单测按位置构造它们时总线是 `null`，行为与接入补全链之前一致。
- 模型调用：沿用 `connector.semantic.infer-model`，经 `ClaudeService.messagesInternal`（不带工具、只调一轮），超时沿用 `ConnectorSemanticDeriveService.modelTimeout`。请求体用可变集合。**单测一律用假实现，不真调模型**；真调只在 Task 16。
- 后端测试只放在 `modules/data-server`。跑单测用 `mvn -o -q -pl modules/data-server test -Dtest='类名*' -Dsurefire.failIfNoSpecifiedTests=false`，**类名后面的 `*` 不能省**，否则 `@Nested` 里的用例不会跑。`@Nested` 的用例只记在 `target/surefire-reports/TEST-*.xml` 里，用例数和失败数用 `grep -c '<testcase'` 和 `grep -c '<failure\|<error'` 数 xml。
- 改了 `common/*`（Task 1、Task 5）之后先 `mvn -o -q install -DskipTests -pl common/common-core,common/common-persistence`，否则 `-pl modules/data-server` 会用本地仓库里的旧 jar，改动被静默吞掉。
- 前端用 Node 20（`source ~/.nvm/nvm.sh && nvm use 20`）。通过标准是 `npm run typecheck && npm run lint`（`--max-warnings 0`）加 `npm run test:data-graph`（Playwright 夹具脚本）。
- 依赖版本锁死：`@xyflow/react` `12.12.0`、`@dagrejs/dagre` `3.1.1`（不带 `^`，v2 审查 #12）。
- 界面文案（客户的表名、字段名、注释只在折叠的技术信息区出现）不得出现：`READY` / `RUNNING` / `FAILED` 等枚举原文、「AI」、置信度或百分比、语义层的原文说明 / 依据 / 核对说明。业务文字的合格标准是 spec 附录 B 与 §6.2（`BusinessTextRules`）。句式、空状态文案、提示文案逐字照本计划写，e2e 按原文逐字断言。
- DDL 没有 Flyway：本地 dev 库可以直接执行；**生产库由用户执行**，计划里只写变更文档（`docs/config-changes/`，只写表名、键名与用途，不写任何密钥）。
- 提交信息用中文，末尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。**不要 push**：推送需要用户另行同意，而且两个仓库要串行推（先 data-service 后前端，它们共用一台 runner）。

## Review Focus

1. **真实模型的输出不守格式**（多包一层 markdown、正文前有闲话、被 max_tokens 截断、写了没问到的列、写了不存在的表、把 evidence 写成 GUESS）：解析兜得住、过滤丢得干净、整条链不崩；解析彻底失败时这一步记 FAILED，规则候选和已写下的批次照样保留。由 Task 6 的 `SemanticModelCallTest`、`SemanticRelationDiscoveryTest#规则与模型`、`#模型失败` 覆盖，Task 16 用真模型再核一遍。
2. **多副本、或推导与定时对账同时触发同一条连接**：认领（`claim_at` 的一条 CAS UPDATE）只让一个跑；没抢到认领的那次触发，原本要派发的采样核对照样派出去，不会因为补全链在跑就丢。由 Task 9 的 `SemanticEnrichmentServiceTest#抢不到认领`、`#认领丢了` 覆盖；CAS 本身是 SQL，Task 16 在本地库上连发两次认领核实第二次拿到 0。
3. **旧快照（没有 `unique_keys` / `foreign_keys`）**：命名规则不给目标列（唯一键未知就放弃），推断关系一条都不画，页面照常。由 Task 3 `RelationCandidatesTest#目标列`、Task 10 `DataGraphProjectorTest$Edges#旧快照` 覆盖。
4. **业务文字漏出代码或 AI 口吻**：模型起的名字夹着表名 / 字段名、写了「疑似」「字段」、两个对象重名；兜底用的客户注释夹着字段名。一律不上屏（重试一次后退回兜底，兜底注释同样过代码扫描）。由 Task 7 `BusinessTextRulesTest`、Task 8 `BusinessViewGeneratorTest#重试一次`、`#重名`、Task 10 `DataGraphProjectorTest$BusinessView#标题兜底`、`#角色名` 覆盖；前端 e2e 另做一遍默认视图的代码扫描。
5. **普通成员直接敲地址或直接调接口**：没被授予模块时，页面显示「无权访问」，接口返回 4001（HTTP 200，不会被踢回登录页），一行数据都不查。由 Task 1 `DataGraphControllerTest#没有模块被拒`、`PermissionResolverModuleTest` 和前端 e2e「无权访问」覆盖。

## 开工前：spec 已同实现对齐（data-service，不用做任何事）

验证时对 spec 做了几处修订，已经和本计划在同一个提交里（`git -C $DS log --oneline -1` 是 `docs: 数据星图 v3 实施计划……`）：

- §5.3 与 §7.1 第 7′ 行：关系发现里模型那一遍推出的关系只进语义层给 Agent 当线索，采样核对通过或业务方确认后才上星图（三次真实库试跑的结论，见 Task 6）；
- §1 第 1 条：被采样核对否掉的关系按第 6 行不画，验收按「没被否掉的全部出现」核；「明细 → 表头」改为记录项；
- §6.1 与附录 D：状态表多一列 `relation_pass_fingerprint`（模型那一遍输入没变就不再问；指纹按这一遍写完之后的样子算，它自己写下的关系不算输入变化，见 Task 6），附录 D 换成实际 DDL 全文；
- §13：不改 `modules/data-server/docs/mysql-schema.sql`（插件下线前的全量导出，早已不随迁移更新），上线清单 ⑥ 的自检会自动扫到新迁移；
- §14、§15：补上模型那一遍的随机性与「数据量太小的库核对判不出」这条限制。

---
### Task 1: 「数据星图」模块授权（data-service）

v2 的三个接口只给企业超管用。v3 的读者是业务人员和产品（spec §9）：新增可授予成员的模块码 `DATA_GRAPH_MODULE`。已有的智能体 / 知识库 / 对话三个模块在后端靠实例授权把关，模块本身只在前端拦；数据星图没有实例可授，所以模块检查必须放在后端，新增 `PermissionResolver.assertCurrentModule`。jm-admin 的授权弹窗按 `/modules` 接口渲染，自动出现「数据星图」，不用改。

**Files:**
- Modify: `common/common-core/src/main/java/com/jimeng/common/core/constant/PlatformConstant.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/admin/rbac/grantable/controller/GrantableResourceController.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/admin/rbac/permission/PermissionResolver.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphController.java`（整份重写）
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/admin/rbac/grantable/controller/GrantableResourceControllerTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/admin/rbac/permission/PermissionResolverModuleTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphControllerTest.java`（整份重写）

**Interfaces:**
- Produces:
  - `PlatformConstant.MODULE_DATA_GRAPH = "DATA_GRAPH_MODULE"`，并进 `ALL_MODULES`；
  - `PermissionResolver.assertCurrentModule(String moduleCode, String deniedMessage): void`：超管恒通过；否则 `canEnter` 为假时抛 `ServiceException(AUTHENTICATION_FAIL, deniedMessage)`；
  - `DataGraphController(PermissionResolver, DataGraphService)`，拒绝文案 `DataGraphController.DENIED = "没有数据星图的访问权限"`。
  - 前端 Task 15 用模块码 `DATA_GRAPH_MODULE`。

- [ ] **Step 1: 写失败的测试**

`modules/data-server/src/test/java/com/jimeng/dataserver/admin/rbac/grantable/controller/GrantableResourceControllerTest.java`：

```java
package com.jimeng.dataserver.admin.rbac.grantable.controller;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.grantable.dto.ModuleOption;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GrantableResourceControllerTest {

    /**
     * jm-admin 的角色授权弹窗按这个接口渲染模块勾选框；保存时 {@code RoleResourceService} 按 {@code ALL_MODULES} 校验。
     * 两边少一个，这个模块就授不出去；多一个，勾上之后保存会被拒。
     */
    @Test
    @DisplayName("可授权模块与 ALL_MODULES 一一对应，数据星图在列")
    void 模块列表() {
        GrantableResourceController controller = new GrantableResourceController(
                mock(AgentMapper.class), mock(KnowledgeBaseMapper.class), mock(SuperAdminGuard.class));

        List<ModuleOption> modules = controller.modules();

        assertEquals(PlatformConstant.ALL_MODULES, modules.stream().map(ModuleOption::getCode).toList());
        assertEquals("数据星图", modules.stream()
                .filter(m -> PlatformConstant.MODULE_DATA_GRAPH.equals(m.getCode()))
                .findFirst().orElseThrow().getName());
    }
}
```

`modules/data-server/src/test/java/com/jimeng/dataserver/admin/rbac/permission/PermissionResolverModuleTest.java`：

```java
package com.jimeng.dataserver.admin.rbac.permission;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import com.jimeng.persistence.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class PermissionResolverModuleTest {

    private static final String DENIED = "没有数据星图的访问权限";

    private PermissionResolver resolverWith(ResolvedPermissions permissions) {
        PermissionResolver resolver = spy(new PermissionResolver(mock(SysUserMapper.class),
                mock(SysUserRoleMapper.class), mock(SysRoleResourceMapper.class), mock(AgentMapper.class),
                mock(KnowledgeBaseMapper.class), mock(AiSkillMapper.class)));
        doReturn(permissions).when(resolver).resolveCurrent();
        return resolver;
    }

    @Test
    @DisplayName("角色被授予了这个模块的成员：放行")
    void 有模块的成员放行() {
        PermissionResolver resolver = resolverWith(new ResolvedPermissions(false, "MEMBER",
                Set.of(PlatformConstant.MODULE_DATA_GRAPH), null, null));
        assertDoesNotThrow(() -> resolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED));
    }

    /** 业务码 4001 按平台约定走 HTTP 200：前端只在 401/403 时踢回登录页，这里不会把成员踢出去。 */
    @Test
    @DisplayName("没有这个模块的成员：4001，文案原样带出")
    void 没有模块的成员被拒() {
        PermissionResolver resolver = resolverWith(new ResolvedPermissions(false, "MEMBER",
                Set.of(PlatformConstant.MODULE_AGENT), null, null));

        ServiceException e = assertThrows(ServiceException.class,
                () -> resolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED));

        assertEquals(ExceptionCode.AUTHENTICATION_FAIL.getResultCode(), e.getRespCode());
        assertEquals(DENIED, e.getRespMsg());
    }

    @Test
    @DisplayName("企业超管：不看授权，恒通过")
    void 超管恒通过() {
        PermissionResolver resolver = resolverWith(new ResolvedPermissions(true, "SUPER_ADMIN",
                Set.of(), null, null));
        assertDoesNotThrow(() -> resolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED));
    }
}
```

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphControllerTest.java`（整份覆盖）：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataGraphControllerTest {

    private static final String DENIED = "没有数据星图的访问权限";

    @Test
    @DisplayName("没有「数据星图」模块：三个接口都被拒，而且一行数据都不查")
    void 没有模块被拒() {
        PermissionResolver resolver = mock(PermissionResolver.class);
        DataGraphService service = mock(DataGraphService.class);
        doThrow(new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, DENIED))
                .when(resolver).assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED);
        DataGraphController controller = new DataGraphController(resolver, service);

        assertEquals(DENIED, assertThrows(ServiceException.class, controller::systems).getRespMsg());
        assertThrows(ServiceException.class, () -> controller.system("7"));
        assertThrows(ServiceException.class, () -> controller.table("7", "t_order"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("有模块（或企业超管）：每个接口先查模块，再原样交给服务")
    void 有模块放行() {
        PermissionResolver resolver = mock(PermissionResolver.class);
        DataGraphService service = mock(DataGraphService.class);
        List<DataGraphViews.SystemSummary> systems = List.of();
        when(service.systems()).thenReturn(systems);
        DataGraphController controller = new DataGraphController(resolver, service);

        assertSame(systems, controller.systems());
        controller.system("7");
        controller.table("7", "t_order");
        verify(resolver, times(3)).assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED);
        verify(service).system("7");
        verify(service).table("7", "t_order");
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='GrantableResourceControllerTest*,PermissionResolverModuleTest*,DataGraphControllerTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报「找不到符号」（`MODULE_DATA_GRAPH`、`assertCurrentModule`），以及 `PermissionResolver无法转换为SuperAdminGuard`。

- [ ] **Step 3: 加模块码**

`PlatformConstant.java`：

第 1 处，把：

```java
    public static final String MODULE_AGENT = "AGENT_MODULE";
    public static final String MODULE_KB = "KB_MODULE";
    public static final String MODULE_CHAT = "CHAT_MODULE";

    /** 全部可授权模块码。 */
    public static final List<String> ALL_MODULES =
            Arrays.asList(MODULE_AGENT, MODULE_KB, MODULE_CHAT);
}
```

替换为：

```java
    public static final String MODULE_AGENT = "AGENT_MODULE";
    public static final String MODULE_KB = "KB_MODULE";
    public static final String MODULE_CHAT = "CHAT_MODULE";
    /** 数据星图。它没有实例授权，后端靠 {@code PermissionResolver.assertCurrentModule} 按模块把关。 */
    public static final String MODULE_DATA_GRAPH = "DATA_GRAPH_MODULE";

    /** 全部可授权模块码。 */
    public static final List<String> ALL_MODULES =
            Arrays.asList(MODULE_AGENT, MODULE_KB, MODULE_CHAT, MODULE_DATA_GRAPH);
}
```

`GrantableResourceController.java`：

第 1 处，把：

```java
        return List.of(
                new ModuleOption(PlatformConstant.MODULE_AGENT, "智能体"),
                new ModuleOption(PlatformConstant.MODULE_KB, "知识库"),
                new ModuleOption(PlatformConstant.MODULE_CHAT, "对话")
        );
    }
}
```

替换为：

```java
        return List.of(
                new ModuleOption(PlatformConstant.MODULE_AGENT, "智能体"),
                new ModuleOption(PlatformConstant.MODULE_KB, "知识库"),
                new ModuleOption(PlatformConstant.MODULE_CHAT, "对话"),
                new ModuleOption(PlatformConstant.MODULE_DATA_GRAPH, "数据星图")
        );
    }
}
```

- [ ] **Step 4: 加模块检查**

`PermissionResolver.java`：

第 1 处，把：

```java
        throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "无权访问该资源");
    }

    /** 某实例的 create_user 是否等于当前账号（按类型查对应表；表均在租户白名单内，跨租户查不到）。 */
    private boolean isOwnedByCurrent(ResourceType type, Long id) {
        if (id == null) {
```

替换为：

```java
        throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "无权访问该资源");
    }

    /**
     * 断言当前账号可进入某模块（超管恒通过），否则抛 4001。
     *
     * <p>给<b>没有实例授权</b>、只能按模块把关的功能用（数据星图）。智能体 / 知识库 / 对话三个模块在后端靠实例授权把关，
     * 模块本身只在前端拦；这类功能没有实例可授，不在这里查，后端就等于没有门。
     */
    public void assertCurrentModule(String moduleCode, String deniedMessage) {
        if (!resolveCurrent().canEnter(moduleCode)) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, deniedMessage);
        }
    }

    /** 某实例的 create_user 是否等于当前账号（按类型查对应表；表均在租户白名单内，跨租户查不到）。 */
    private boolean isOwnedByCurrent(ResourceType type, Long id) {
        if (id == null) {
```

`DataGraphController.java`（整份覆盖）：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据星图：给业务人员和产品看的业务对象关系图（设计文档 {@code docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md}）。
 *
 * <p>按模块授权（§9）：被授予「数据星图」模块的成员和企业超管可用，能看到本企业全部系统。这个功能没有实例授权，
 * 所以模块检查必须在后端做，前端的入口隐藏只是纵深防御。
 */
@Tag(name = "数据星图", description = "各业务系统的业务对象与关联（只读）")
@RestController
@RequestMapping("/data/admin/data-graph")
@RequiredArgsConstructor
public class DataGraphController {

    static final String DENIED = "没有数据星图的访问权限";

    private final PermissionResolver permissionResolver;
    private final DataGraphService dataGraphService;

    @Operation(summary = "系统列表（有结构快照的数据连接）")
    @GetMapping("/systems")
    public List<SystemSummary> systems() {
        requireModule();
        return dataGraphService.systems();
    }

    @Operation(summary = "某个系统的业务对象与关联")
    @GetMapping("/systems/{connectorId}")
    public SystemGraph system(@PathVariable String connectorId) {
        requireModule();
        return dataGraphService.system(connectorId);
    }

    @Operation(summary = "单个对象的详情：说明、关联与技术信息")
    @GetMapping("/systems/{connectorId}/tables")
    public TableDetail table(@PathVariable String connectorId, @RequestParam("name") String name) {
        requireModule();
        return dataGraphService.table(connectorId, name);
    }

    private void requireModule() {
        permissionResolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED);
    }
}
```

- [ ] **Step 5: 装 common-core，再跑测试**

```bash
cd $DS && mvn -o -q install -DskipTests -pl common/common-core && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='GrantableResourceControllerTest*,PermissionResolverModuleTest*,DataGraphControllerTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 三个文件都是 `failures=0`；用例数 GrantableResourceControllerTest 1、PermissionResolverModuleTest 3、DataGraphControllerTest 2。

- [ ] **Step 6: 提交**

```bash
cd $DS && git add common/common-core/src/main/java/com/jimeng/common/core/constant/PlatformConstant.java \
  modules/data-server/src/main/java/com/jimeng/dataserver/admin/rbac \
  modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphController.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/admin \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphControllerTest.java && \
git commit -q -F - <<'MSG'
feat(graph): 数据星图改为可授予成员的「数据星图」模块

新增模块码 DATA_GRAPH_MODULE（进 ALL_MODULES 与可授权列表，jm-admin 自动出现）。
数据星图没有实例授权，模块检查放在后端：PermissionResolver.assertCurrentModule，
不满足时 4001（HTTP 200，不踢回登录页），超管恒通过。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 2: 结构快照带上声明的外键（data-service）

声明的外键是关系发现里最硬的一条依据（spec §5.2 第 1 条）。`MySqlSession.describe` 追加读一次 `information_schema.KEY_COLUMN_USAGE`，放进对象级 `extra.foreign_keys`。它和 `unique_keys` 同一个纪律：刷新结构后才会有；不进 `content_hash`，不会引起漂移；读不到（Doris / StarRocks 这类引擎没有这张视图）就不放这个键，而不是放空列表——「不知道」和「没有」必须分得开。

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlSession.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlConnectorTest.java`

**Interfaces:**
- Produces:
  - 快照 `detail_json.extra.foreign_keys`：`[{"name":约束名,"columns":[列...],"ref_table":被引用表,"ref_columns":[列...]}]`，按约束名排序；
  - `MySqlSession.EXTRA_FOREIGN_KEYS = "foreign_keys"`（包内可见），Task 3 的 `RelationCandidates.EXTRA_FOREIGN_KEYS` 与它同名，由单测钉住。

- [ ] **Step 1: 写失败的测试**

`MySqlConnectorTest.java` 里改两处：唯一键那组测试的 `describeWith` 夹具要把外键查询分到自己的 PreparedStatement 上（否则它会拿到列查询的结果集），再在唯一键那组后面加一组外键测试。

第 1 处，把：

```java
            when(conn.createStatement()).thenReturn(mock(Statement.class));
            PreparedStatement colPs = mock(PreparedStatement.class);
            PreparedStatement keyPs = mock(PreparedStatement.class);
            when(conn.prepareStatement(anyString())).thenAnswer(inv ->
                    MySqlSession.UNIQUE_KEYS_SQL.equals(inv.getArgument(0)) ? keyPs : colPs);
            ResultSet colRs = resultSet(columns);
            when(colPs.executeQuery()).thenReturn(colRs);
            if (keyFailure != null) {
```

替换为：

```java
            when(conn.createStatement()).thenReturn(mock(Statement.class));
            PreparedStatement colPs = mock(PreparedStatement.class);
            PreparedStatement keyPs = mock(PreparedStatement.class);
            PreparedStatement fkPs = mock(PreparedStatement.class);
            when(conn.prepareStatement(anyString())).thenAnswer(inv ->
                    MySqlSession.UNIQUE_KEYS_SQL.equals(inv.getArgument(0)) ? keyPs
                            : MySqlSession.FOREIGN_KEYS_SQL.equals(inv.getArgument(0)) ? fkPs : colPs);
            ResultSet fkRs = resultSet(List.of());
            when(fkPs.executeQuery()).thenReturn(fkRs);
            ResultSet colRs = resultSet(columns);
            when(colPs.executeQuery()).thenReturn(colRs);
            if (keyFailure != null) {
```

第 2 处，把：

```java
        }
    }

    // ================================================================ 对象是否存在（契约 K-1）

    /**
```

替换为：

```java
        }
    }

    // ================================================================ 声明外键采集（数据星图 v3 §5.2）

    /**
     * 声明外键是关系发现里最硬的一条依据（客户自己在库里写下的约束）。和唯一键同一个纪律：
     * 读不到 ≠ 没有；读失败不能让「看结构」本身失败。
     */
    @Nested
    @DisplayName("声明外键采集")
    class ForeignKeyCapture {

        private ObjectDetail describeWith(SQLException fkFailure, List<Map<String, Object>> fkRows)
                throws SQLException {
            DataSource ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(mock(Statement.class));
            PreparedStatement colPs = mock(PreparedStatement.class);
            PreparedStatement keyPs = mock(PreparedStatement.class);
            PreparedStatement fkPs = mock(PreparedStatement.class);
            when(conn.prepareStatement(anyString())).thenAnswer(inv ->
                    MySqlSession.UNIQUE_KEYS_SQL.equals(inv.getArgument(0)) ? keyPs
                            : MySqlSession.FOREIGN_KEYS_SQL.equals(inv.getArgument(0)) ? fkPs : colPs);
            ResultSet colRs = resultSet(List.of(
                    row("COLUMN_NAME", "id", "COLUMN_TYPE", "bigint(20)", "IS_NULLABLE", "NO", "COLUMN_KEY", "PRI"),
                    row("COLUMN_NAME", "cust_id", "COLUMN_TYPE", "bigint(20)", "IS_NULLABLE", "YES",
                            "COLUMN_KEY", "MUL")));
            when(colPs.executeQuery()).thenReturn(colRs);
            ResultSet keyRs = resultSet(List.of(row("INDEX_NAME", "PRIMARY", "SEQ_IN_INDEX", 1, "COLUMN_NAME", "id")));
            when(keyPs.executeQuery()).thenReturn(keyRs);
            if (fkFailure != null) {
                when(fkPs.executeQuery()).thenThrow(fkFailure);
            } else {
                ResultSet fkRs = resultSet(fkRows);
                when(fkPs.executeQuery()).thenReturn(fkRs);
            }
            return new MySqlSession(inst(baseParams(), "pwd"), ds, "shop", new ReadOnlySqlGuard())
                    .describe("t_ord");
        }

        @Test
        @DisplayName("组合外键按约束分组、列按 ORDINAL_POSITION 排；约束之间按名字排")
        void 分组与排序() {
            List<MySqlSession.ForeignKey> keys = MySqlSession.groupForeignKeys(List.of(
                    new MySqlSession.FkPart("fk_z", 1, "cust_id", "t_cust", "id"),
                    new MySqlSession.FkPart("fk_a", 2, "line_no", "t_ord_dtl", "line_no"),
                    new MySqlSession.FkPart("fk_a", 1, "ord_id", "t_ord_dtl", "ord_id")));

            assertEquals(List.of(
                    new MySqlSession.ForeignKey("fk_a", List.of("ord_id", "line_no"), "t_ord_dtl",
                            List.of("ord_id", "line_no")),
                    new MySqlSession.ForeignKey("fk_z", List.of("cust_id"), "t_cust", List.of("id"))), keys);
        }

        @Test
        @DisplayName("缺列名或缺目标的约束整条丢掉，不拼一个半截外键")
        void 残缺约束整条丢() {
            List<MySqlSession.ForeignKey> keys = MySqlSession.groupForeignKeys(List.of(
                    new MySqlSession.FkPart("fk_bad", 1, null, "t_cust", "id"),
                    new MySqlSession.FkPart("fk_ok", 1, "cust_id", "t_cust", "id")));
            assertEquals(1, keys.size());
            assertEquals("fk_ok", keys.get(0).name());
        }

        @Test
        @DisplayName("★ describe 把外键放进对象级 extra.foreign_keys")
        void describe带回外键() throws SQLException {
            ObjectDetail d = describeWith(null, List.of(row("CONSTRAINT_NAME", "fk_cust", "ORDINAL_POSITION", 1,
                    "COLUMN_NAME", "cust_id", "REFERENCED_TABLE_NAME", "t_cust", "REFERENCED_COLUMN_NAME", "id")));

            assertEquals(List.of(Map.of("name", "fk_cust", "columns", List.of("cust_id"),
                            "ref_table", "t_cust", "ref_columns", List.of("id"))),
                    d.extra().get(MySqlSession.EXTRA_FOREIGN_KEYS));
            assertEquals(2, d.fields().size());
        }

        @Test
        @DisplayName("★ 读外键失败（Doris / StarRocks 这类引擎没有这张视图）：describe 照常返回，extra 里没有 foreign_keys")
        void 读不到外键不等于没有() throws SQLException {
            ObjectDetail d = describeWith(new SQLException("Unknown table 'KEY_COLUMN_USAGE'", "42S02", 1109), null);

            assertEquals(2, d.fields().size());
            assertFalse(d.extra().containsKey(MySqlSession.EXTRA_FOREIGN_KEYS), d.extra().toString());
            assertTrue(d.extra().containsKey(MySqlSession.EXTRA_UNIQUE_KEYS), "外键读失败不影响唯一键");
        }

        @Test
        @DisplayName("表确实没有外键：放空列表，与「读不到」区分开")
        void 没有外键是空列表() throws SQLException {
            assertEquals(List.of(), describeWith(null, List.of()).extra().get(MySqlSession.EXTRA_FOREIGN_KEYS));
        }

        @Test
        @DisplayName("只查同库的外键：SQL 同时限定本表所在库和被引用表所在库")
        void 只查同库() {
            assertTrue(MySqlSession.FOREIGN_KEYS_SQL.contains("TABLE_SCHEMA = ?"));
            assertTrue(MySqlSession.FOREIGN_KEYS_SQL.contains("REFERENCED_TABLE_SCHEMA = ?"));
        }
    }

    // ================================================================ 对象是否存在（契约 K-1）

    /**
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='MySqlConnectorTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `FOREIGN_KEYS_SQL`、`FkPart`、`ForeignKey`、`groupForeignKeys`、`EXTRA_FOREIGN_KEYS`。

- [ ] **Step 3: 写实现**

`MySqlSession.java`：

第 1 处，把：

```java
            "SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND NON_UNIQUE = 0";

    /** 只读探针用的表名。刻意取一个不可能与客户业务表重名的名字。 */
    private static final String PROBE_TABLE = "__jm_readonly_probe__";
```

替换为：

```java
            "SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND NON_UNIQUE = 0";

    /**
     * {@link ObjectDetail#extra()} 里存声明外键的键名（数据星图 v3 §5.2）。
     *
     * <p>与语义层读取方 {@code RelationCandidates.EXTRA_FOREIGN_KEYS} <b>刻意同名</b>，由单测钉住；不互相 import 的理由同
     * {@link #EXTRA_UNIQUE_KEYS}。它和唯一键性质相同：刷新结构后才会有，不进 {@code content_hash}，不会引起漂移。
     */
    static final String EXTRA_FOREIGN_KEYS = "foreign_keys";

    /**
     * 一张表声明的外键，只看<b>同库</b>的（被引用表在别的库里时，快照里也没有它，连不上）。
     *
     * <p>两个库名条件都写上：只写 {@code TABLE_SCHEMA} 语义上也够，但 5.7 一系的 information_schema 靠常量条件决定只打开哪张表的定义，
     * 条件越全越省。不在 SQL 里排序，理由同 {@link #UNIQUE_KEYS_SQL}。
     */
    static final String FOREIGN_KEYS_SQL =
            "SELECT CONSTRAINT_NAME, ORDINAL_POSITION, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                    + "FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND REFERENCED_TABLE_SCHEMA = ? "
                    + "AND REFERENCED_TABLE_NAME IS NOT NULL";

    /** 只读探针用的表名。刻意取一个不可能与客户业务表重名的名字。 */
    private static final String PROBE_TABLE = "__jm_readonly_probe__";
```

第 2 处，把：

```java
                throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "库中不存在名为「" + name + "」的表，或当前账号看不到它。请先用 conn_catalog 确认表名");
            }
            // 唯一键放在列之后查：表不存在时不必多打一条。
            List<UniqueKey> keys = uniqueKeys(c, name);
            List<FieldDetail> fields = new ArrayList<>(raw.size());
            for (RawColumn r : raw) {
                fields.add(new FieldDetail(r.name(), r.type(), r.nullable(), r.comment(), fieldExtra(r, keys)));
```

替换为：

```java
                throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "库中不存在名为「" + name + "」的表，或当前账号看不到它。请先用 conn_catalog 确认表名");
            }
            // 唯一键、外键放在列之后查：表不存在时不必多打两条。
            List<UniqueKey> keys = uniqueKeys(c, name);
            List<ForeignKey> foreignKeys = foreignKeys(c, name);
            List<FieldDetail> fields = new ArrayList<>(raw.size());
            for (RawColumn r : raw) {
                fields.add(new FieldDetail(r.name(), r.type(), r.nullable(), r.comment(), fieldExtra(r, keys)));
```

第 3 处，把：

```java
                //   语义层会据此认定目标列不属于任何组合键。「不知道」和「没有」必须分得开。
                extra.put(EXTRA_UNIQUE_KEYS, uniqueKeysExtra(keys));
            }
            return new ObjectDetail(name, "TABLE", null, fields, extra);
        } catch (SQLException e) {
            throw classify(e, "看结构");
```

替换为：

```java
                //   语义层会据此认定目标列不属于任何组合键。「不知道」和「没有」必须分得开。
                extra.put(EXTRA_UNIQUE_KEYS, uniqueKeysExtra(keys));
            }
            if (foreignKeys != null) {
                // 同一条纪律：读不到就不放，空列表只表示「这张表确实没声明外键」。
                extra.put(EXTRA_FOREIGN_KEYS, foreignKeysExtra(foreignKeys));
            }
            return new ObjectDetail(name, "TABLE", null, fields, extra);
        } catch (SQLException e) {
            throw classify(e, "看结构");
```

第 4 处，把：

```java
        return "组合键成员（" + cols + "），单独这一列可能重复";
    }

    /** 放进 {@link ObjectDetail#extra()} 的形状。显式拼 Map：jackson 2.11 序列化不了 record。 */
    private static List<Map<String, Object>> uniqueKeysExtra(List<UniqueKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
```

替换为：

```java
        return "组合键成员（" + cols + "），单独这一列可能重复";
    }

    /** {@link #FOREIGN_KEYS_SQL} 的一行。 */
    record FkPart(String constraint, int seq, String column, String refTable, String refColumn) {}

    /** 一条声明外键，列与被引用列按 {@code ORDINAL_POSITION} 一一对应。 */
    record ForeignKey(String name, List<String> columns, String refTable, List<String> refColumns) {}

    /**
     * 读一张表声明的外键。
     *
     * @return 按约束名排序；{@code null} = <b>没读到</b>（不是「没有外键」）。Doris / StarRocks 这类自己实现
     *         information_schema 的引擎可能根本没有这张视图，读不到不让整次 describe 失败。
     */
    private List<ForeignKey> foreignKeys(Connection c, String table) {
        try (PreparedStatement ps = c.prepareStatement(FOREIGN_KEYS_SQL)) {
            ps.setQueryTimeout(10);
            ps.setString(1, database);
            ps.setString(2, table);
            ps.setString(3, database);
            List<FkPart> parts = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parts.add(new FkPart(rs.getString("CONSTRAINT_NAME"), rs.getInt("ORDINAL_POSITION"),
                            rs.getString("COLUMN_NAME"), rs.getString("REFERENCED_TABLE_NAME"),
                            rs.getString("REFERENCED_COLUMN_NAME")));
                }
            }
            return groupForeignKeys(parts);
        } catch (SQLException e) {
            log.warn("读取外键失败，本表按「外键未知」处理 connectorId={} errorCode={} sqlState={}",
                    instance.id(), e.getErrorCode(), e.getSQLState(), e);
            return null;
        }
    }

    /** KEY_COLUMN_USAGE 的行 → 外键列表。缺列名、缺被引用表或被引用列的约束<b>整条丢掉</b>：半截外键会连错。 */
    static List<ForeignKey> groupForeignKeys(List<FkPart> parts) {
        Map<String, List<FkPart>> byConstraint = new LinkedHashMap<>();
        for (FkPart p : parts) {
            if (p == null || p.constraint() == null) {
                continue;
            }
            byConstraint.computeIfAbsent(p.constraint(), k -> new ArrayList<>()).add(p);
        }
        List<ForeignKey> out = new ArrayList<>(byConstraint.size());
        for (Map.Entry<String, List<FkPart>> e : byConstraint.entrySet()) {
            List<FkPart> ps = new ArrayList<>(e.getValue());
            if (ps.stream().anyMatch(p -> p.column() == null || p.refTable() == null || p.refColumn() == null)) {
                continue;
            }
            ps.sort(Comparator.comparingInt(FkPart::seq));
            out.add(new ForeignKey(e.getKey(), ps.stream().map(FkPart::column).toList(), ps.get(0).refTable(),
                    ps.stream().map(FkPart::refColumn).toList()));
        }
        out.sort(Comparator.comparing(ForeignKey::name));
        return out;
    }

    /** 放进 {@link ObjectDetail#extra()} 的形状，同 {@link #uniqueKeysExtra}。 */
    private static List<Map<String, Object>> foreignKeysExtra(List<ForeignKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
        for (ForeignKey k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k.name());
            m.put("columns", k.columns());
            m.put("ref_table", k.refTable());
            m.put("ref_columns", k.refColumns());
            out.add(m);
        }
        return out;
    }

    /** 放进 {@link ObjectDetail#extra()} 的形状。显式拼 Map：jackson 2.11 序列化不了 record。 */
    private static List<Map<String, Object>> uniqueKeysExtra(List<UniqueKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='MySqlConnectorTest*,MySqlSession*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done; \
  grep -c 'classname="声明外键采集"' TEST-com.jimeng.dataserver.ai.connector.impl.mysql.MySqlConnectorTest.xml
```
Expected: 每个文件 `failures=0`；MySqlConnectorTest @@N:MySqlConnectorTest@2@@ 个用例；最后一行（外键那组）是 6。

- [ ] **Step 5: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlSession.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlConnectorTest.java && \
git commit -q -F - <<'MSG'
feat(connector): 结构快照带上声明的外键（extra.foreign_keys）

describe 追加读 KEY_COLUMN_USAGE（同库），按约束分组放进对象级 extra。
与唯一键同一纪律：读不到就不放（不是空列表）、不进 content_hash、不引起漂移。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 3: 关系发现的确定性候选（data-service）

纯函数 `RelationCandidates`（spec §5.2）：声明的外键（只取单列外键）+ 通用命名规律（列名去 `id` 的词干对上别的表的表名后缀）。不写死任何表名或业务词。

规则的边界都有来历：词干至少 4 个字符（`OID`、`SOID` 说明不了什么）；对上的后缀至少 4 个字符（`org`、`dtl` 会到处误连）；本列是本表的单列唯一键不算（`PROJECTID` 在项目表里是项目自己的编号）；不连本表（试过，会冒出「项目表的 PROJECTID 指向项目表」）；最长的后缀并列就放弃（留给模型那一遍）；目标列只取单列主键或唯一的那个单列唯一键，唯一键未知就放弃。

单测用「同形夹具」：本地两个真实系统的结构快照，只留规则会看的列（以 `id` 结尾的），按固定词表换了名字。换名前后跑同一条规则，两个系统分别是 33 条和 16 条、一一对应（2026-10-01 核过），夹具里不含任何真实客户的表名。

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/RelationCandidates.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/RelationRuleFixtures.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/RelationCandidatesTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlConnectorTest.java`（外键键名两边一致）

**Interfaces:**
- Consumes: Task 2 的 `extra.foreign_keys` 形状；`SemanticTableRenderer.namedUniqueKeys(ConnectorSchema)`；`SemanticRowAssembler.parseFields`。
- Produces（Task 6 用）：
  - `record RelationCandidates.Candidate(String fromTable, String fromColumn, String toTable, String toColumn, String origin)`；
  - `RelationCandidates.of(List<ConnectorSchema>): List<Candidate>`（同一列外键优先，按起点表、起点列排序）、`foreignKeys(...)`、`namingRule(...)`；
  - 常量 `EXTRA_FOREIGN_KEYS = "foreign_keys"`、`ORIGIN_FK = "FK"`、`ORIGIN_NAME_RULE = "NAME_RULE"`、`ORIGIN_RELATION_PASS = "RELATION_PASS"`；
  - `RelationCandidates.norm(String)`（去 `_`、转小写，包内可见）。
  - 测试夹具 `RelationRuleFixtures.schema(String table, String key, List<String> columns): ConnectorSchema`（`key` 为单列主键列名，`"-"` 表示没有唯一键），Task 6 的测试也用它。

- [ ] **Step 1: 写失败的测试**

`RelationRuleFixtures.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.persistence.entity.ConnectorSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命名规则的「同形夹具」：本地两个真实系统的结构快照，按固定词表换了名字（设计文档 v3 §12）。
 *
 * <p>只保留规则会看的列（去掉 {@code _} 后以 {@code id} 结尾的列），列的顺序、表的唯一键原样保留。换名词表保证了
 * 「谁是谁的后缀」这层关系不变：换名前后跑同一条规则，两个系统分别是 33 条和 16 条，一一对应（2026-10-01 核过）。
 * 不含任何真实客户的表名。
 *
 * <p>每行是 {@code 表名|唯一键|列 列 列...}；唯一键写单列主键的列名，{@code -} 表示这张表确实没有唯一键；
 * 以 {@code + } 开头的行接着上一张表的列。
 */
final class RelationRuleFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 形同 klny_erp：19 张表。 */
    static final String ERP_A = """
            MD_BUYER|OID|OID SOID POID VERID DVERID PARENTID SUPPLIERID CLIENTID ADDRESSCOUNTRYID REGIONID
            + BUYERSUBJECTGROUPID MM_PARTNERSCHEMASID TRADEPARTNERID INTERNALORGUNITID CURRENCYID
            MD_COSTPOOL|OID|OID SOID POID VERID DVERID PARENTID CLIENTID CONTROLLINGAREAID COSTPOOLGROUPID SEGMENTID
            + RESPONSIBLEUSERID
            MD_ORGUNIT|OID|OID SOID POID VERID DVERID PARENTID LANGUAGEID COUNTRYID FIELDSTATUSVARIANTID CLIENTID
            + CURRENCYID REGIONID TIMEZONEID CREDITCONTROLAREAID FUNDMANAGEAREAID BUSINESSAREAID SUBJECTCHARTID
            + COUNTRYSUBJECTCHARTID COMPANYID PERIODTYPEID POSTPERIODTYPEID INDUSTRYID DERIVATIVERULEID ORGSTRUCTUREID
            MD_SUBJECT|OID|OID SOID POID VERID DVERID PARENTID SUBJECTGROUPID SUBJECTCHARTID GROUPSUBJECTID CLIENTID
            + FUNCTIONAREAID TRADEPARTNERID
            MD_SUPPLIER|OID|OID SOID POID VERID DVERID PARENTID COUNTRYID BUYERID CLIENTID REGIONID
            + SUPPLIERSUBJECTGROUPID PLANTID TRADEPARTNERID INTERNALORGUNITID
            QF_BUYER_ORGSETDTL|OID|OID SOID POID VERID DVERID ORGUNITID RECONSUBJECTID ASSIGNMENTRULEID
            + VCTOLERANCEGROUPID TERMOFPAYMENTID CREDITTERMOFPAYMENTID
            QF_EXT_IF17|OID|OID SOID POID VERID DVERID CONTRACTID TRANSID SYSID FRAMEWORKID DEPTID OURSIGNERID
            + UNDERTAKEID INNERCONTRACTID GENERALCONTRACTID UNDERTAKEDEPTUNITID
            QF_JOURNALDTL|OID|OID SOID POID VERID DVERID ENTRYBILLDTLID POSTINGKEYID NEWORGUNITID GLSUBJECTID BUYERID
            + SUPPLIERID ASSETID MATERIALID SPECIALGLID TRANSACTIONTYPEID CONSOLIDATIONTYPEID SUBJECTID ITEMCATEGORYID
            + COUNTRYSUBJECTID ITEMCURRENCYID ITEMFIRSTLOCALCURRENCYID ITEMSECONDLOCALCURRENCYID
            + ITEMTHIRDLOCALCURRENCYID TAXCODEID TAXSRCBILLDTLID CONTROLLINGAREAID COSTELEMENTID BUSINESSAREAID
            + PARTNERBUSINESSAREAID COSTCENTERID ORDERID COSTOBJECTID COSTPOOLID PARTNERCOSTPOOLID SEGMENTID
            + PARTNERSEGMENTID TASKNODEID ASSETSBUSINESSCLASSID SALESORDERID SALESORDERPLANDTLOID FUNCTIONALAREAID
            + PARTNERFUNCTIONALAREAID PLANTID BASEUNITID PURCHASINGID CREDITCONTROLAREAID UPDATECURRENCYID
            + CLEARINGJOURNALID CLEARINGJOURNALDTLID TERMOFPAYMENTID PAYMENTDICTIONARYID PAYMENTMETHODID
            + PAYMENTBLOCKREASONID PAYMENTCURRENRCYID REASONCODEID VALUESTRINGID CASHFLOWITEMID REFERENCEBILLID
            + REFERENCEBILLDTLID TRANSACTIONKEYID SRCSOID SRCOID VALUATIONTYPEID NETWORKID ACTIVITYID
            + PAYMENTMETHODSUPPLEMENTID HOUSEBANKID HOUSESUBJECTID TRADEPARTNERID BANKCHECKRESULTID ITEMLEDGERID
            + BUYORDERUNITID RECONSUBJECTID CLAUSEUNITID NEWFUNDMANAGEAREAID FUNDCENTERID COMMITMENTITEMID DEPARTMENTID
            + CUSTOMAUXILIARY01ID CUSTOMAUXILIARY02ID CUSTOMAUXILIARY03ID CUSTOMAUXILIARY04ID CUSTOMAUXILIARY05ID
            + CUSTOMAUXILIARY06ID CUSTOMAUXILIARY07ID CUSTOMAUXILIARY08ID CUSTOMAUXILIARY09ID CUSTOMAUXILIARY10ID
            + CUSTOMAUXILIARY11ID CUSTOMAUXILIARY12ID CUSTOMAUXILIARY13ID CUSTOMAUXILIARY14ID CUSTOMAUXILIARY15ID
            + CUSTOMAUXILIARY16ID CUSTOMAUXILIARY17ID CUSTOMAUXILIARY18ID CUSTOMAUXILIARY19ID CUSTOMAUXILIARY20ID
            + CUSTOMAUXILIARY21ID CUSTOMAUXILIARY22ID CUSTOMAUXILIARY23ID CUSTOMAUXILIARY24ID CUSTOMAUXILIARY25ID
            + CUSTOMAUXILIARY26ID CUSTOMAUXILIARY27ID CUSTOMAUXILIARY28ID CUSTOMAUXILIARY29ID CUSTOMAUXILIARY30ID
            + ITEMCLIENTID ENTRYSUMID IDENTITYID PURCHASEJOURNALID RECONTRACTID REFLOWTYPEID
            QF_SOURCELINEDTL|OID|OID SOID POID VERID DVERID JOURNALDTLID CLIENTID
            QF_SUPPLIER_ORGSETDTL|OID|OID SOID POID VERID DVERID ORGUNITID RECONSUBJECTID ASSIGNMENTRULEID
            + VCTOLERANCEGROUPID TERMOFPAYMENTID SUPPLIERSPECIFICTOLERANCEID CREDITTERMOFPAYMENTID
            QG_OBJSTATE|OID|OID SOID POID VERID DVERID SYSTEMSTATUSID PRESYSTEMSTATUSID
            QM_BUYORDERCONFIRM|OID|OID SOID POID VERID DVERID CONFIRMATIONCATEGORYID INBOUNDDELIVERYDTLID
            + INBOUNDDELIVERYBILLID
            QM_BUYORDERDTL|OID|OID SOID POID VERID DVERID MATERIALID MATERIALGROUPID PLANTID STORAGELOCATIONID
            + TAXCODEID ACCTASSIGNMENTCATID ITEMCATEGORYID DELIVERYTYPEID BUYERID SALEORGANIZATIONID
            + DISTRIBUTIONCHANNELID DIVISIONID PRICEUNITID UNITID BASEUNITID PURCHASEINFORECORDID SRCSOID SRCOID
            + RESERVATIONID SRCCONTRACTBILLDTLID COSTCENTERID COSTPOOLID FI_SUBJECTID BUSINESSAREAID ASSETID
            + SRCREQUISITIONBILLID SRCREQUISITIONBILLDTLID SRCCONTRACTBILLID VALUATIONTYPEID SHIPPINGCONDITIONID
            + LOADINGGROUPID SHIPPINGPOINTID SETCONFIRMATIONCONTROLID PS_TASKNODEID PS_NETWORKID CHECKINGGROUPID
            + CONFIRMDTLID PP_REQUIREMENTTYPEID PS_ACTIVITYID SRCSELLORDERBILLID DELIVERYBUYERID CROSSCONFIGMATERIALID
            + MATERIALCONFIGPROFILEID DEFAULT_CATEGORYTYPEID STORAGEPOINTID ORGUNITID SRCSELLORDERBILLDTLID
            + QMCONTROLKEYID DEMANDSRCPLANORDERID SRCDEMAND_MRPELEMENTID SRCDEMAND_ORDERID SRCDEMAND_ORDERBILLDTLID
            + PURCHASEREQUISITIONID PURCHASINGNUMBERID AGREEMENTID SELLORDERITEMID OVERALLLIMITCURRENCYID
            + MM_DOCUMENTTYPESID PERIODINDICATOR4SHELFID SHIPMENTSTORAGELOCATIONID PURORGANIZATIONID SUPPLIERID
            + PURCHASINGGROUPID SUPPLYINGPLANTID CLIENTID FUNCTIONALAREAID REFPURORDEROID DCDXID DCCXID
            QM_BUYORDERHEAD|OID|OID SOID POID VERID DVERID CLIENTID PURORGANIZATIONID PURCHASINGGROUPID ORGUNITID
            + PARTNERSCHEMASID SUPPLIERID SUPPLYINGPLANTID PRICINGPROCEDUREID PAYMENTTERMID CURRENCYID
            + MM_DOCUMENTTYPESID CHECKINGRULEID COUNTRYID PAYMENTDICTIONARYID DRAWERID PARENTID ZDCCGDDID ZZK_DCCGDDID
            + INSTANCEID ZUNITID
            QP_VENTURE|OID|OID SOID POID VERID DVERID PARENTID CLIENTID ORGUNITID PLANTID LOCATIONID ZZ_VENTUREID
            + STANDARDVENTUREID VENTUREID SHIELDID
            QS_SELLORDERDTL|OID|OID SOID POID VERID DVERID MATERIALID SD_ITEMCATEGORIESID PLANTID CONDITIONTYPEID
            + REASONFORREJECTIONID TAXCLASSIFICATIONID COSTPOOLID PRICEUNITID UNITID BASEUNITID PP_REQUIREMENTTYPEID
            + SD_ITEMCATEGORYGROUPSID ITEMCATEGORYHIGHERLEVELITEMID SD_ITEMCATEGORYUSAGEID SRCSOID SRCOID
            + ITEMPARTNERSCHEMASID ORGUNITID SD_MATERIALGROUPSID ITEM_SD_PRIGROUPS4BUYERSID STORAGELOCATIONID
            + ITEM_SD_INCOTERMSID PAYMENTMETHODID CONDITIONCURRENCYID SRCSALECONTRACTBILLID SRCSALECONTRACTBILLDTLID
            + SRCSALESORDERBILLID SRCSALESORDERBILLDTLID SRCSALESINVOICEBILLID SRCSALESINVOICEBILLDTLID
            + COSTINGVARIANTID ACCTASSIGNMENTCATID SHIPPINGPOINTID LOADINGGROUPID VALUATIONTYPEID PS_TASKNODEID
            + BILLINGPLANTYPEID TPSPURREQUISITIONID TPSPURORDERID MATERIALCONFIGPROFILEID BOMBILLDTLID
            + CROSSCONFIGMATERIALID ROOTMATERIALSELLORDERBILLDTLID HIGHBOMBILLDTLID DEFAULT_CATEGORYTYPEID
            + STORAGEPOINTID ORDERBOMBILLID SD_SALESDOCUMENTTYPESID SALEAREAID SOLDTOPARTYID SHIPTOPARTYID
            + SD_SALEOFFICEID SD_SALEGROUPSID REBATEAGREEMENTID SALEORGANIZATIONID DISTRIBUTIONCHANNELID CLIENTID
            + PAYERID RECEIVEINVOICEID TRANSPORTMOVETYPEID EFFECTIVETASKNODEID INTEDATAID USERMEASUREMENTID PRICETYPEID
            QS_SELLORDERHEAD|OID|OID SOID POID VERID DVERID PARENTID CLIENTID DELIVERYBLOCKID SALEORGANIZATIONID
            + DISTRIBUTIONCHANNELID DIVISIONID SD_SALEOFFICEID SD_SALEGROUPSID SD_BUYERGROUPSID SD_SALESDOCUMENTTYPESID
            + SD_SALESORDERREASONSID CURRENCYID PRICINGPROCEDUREID SD_PRICEGROUPS4BUYERSID SOLDTOPARTYID SHIPTOPARTYID
            + FI_TERMOFPAYMENTID SD_INCOTERMSID SD_REASON4BLOCKINGBILLIINGID HEAD_ORGUNITID HEAD_TAXCLASSIFICATIONID
            + SUBJECTGROUPID PAYMENTMETHODID PERIODTYPEID BUSINESSAREAID COSTCENTERID PAYERID CREDITCONTROLAREAID
            + RISKCATEGORYID SD_DOCUMENTCREDITGROUPID CREDITREPRESENTATIVEGROUPID SD_BILLINGDOCUMENTTYPEID
            + SD_DELIVERYBILLINGTYPEID PARTNERSCHEMASID SHIPPINGCONDITIONID SUBSTITUTEPROCEDUREID SD_SHIPPINGTYPEID
            + IVBILLINGDOCUMENTTYPEID REBATEAGREEMENTID COUNTRYID EXCHANGERATETYPEID LOCALCURRENCYID TCODEID
            + RECEIVEINVOICEID HEADPS_TASKNODEID BUYERSUBJECTASGGROUPID RECEIVINGPARTYID ARRIVE_SOLDTOPARTYID
            + ARRIVE_SHIPTOPARTYID INSTANCEID DELIVERYPLANID CREDITSUBJECTID RECEIPTSUPPLIERID ZZH_CONTRACTID
            RPT_BAL_2026|-|F_UNITID
            RPT_SUM_2026|-|F_UNITID
            """;

    /** 形同 erp_real：8 张表。 */
    static final String ERP_B = """
            MD_COSTPOOL|OID|OID SOID POID VERID DVERID PARENTID CLIENTID CONTROLLINGAREAID COSTPOOLGROUPID SEGMENTID
            + RESPONSIBLEUSERID
            MD_ORGUNIT|OID|OID SOID POID VERID DVERID PARENTID LANGUAGEID COUNTRYID FIELDSTATUSVARIANTID CLIENTID
            + CURRENCYID REGIONID TIMEZONEID CREDITCONTROLAREAID FUNDMANAGEAREAID BUSINESSAREAID SUBJECTCHARTID
            + COUNTRYSUBJECTCHARTID COMPANYID PERIODTYPEID POSTPERIODTYPEID INDUSTRYID DERIVATIVERULEID ORGSTRUCTUREID
            MD_SUBJECT|OID|OID SOID POID VERID DVERID PARENTID SUBJECTGROUPID SUBJECTCHARTID GROUPSUBJECTID CLIENTID
            + FUNCTIONAREAID TRADEPARTNERID
            MD_SUPPLIER|OID|OID SOID POID VERID DVERID PARENTID COUNTRYID BUYERID CLIENTID REGIONID
            + SUPPLIERSUBJECTGROUPID PLANTID TRADEPARTNERID INTERNALORGUNITID
            QF_JOURNALDTL|OID|OID SOID POID VERID DVERID ENTRYBILLDTLID POSTINGKEYID NEWORGUNITID GLSUBJECTID BUYERID
            + SUPPLIERID ASSETID MATERIALID SPECIALGLID TRANSACTIONTYPEID CONSOLIDATIONTYPEID SUBJECTID ITEMCATEGORYID
            + COUNTRYSUBJECTID ITEMCURRENCYID ITEMFIRSTLOCALCURRENCYID ITEMSECONDLOCALCURRENCYID
            + ITEMTHIRDLOCALCURRENCYID TAXCODEID TAXSRCBILLDTLID CONTROLLINGAREAID COSTELEMENTID BUSINESSAREAID
            + PARTNERBUSINESSAREAID COSTCENTERID ORDERID COSTOBJECTID COSTPOOLID PARTNERCOSTPOOLID SEGMENTID
            + PARTNERSEGMENTID TASKNODEID ASSETSBUSINESSCLASSID SALESORDERID SALESORDERPLANDTLOID FUNCTIONALAREAID
            + PARTNERFUNCTIONALAREAID PLANTID BASEUNITID PURCHASINGID CREDITCONTROLAREAID UPDATECURRENCYID
            + CLEARINGJOURNALID CLEARINGJOURNALDTLID TERMOFPAYMENTID PAYMENTDICTIONARYID PAYMENTMETHODID
            + PAYMENTBLOCKREASONID PAYMENTCURRENRCYID REASONCODEID VALUESTRINGID CASHFLOWITEMID REFERENCEBILLID
            + REFERENCEBILLDTLID TRANSACTIONKEYID SRCSOID SRCOID VALUATIONTYPEID NETWORKID ACTIVITYID
            + PAYMENTMETHODSUPPLEMENTID HOUSEBANKID HOUSESUBJECTID TRADEPARTNERID BANKCHECKRESULTID ITEMLEDGERID
            + BUYORDERUNITID RECONSUBJECTID CLAUSEUNITID NEWFUNDMANAGEAREAID FUNDCENTERID COMMITMENTITEMID DEPARTMENTID
            + CUSTOMAUXILIARY01ID CUSTOMAUXILIARY02ID CUSTOMAUXILIARY03ID CUSTOMAUXILIARY04ID CUSTOMAUXILIARY05ID
            + CUSTOMAUXILIARY06ID CUSTOMAUXILIARY07ID CUSTOMAUXILIARY08ID CUSTOMAUXILIARY09ID CUSTOMAUXILIARY10ID
            + CUSTOMAUXILIARY11ID CUSTOMAUXILIARY12ID CUSTOMAUXILIARY13ID CUSTOMAUXILIARY14ID CUSTOMAUXILIARY15ID
            + CUSTOMAUXILIARY16ID CUSTOMAUXILIARY17ID CUSTOMAUXILIARY18ID CUSTOMAUXILIARY19ID CUSTOMAUXILIARY20ID
            + CUSTOMAUXILIARY21ID CUSTOMAUXILIARY22ID CUSTOMAUXILIARY23ID CUSTOMAUXILIARY24ID CUSTOMAUXILIARY25ID
            + CUSTOMAUXILIARY26ID CUSTOMAUXILIARY27ID CUSTOMAUXILIARY28ID CUSTOMAUXILIARY29ID CUSTOMAUXILIARY30ID
            + ITEMCLIENTID ENTRYSUMID IDENTITYID PURCHASEJOURNALID RECONTRACTID REFLOWTYPEID
            QF_JOURNALDTL_EXT|OID|OID JOURNALTYPEID ORGUNITID CURRENCYID LEDGERGROUPID LEDGERID FIRSTLOCALCURRENCYID
            + SECONDLOCALCURRENCYID THIRDLOCALCURRENCYID FIRSTEXCHANGERATETYPEID SECONDEXCHANGERATETYPEID
            + THIRDEXCHANGERATETYPEID SUBJECTCHARTID COUNTRYSUBJECTCHARTID CLIENTID REVERSALREASONID REVERSALDOCUMENTID
            + COPIEDJOURNALID SRCORGUNITID CROSSORGSETJOURNALID HEADSRCSOID NUMBERRANGEID FUNDMANAGEAREAID JOURNALGUID
            + PARENTID LEDGERNUMBERRANGEID ATTACHID
            QF_SUPPLIER_ORGSETDTL|OID|OID SOID POID VERID DVERID ORGUNITID RECONSUBJECTID ASSIGNMENTRULEID
            + VCTOLERANCEGROUPID TERMOFPAYMENTID SUPPLIERSPECIFICTOLERANCEID CREDITTERMOFPAYMENTID
            QP_TASKNODE|OID|OID SOID POID VERID DVERID CLIENTID SHORTID ORGUNITID FUNCTIONALLOCATIONID VENTUREID
            + LOCATIONID USERFIELDKEYID PARENTID POSITIONID DEFINITIONID FIRSTDOWNID LEFTID RIGHTID WELL_ID OACODEID
            + PERFORMANCETYPEID
            """;

    /** {@link #ERP_A} 上命名规则应当推出的全部候选，按起点表、起点列排序。 */
    static final List<String> ERP_A_EXPECTED = List.of(
            "MD_BUYER.INTERNALORGUNITID -> MD_ORGUNIT.OID",
            "MD_BUYER.SUPPLIERID -> MD_SUPPLIER.OID",
            "MD_SUPPLIER.BUYERID -> MD_BUYER.OID",
            "MD_SUPPLIER.INTERNALORGUNITID -> MD_ORGUNIT.OID",
            "QF_BUYER_ORGSETDTL.ORGUNITID -> MD_ORGUNIT.OID",
            "QF_BUYER_ORGSETDTL.RECONSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.BUYERID -> MD_BUYER.OID",
            "QF_JOURNALDTL.COSTPOOLID -> MD_COSTPOOL.OID",
            "QF_JOURNALDTL.COUNTRYSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.GLSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.HOUSESUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.NEWORGUNITID -> MD_ORGUNIT.OID",
            "QF_JOURNALDTL.PARTNERCOSTPOOLID -> MD_COSTPOOL.OID",
            "QF_JOURNALDTL.RECONSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.SUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.SUPPLIERID -> MD_SUPPLIER.OID",
            "QF_SOURCELINEDTL.JOURNALDTLID -> QF_JOURNALDTL.OID",
            "QF_SUPPLIER_ORGSETDTL.ORGUNITID -> MD_ORGUNIT.OID",
            "QF_SUPPLIER_ORGSETDTL.RECONSUBJECTID -> MD_SUBJECT.OID",
            "QM_BUYORDERDTL.BUYERID -> MD_BUYER.OID",
            "QM_BUYORDERDTL.COSTPOOLID -> MD_COSTPOOL.OID",
            "QM_BUYORDERDTL.DELIVERYBUYERID -> MD_BUYER.OID",
            "QM_BUYORDERDTL.FI_SUBJECTID -> MD_SUBJECT.OID",
            "QM_BUYORDERDTL.ORGUNITID -> MD_ORGUNIT.OID",
            "QM_BUYORDERDTL.SUPPLIERID -> MD_SUPPLIER.OID",
            "QM_BUYORDERHEAD.ORGUNITID -> MD_ORGUNIT.OID",
            "QM_BUYORDERHEAD.SUPPLIERID -> MD_SUPPLIER.OID",
            "QP_VENTURE.ORGUNITID -> MD_ORGUNIT.OID",
            "QS_SELLORDERDTL.COSTPOOLID -> MD_COSTPOOL.OID",
            "QS_SELLORDERDTL.ORGUNITID -> MD_ORGUNIT.OID",
            "QS_SELLORDERHEAD.CREDITSUBJECTID -> MD_SUBJECT.OID",
            "QS_SELLORDERHEAD.HEAD_ORGUNITID -> MD_ORGUNIT.OID",
            "QS_SELLORDERHEAD.RECEIPTSUPPLIERID -> MD_SUPPLIER.OID");

    /** {@link #ERP_B} 上命名规则应当推出的全部候选。 */
    static final List<String> ERP_B_EXPECTED = List.of(
            "MD_SUPPLIER.INTERNALORGUNITID -> MD_ORGUNIT.OID",
            "QF_JOURNALDTL.COSTPOOLID -> MD_COSTPOOL.OID",
            "QF_JOURNALDTL.COUNTRYSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.GLSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.HOUSESUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.NEWORGUNITID -> MD_ORGUNIT.OID",
            "QF_JOURNALDTL.PARTNERCOSTPOOLID -> MD_COSTPOOL.OID",
            "QF_JOURNALDTL.RECONSUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.SUBJECTID -> MD_SUBJECT.OID",
            "QF_JOURNALDTL.SUPPLIERID -> MD_SUPPLIER.OID",
            "QF_JOURNALDTL.TASKNODEID -> QP_TASKNODE.OID",
            "QF_JOURNALDTL_EXT.ORGUNITID -> MD_ORGUNIT.OID",
            "QF_JOURNALDTL_EXT.SRCORGUNITID -> MD_ORGUNIT.OID",
            "QF_SUPPLIER_ORGSETDTL.ORGUNITID -> MD_ORGUNIT.OID",
            "QF_SUPPLIER_ORGSETDTL.RECONSUBJECTID -> MD_SUBJECT.OID",
            "QP_TASKNODE.ORGUNITID -> MD_ORGUNIT.OID");

    private RelationRuleFixtures() {
    }

    static List<ConnectorSchema> parse(String text) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        String current = null;
        for (String raw : text.strip().split("\n")) {
            String line = raw.strip();
            if (line.startsWith("+ ")) {
                columns.get(current).addAll(List.of(line.substring(2).trim().split(" ")));
                continue;
            }
            String[] parts = line.split("\\|", 3);
            current = parts[0];
            keys.put(current, parts[1]);
            columns.put(current, new ArrayList<>(List.of(parts[2].trim().split(" "))));
        }
        List<ConnectorSchema> out = new ArrayList<>();
        columns.forEach((table, cols) -> out.add(schema(table, keys.get(table), cols)));
        return out;
    }

    static ConnectorSchema schema(String table, String key, List<String> cols) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String c : cols) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", c);
            f.put("type", "decimal(19,0)");
            f.put("nullable", true);
            f.put("comment", "");
            fields.add(f);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", table);
        detail.put("type", "TABLE");
        detail.put("fields", fields);
        detail.put("extra", Map.of("unique_keys", "-".equals(key) ? List.of()
                : List.of(Map.of("name", "PRIMARY", "primary", true, "columns", List.of(key)))));
        ConnectorSchema row = new ConnectorSchema();
        row.setConnectorId(1L);
        row.setObjectType("TABLE");
        row.setObjectName(table);
        row.setDetailJson(json(detail));
        return row;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`RelationCandidatesTest.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.service.RelationCandidates.Candidate;
import com.jimeng.persistence.entity.ConnectorSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jimeng.dataserver.ai.connector.service.RelationRuleFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationCandidatesTest {

    private static List<String> lines(List<Candidate> candidates) {
        return candidates.stream()
                .map(c -> c.fromTable() + "." + c.fromColumn() + " -> " + c.toTable() + "." + c.toColumn())
                .toList();
    }

    /** 一张表：{@code key} 为单列主键列名，{@code "-"} = 确实没有唯一键，{@code null} = 唯一键未知。 */
    private static ConnectorSchema table(String name, String key, String... columns) {
        ConnectorSchema row = RelationRuleFixtures.schema(name, key == null ? "-" : key, List.of(columns));
        if (key == null) {
            row.setDetailJson(row.getDetailJson().replace(",\"extra\":{\"unique_keys\":[]}", ""));
        }
        return row;
    }

    @Nested
    @DisplayName("同形夹具：本地两个系统换了名字的快照")
    class SameShape {

        @Test
        @DisplayName("★ 形同 klny_erp：恰好 33 条，一条不多一条不少")
        void erpA() {
            assertEquals(RelationRuleFixtures.ERP_A_EXPECTED,
                    lines(RelationCandidates.namingRule(parse(RelationRuleFixtures.ERP_A))));
        }

        @Test
        @DisplayName("★ 形同 erp_real：恰好 16 条")
        void erpB() {
            assertEquals(RelationRuleFixtures.ERP_B_EXPECTED,
                    lines(RelationCandidates.namingRule(parse(RelationRuleFixtures.ERP_B))));
        }

        @Test
        @DisplayName("来源都标 NAME_RULE")
        void origin() {
            assertTrue(RelationCandidates.namingRule(parse(RelationRuleFixtures.ERP_A)).stream()
                    .allMatch(c -> RelationCandidates.ORIGIN_NAME_RULE.equals(c.origin())));
        }
    }

    @Nested
    @DisplayName("命名规则的边界")
    class NamingRule {

        @Test
        @DisplayName("词干等于后缀、或以后缀结尾，都算；取对上的后缀最长的那张表")
        void 等于或结尾() {
            List<ConnectorSchema> s = List.of(
                    table("M_VENDOR", "OID", "OID"),
                    table("M_RECEIPTVENDOR", "OID", "OID"),
                    table("T_ORDER", "OID", "OID", "VENDORID", "RECEIPTVENDORID", "HEAD_VENDORID"));
            assertEquals(List.of(
                    "T_ORDER.HEAD_VENDORID -> M_VENDOR.OID",
                    "T_ORDER.RECEIPTVENDORID -> M_RECEIPTVENDOR.OID",
                    "T_ORDER.VENDORID -> M_VENDOR.OID"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("最长的对上了两张表：放弃，留给模型那一遍")
        void 并列放弃() {
            List<ConnectorSchema> s = List.of(
                    table("A_ITEM", "OID", "OID"),
                    table("B_ITEM", "OID", "OID"),
                    table("T_ORDER", "OID", "OID", "ITEMID"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("本列是本表的单列唯一键：不算（它是这张表自己的编号）")
        void 自身唯一键不算() {
            List<ConnectorSchema> s = List.of(
                    table("T_ORDER", "OID", "OID"),
                    table("ORDER_EXT", "ORDERID", "ORDERID", "MEMO"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("只连别的表：只对得上本表时不提候选")
        void 不连本表() {
            List<ConnectorSchema> s = List.of(
                    table("PS_PROJECT", "OID", "OID", "PROJECTID", "STANDARDPROJECTID"),
                    table("T_TASK", "OID", "OID"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("后缀不足 4 个字符不算：org 不行，xorg 可以")
        void 短后缀() {
            List<ConnectorSchema> s = List.of(
                    table("X_ORG", "OID", "OID"),
                    table("T_DEPT", "OID", "OID", "PARENTORGID", "XORGID"));
            assertEquals(List.of("T_DEPT.XORGID -> X_ORG.OID"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("词干不足 4 个字符不算：OID / SOID / POID 什么都说明不了")
        void 短词干() {
            List<ConnectorSchema> s = List.of(
                    table("T_SO", "OID", "OID"),
                    table("T_ITEM", "OID", "OID", "SOID", "POID"));
            assertEquals(List.of(), RelationCandidates.namingRule(s));
        }

        @Test
        @DisplayName("复数表名：orders 按 order 比；address 本身以 s 结尾，照样按 address 比")
        void 复数() {
            List<ConnectorSchema> s = List.of(
                    table("T_ORDERS", "ID", "ID"),
                    table("T_ADDRESS", "ID", "ID"),
                    table("T_ITEM", "ID", "ID", "ORDER_ID", "ADDRESS_ID"));
            assertEquals(List.of(
                    "T_ITEM.ADDRESS_ID -> T_ADDRESS.ID",
                    "T_ITEM.ORDER_ID -> T_ORDERS.ID"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("目标列：没有主键取唯一的单列唯一键；两个单列唯一键、没有唯一键、唯一键未知都放弃")
        void 目标列() {
            ConnectorSchema uniqueOnly = RelationRuleFixtures.schema("M_PRODUCT", "-", List.of("PRODUCT_CODE", "NAME"));
            uniqueOnly.setDetailJson(uniqueOnly.getDetailJson().replace("\"unique_keys\":[]",
                    "\"unique_keys\":[{\"name\":\"uk_code\",\"primary\":false,\"columns\":[\"PRODUCT_CODE\"]}]"));
            ConnectorSchema twoUnique = RelationRuleFixtures.schema("M_OUTLET", "-", List.of("CODE", "NO"));
            twoUnique.setDetailJson(twoUnique.getDetailJson().replace("\"unique_keys\":[]",
                    "\"unique_keys\":[{\"name\":\"uk_a\",\"primary\":false,\"columns\":[\"CODE\"]},"
                            + "{\"name\":\"uk_b\",\"primary\":false,\"columns\":[\"NO\"]}]"));
            List<ConnectorSchema> s = List.of(uniqueOnly, twoUnique,
                    table("M_PLANT", "-", "NAME"),
                    table("M_STORE", null, "ID"),
                    table("T_LINE", "OID", "OID", "PRODUCTID", "OUTLETID", "PLANTID", "STOREID"));
            assertEquals(List.of("T_LINE.PRODUCTID -> M_PRODUCT.PRODUCT_CODE"), lines(RelationCandidates.namingRule(s)));
        }

        @Test
        @DisplayName("表名后缀：按 _ 切开逐段取后缀，去掉 _、转小写")
        void 后缀() {
            assertEquals(List.of("customercpycodedtl", "cpycodedtl", "eficustomercpycodedtl").stream().sorted().toList(),
                    RelationCandidates.suffixes("EFI_CUSTOMER_CPYCODEDTL").stream().sorted().toList());
        }
    }

    @Nested
    @DisplayName("声明的外键")
    class ForeignKeys {

        private ConnectorSchema withForeignKeys(ConnectorSchema row, String foreignKeysJson) {
            row.setDetailJson(row.getDetailJson().replace("\"unique_keys\":", "\"foreign_keys\":" + foreignKeysJson
                    + ",\"unique_keys\":"));
            return row;
        }

        @Test
        @DisplayName("单列外键进候选、来源 FK；组合外键、指向快照外的表的外键不进")
        void 单列外键() {
            List<ConnectorSchema> s = List.of(
                    table("t_cust", "id", "id"),
                    withForeignKeys(table("t_ord", "id", "id", "buyer", "a", "b", "x"), "["
                            + "{\"name\":\"fk_buyer\",\"columns\":[\"buyer\"],\"ref_table\":\"t_cust\",\"ref_columns\":[\"id\"]},"
                            + "{\"name\":\"fk_ab\",\"columns\":[\"a\",\"b\"],\"ref_table\":\"t_cust\",\"ref_columns\":[\"id\",\"id\"]},"
                            + "{\"name\":\"fk_x\",\"columns\":[\"x\"],\"ref_table\":\"t_gone\",\"ref_columns\":[\"id\"]}]"));

            List<Candidate> out = RelationCandidates.foreignKeys(s);

            assertEquals(List.of("t_ord.buyer -> t_cust.id"), lines(out));
            assertEquals(RelationCandidates.ORIGIN_FK, out.get(0).origin());
        }

        @Test
        @DisplayName("★ 同一列既有外键又有命名规律：外键优先")
        void 外键优先() {
            List<ConnectorSchema> s = List.of(
                    table("M_VENDOR", "OID", "OID"),
                    table("M_PARTNER", "OID", "OID"),
                    withForeignKeys(table("T_ORDER", "OID", "OID", "VENDORID"),
                            "[{\"name\":\"fk\",\"columns\":[\"VENDORID\"],\"ref_table\":\"M_PARTNER\",\"ref_columns\":[\"OID\"]}]"));

            List<Candidate> out = RelationCandidates.of(s);

            assertEquals(List.of("T_ORDER.VENDORID -> M_PARTNER.OID"), lines(out));
            assertEquals(RelationCandidates.ORIGIN_FK, out.get(0).origin());
        }

        @Test
        @DisplayName("快照里没有外键信息（旧快照、读不到）：没有外键候选，命名规则照常")
        void 没有外键信息() {
            List<ConnectorSchema> s = new ArrayList<>(List.of(
                    table("M_VENDOR", "OID", "OID"),
                    table("T_ORDER", "OID", "OID", "VENDORID")));
            assertEquals(List.of(), RelationCandidates.foreignKeys(s));
            assertEquals(List.of("T_ORDER.VENDORID -> M_VENDOR.OID"), lines(RelationCandidates.of(s)));
        }
    }

    @Test
    @DisplayName("单张表的快照 JSON 坏了：只有它没有候选，别的表照常")
    void 坏快照() {
        ConnectorSchema broken = table("T_BAD", "OID", "OID", "VENDORID");
        broken.setDetailJson("{not json");
        List<ConnectorSchema> s = List.of(broken,
                table("M_VENDOR", "OID", "OID"),
                table("T_ORDER", "OID", "OID", "VENDORID"));
        assertEquals(List.of("T_ORDER.VENDORID -> M_VENDOR.OID"), lines(RelationCandidates.of(s)));
    }

    @Test
    @DisplayName("输出顺序确定：与快照行的顺序无关")
    void 顺序确定() {
        List<ConnectorSchema> s = parse(RelationRuleFixtures.ERP_B);
        List<ConnectorSchema> reversed = new ArrayList<>(s);
        Collections.reverse(reversed);
        assertEquals(lines(RelationCandidates.of(s)), lines(RelationCandidates.of(reversed)));
    }
}
```

`MySqlConnectorTest.java`：

第 1 处，把：

```java
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import org.junit.jupiter.api.AfterEach;
```

替换为：

```java
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.RelationCandidates;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import org.junit.jupiter.api.AfterEach;
```

第 2 处，把：

```java
            assertEquals(List.of(), describeWith(null, List.of()).extra().get(MySqlSession.EXTRA_FOREIGN_KEYS));
        }

        @Test
        @DisplayName("只查同库的外键：SQL 同时限定本表所在库和被引用表所在库")
        void 只查同库() {
```

替换为：

```java
            assertEquals(List.of(), describeWith(null, List.of()).extra().get(MySqlSession.EXTRA_FOREIGN_KEYS));
        }

        /** 两边互不 import，靠这一条保证写进快照的键名和关系发现读的是同一个。 */
        @Test
        @DisplayName("快照里的键名与关系发现读取的键名一致")
        void 键名两边一致() {
            assertEquals(RelationCandidates.EXTRA_FOREIGN_KEYS, MySqlSession.EXTRA_FOREIGN_KEYS);
        }

        @Test
        @DisplayName("只查同库的外键：SQL 同时限定本表所在库和被引用表所在库")
        void 只查同库() {
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='RelationCandidatesTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `RelationCandidates`。

- [ ] **Step 3: 写实现**

`RelationCandidates.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer.NamedKey;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.persistence.entity.ConnectorSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 关系发现的确定性候选（数据星图设计 v3 §5.2）：声明的外键 + 通用命名规律。
 *
 * <p>纯函数：不查库、不调模型，同样的快照永远得到同样的候选（顺序也一样）。<b>不写死任何表名或业务词</b>：
 * 规则只看「列名去掉 {@code id} 后的词干」和「别的表名的后缀」之间的字面关系，换一个行业的库照样成立。
 *
 * <p>2026-10-01 用本地两个系统的真实快照实测：一个库推出 33 条、没有一条多余，另一个推出 16 条。
 * 单测里的同形夹具就是这两个库按固定词表换了名字的版本，见 {@code RelationRuleFixtures}。
 */
public final class RelationCandidates {

    /** 快照 {@code detail_json.extra} 里声明外键的键名。与 {@code MySqlSession.EXTRA_FOREIGN_KEYS} 刻意同名，由单测钉住。 */
    public static final String EXTRA_FOREIGN_KEYS = "foreign_keys";

    /** JOIN 行 {@code detail_json.origin} 的取值：这条关系是谁提出来的。 */
    public static final String ORIGIN_FK = "FK";
    public static final String ORIGIN_NAME_RULE = "NAME_RULE";
    public static final String ORIGIN_RELATION_PASS = "RELATION_PASS";

    /** 词干（列名去掉 {@code id} 之后）至少几个字符：{@code OID}、{@code SOID} 这种短名字什么都说明不了。 */
    static final int MIN_STEM = 4;
    /** 对上的表名后缀至少几个字符：{@code org}、{@code dtl} 这种短后缀会到处误连。 */
    static final int MIN_SUFFIX = 4;

    private static final Comparator<Candidate> ORDER = Comparator.comparing(Candidate::fromTable)
            .thenComparing(Candidate::fromColumn);

    private RelationCandidates() {
    }

    /** 一条候选关系。{@code origin} 是 {@link #ORIGIN_FK} 或 {@link #ORIGIN_NAME_RULE}。 */
    public record Candidate(String fromTable, String fromColumn, String toTable, String toColumn, String origin) {
    }

    /**
     * 全部确定性候选：同一列既有声明外键又有命名规律时，<b>外键优先</b>（客户自己写下的约束压过名字像）。
     * 按起点表、起点列排序。
     */
    public static List<Candidate> of(List<ConnectorSchema> schemas) {
        Map<String, Candidate> byColumn = new LinkedHashMap<>();
        for (Candidate c : foreignKeys(schemas)) {
            byColumn.putIfAbsent(columnKey(c.fromTable(), c.fromColumn()), c);
        }
        for (Candidate c : namingRule(schemas)) {
            byColumn.putIfAbsent(columnKey(c.fromTable(), c.fromColumn()), c);
        }
        List<Candidate> out = new ArrayList<>(byColumn.values());
        out.sort(ORDER);
        return out;
    }

    /**
     * 声明的外键，只取单列外键。组合外键写不成一条「这一列指向那一列」的关系，交给语义层自己的组合键处理；
     * 被引用的表或列不在快照里（快照在 200 个对象处截断过）的丢掉。
     */
    public static List<Candidate> foreignKeys(List<ConnectorSchema> schemas) {
        List<Table> tables = tables(schemas);
        Map<String, Table> byName = new LinkedHashMap<>();
        for (Table t : tables) {
            byName.putIfAbsent(fold(t.name()), t);
        }
        List<Candidate> out = new ArrayList<>();
        for (Table t : tables) {
            for (Map<String, Object> fk : t.foreignKeys()) {
                List<String> cols = strings(fk.get("columns"));
                List<String> refCols = strings(fk.get("ref_columns"));
                Object refTable = fk.get("ref_table");
                if (cols.size() != 1 || refCols.size() != 1 || refTable == null) {
                    continue;
                }
                Table target = byName.get(fold(String.valueOf(refTable)));
                String from = t.column(cols.get(0));
                String to = target == null ? null : target.column(refCols.get(0));
                if (from != null && to != null) {
                    out.add(new Candidate(t.name(), from, target.name(), to, ORIGIN_FK));
                }
            }
        }
        out.sort(ORDER);
        return out;
    }

    /**
     * 命名规律。同时满足以下条件的列提出一条候选（设计文档 §5.2 第 2 条）：
     * <ol>
     *   <li>列名去掉 {@code _}、转小写后以 {@code id} 结尾，去掉这个 {@code id} 的词干至少 {@value #MIN_STEM} 个字符；</li>
     *   <li>这一列本身不是本表的单列唯一键（{@code PROJECTID} 在项目表里是项目自己的编号，不是指向谁）；</li>
     *   <li>另一张表（不含本表）的某个表名后缀等于词干、或是词干的结尾，后缀至少 {@value #MIN_SUFFIX} 个字符；
     *       以 {@code s} 结尾的后缀，去掉 {@code s} 的形式也参与比较；</li>
     *   <li>多张表都对得上时取对上的后缀最长的那张，<b>最长的并列就放弃</b>，留给模型那一遍；</li>
     *   <li>目标列取那张表的单列主键；没有主键时取它唯一的那个单列唯一键；都没有（含唯一键未知）就放弃。</li>
     * </ol>
     * 规则不连本表：试过允许连本表，会冒出「项目表的 PROJECTID 指向项目表」这类误报。自关联只来自语义层和模型那一遍。
     */
    public static List<Candidate> namingRule(List<ConnectorSchema> schemas) {
        List<Table> tables = tables(schemas);
        List<Candidate> out = new ArrayList<>();
        for (Table t : tables) {
            Set<String> ownKeys = singleColumnKeys(t);
            for (String column : t.columns()) {
                String n = norm(column);
                if (!n.endsWith("id") || n.length() - 2 < MIN_STEM || ownKeys.contains(fold(column))) {
                    continue;
                }
                String stem = n.substring(0, n.length() - 2);
                Table best = null;
                int bestLength = 0;
                boolean tie = false;
                for (Table other : tables) {
                    if (other == t) {
                        continue;
                    }
                    int length = longestMatch(stem, other.suffixes());
                    if (length == 0) {
                        continue;
                    }
                    if (length > bestLength) {
                        best = other;
                        bestLength = length;
                        tie = false;
                    } else if (length == bestLength && other != best) {
                        tie = true;
                    }
                }
                if (best == null || tie) {
                    continue;
                }
                String target = targetColumn(best);
                if (target != null) {
                    out.add(new Candidate(t.name(), column, best.name(), target, ORIGIN_NAME_RULE));
                }
            }
        }
        out.sort(ORDER);
        return out;
    }

    /** 表名按 {@code _} 切开后的每个后缀（去掉 {@code _}、转小写），不足 {@value #MIN_SUFFIX} 个字符的不要。 */
    static Set<String> suffixes(String table) {
        String[] parts = table.split("_", -1);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < parts.length; i++) {
            String suffix = norm(String.join("_", Arrays.copyOfRange(parts, i, parts.length)));
            if (suffix.length() >= MIN_SUFFIX) {
                out.add(suffix);
            }
            if (suffix.endsWith("s") && suffix.length() - 1 >= MIN_SUFFIX) {
                out.add(suffix.substring(0, suffix.length() - 1));
            }
        }
        return out;
    }

    private static int longestMatch(String stem, Set<String> suffixes) {
        int best = 0;
        for (String s : suffixes) {
            if ((stem.equals(s) || stem.endsWith(s)) && s.length() > best) {
                best = s.length();
            }
        }
        return best;
    }

    /** 单列主键；没有主键时取唯一的那个单列唯一键；都没有、有好几个、或唯一键未知时为 {@code null}。 */
    private static String targetColumn(Table t) {
        if (t.keys() == null) {
            return null;
        }
        List<String> singles = new ArrayList<>();
        for (NamedKey k : t.keys()) {
            if (k.columns().size() != 1) {
                continue;
            }
            if (k.primary()) {
                return k.columns().get(0);
            }
            singles.add(k.columns().get(0));
        }
        return singles.size() == 1 ? singles.get(0) : null;
    }

    /** 本表的单列唯一键（折叠过大小写）。唯一键未知时为空：宁可多提一条交给采样核对，也不凭空认定它是主键。 */
    private static Set<String> singleColumnKeys(Table t) {
        Set<String> out = new HashSet<>();
        if (t.keys() != null) {
            for (NamedKey k : t.keys()) {
                if (k.columns().size() == 1) {
                    out.add(fold(k.columns().get(0)));
                }
            }
        }
        return out;
    }

    /** 列名 / 表名的比较形态：去掉 {@code _}、转小写。 */
    static String norm(String s) {
        return s.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String columnKey(String table, String column) {
        return fold(table) + '\u0001' + fold(column);
    }

    // ------------------------------------------------------------------ 快照解析

    private static List<Table> tables(List<ConnectorSchema> schemas) {
        List<ConnectorSchema> rows = (schemas == null ? List.<ConnectorSchema>of() : schemas).stream()
                .filter(r -> r != null && r.getObjectName() != null)
                .sorted(Comparator.comparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(rows);
        List<Table> out = new ArrayList<>(rows.size());
        for (ConnectorSchema r : rows) {
            Map<String, FieldDetail> cols = fields.getOrDefault(r.getObjectName(), Map.of());
            if (cols.isEmpty()) {
                continue;
            }
            out.add(new Table(r.getObjectName(), List.copyOf(cols.keySet()),
                    SemanticTableRenderer.namedUniqueKeys(r), foreignKeysOf(r), suffixes(r.getObjectName())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> foreignKeysOf(ConnectorSchema row) {
        if (row.getDetailJson() == null || row.getDetailJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
            if (!(m.get("extra") instanceof Map<?, ?> extra) || !(extra.get(EXTRA_FOREIGN_KEYS) instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> fk) {
                    out.add((Map<String, Object>) fk);
                }
            }
            return out;
        } catch (Exception e) {
            // 单张表的快照坏了只让它没有外键候选，不影响别的表。
            return List.of();
        }
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (e == null) {
                return List.of();
            }
            out.add(String.valueOf(e));
        }
        return out;
    }

    /**
     * @param keys {@code null} = 唯一键未知（旧快照没有 {@code extra.unique_keys}）
     */
    private record Table(String name, List<String> columns, List<NamedKey> keys,
                         List<Map<String, Object>> foreignKeys, Set<String> suffixes) {

        /** 按快照里的写法取列名；大小写不一致时按折叠形态兜底。找不到为 {@code null}。 */
        String column(String name) {
            for (String c : columns) {
                if (c.equals(name)) {
                    return c;
                }
            }
            for (String c : columns) {
                if (fold(c).equals(fold(name))) {
                    return c;
                }
            }
            return null;
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='RelationCandidatesTest*,MySqlConnectorTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 两个文件都 `failures=0`；RelationCandidatesTest 17 个用例（其中 `erpA`、`erpB` 分别断言恰好 33 条、16 条）。

- [ ] **Step 5: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/RelationCandidates.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/RelationCandidatesTest.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/RelationRuleFixtures.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlConnectorTest.java && \
git commit -q -F - <<'MSG'
feat(connector): 关系发现的确定性候选——声明外键与通用命名规律

纯函数，不写死任何表名或业务词。同形夹具（本地两个系统换名后的快照）上恰好 33 条、16 条，
一条不多一条不少；并列放弃、本列唯一键不算、不连本表、短后缀不算、唯一键未知不给目标列。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 4: 关系发现的写入规则；业务方确认关系时保留结构键（data-service）

两件事都在 `ConnectorSemanticService` 里：

1. **`mergeDiscoveredJoins`**（spec §5.4）：逐列写 JOIN 行。这一列没有关系就新增；人工 / 导入的行、带业务方结论的行、已核对通过的行一律不动；推断的行指向非唯一键（或终点已不在快照里）、也没核对通过，外键 / 命名规则的候选可以**替换**它，模型的候选不替换任何东西。为什么要能替换：语义层每一列只能有一条关系，klny_erp 的 `EFI_VENDOR_CPYCODEDTL.COMPANYCODEID` 被一条指向兄弟明细表的推断占着，只补空缺的话对的那条永远写不进去。替换走和 `upsertInferred` 一样的写回纪律（`unchangedSince`），整行显式 `set`，`detail_json.replaced_target` 留下原终点。
2. **`annotateJoin` 保留结构键**（spec §5.5，v2 审查 #5）：业务方确认一条关系，确认的是「这两列是同一个东西」，不是「这条关系没有条件」。覆盖同一对端时，把旧行的 `join_kind` / `discriminator_column` / `composite_columns` 带过去（调用方这次给了的不覆盖）；换了对端不带。

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticService.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticDiscoveredJoinTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticHumanWriteTest.java`

**Interfaces:**
- Consumes: Task 3 的 `RelationCandidates.ORIGIN_*`。
- Produces（Task 6 用）：
  - `ConnectorSemanticService.mergeDiscoveredJoins(Long connectorId, List<ConnectorSemantic> rows, Map<String, Set<String>> singleUniqueColumns): DiscoveryWrite`——`rows` 是经 `SemanticRowAssembler` 过滤、`detail_json.origin` 标好来源的 JOIN 行；`singleUniqueColumns` 的表名、列名都按 `SemanticRowAssembler.fold` 折叠，表不在里面 = 终点已不在快照里；
  - `public record DiscoveryWrite(int inserted, int replaced, int kept, int conflicts, int concurrentSkips, List<String> touchedObjects)`，`touchedObjects` 是新增或替换了关系的起点表（原样写法）；
  - `HumanWrite.carryOver: List<String>`，`ConnectorSemanticService.JOIN_STRUCTURE_KEYS`。

- [ ] **Step 1: 先写 `annotateJoin` 的失败测试**

`ConnectorSemanticHumanWriteTest.java`：

第 1 处，把：

```java
        }
    }

    // ================================================================ 口径的列集合锚点

    @Nested
```

替换为：

```java
        }
    }

    // ================================================================ 确认关系时保留结构键（数据星图设计 v3 §5.5）

    /**
     * 业务方确认一条关系，确认的是「这两列是同一个东西」，不是「这条关系没有条件」。多态关系的判别列、组合键的成员列
     * 是采样核对落下的结构；确认时把它们丢了，星图会把一条多态关系画成无条件的实线，Agent 也少了判别条件。
     */
    @Nested
    @DisplayName("确认关系时保留结构键")
    class JoinStructure {

        private ConnectorSemantic polymorphicJoin() {
            ConnectorSemantic j = new ConnectorSemantic();
            j.setId(8L);
            j.setTenantId(TENANT);
            j.setConnectorId(CONN_ID);
            j.setScope(ConnectorSemanticService.SCOPE_JOIN);
            j.setObjectName("t_ord");
            j.setFieldName("cust_id");
            j.setTerm("");
            j.setGloss("关联 t_cust.id");
            j.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            j.setStatus(ConnectorSemanticService.ST_DRAFT);
            j.setVerified(ConnectorSemanticService.V_UNDECIDABLE);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put(ConnectorSemanticService.KEY_TO_OBJECT, "t_cust");
            d.put(ConnectorSemanticService.KEY_TO_COLUMN, "id");
            d.put("join_kind", "POLYMORPHIC");
            d.put("discriminator_column", "owner_type");
            d.put("composite_columns", List.of("cust_id", "owner_type"));
            d.put("containment", 0.4);
            j.setDetailJson(json(d));
            return j;
        }

        @Test
        @DisplayName("★ 确认同一条关系：join_kind / discriminator_column / composite_columns 原样留下")
        void 同一对端保留() {
            when(semanticMapper.selectOne(any())).thenReturn(polymorphicJoin());

            ConnectorSemantic out = service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_cust", "id",
                    true, "按客户类型区分", "h", "u9", "张三", null);

            Map<String, Object> d = detailOf(out);
            assertEquals("POLYMORPHIC", d.get("join_kind"));
            assertEquals("owner_type", d.get("discriminator_column"));
            assertEquals(List.of("cust_id", "owner_type"), d.get("composite_columns"));
            assertEquals(ConnectorSemanticService.HV_RELATED, d.get(ConnectorSemanticService.KEY_HUMAN_VERDICT));
            assertNull(d.get("containment"), "只带结构键，采样数字不跟着人的确认走");
        }

        @Test
        @DisplayName("改挂到别的对端：旧关系的结构键不带过去")
        void 换了对端不带() {
            when(semanticMapper.selectOne(any())).thenReturn(polymorphicJoin());

            ConnectorSemantic out = service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_member", "uid",
                    true, "其实是会员", "h", "u9", "张三", null);

            assertNull(detailOf(out).get("join_kind"));
            assertNull(detailOf(out).get("discriminator_column"));
        }
    }

    // ================================================================ 口径的列集合锚点

    @Nested
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='ConnectorSemanticHumanWriteTest*' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run:|expected'
```
Expected: `同一对端保留` 失败，`expected: <POLYMORPHIC> but was: <null>`；`换了对端不带` 通过（现在本来就什么都不带）。

- [ ] **Step 3: 写 JOIN 写入的测试**

`ConnectorSemanticDiscoveredJoinTest.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关系发现写 JOIN 行（数据星图设计 v3 §5.4）：逐列按「现有的行 × 候选来源」那张表处理。
 */
class ConnectorSemanticDiscoveredJoinTest {

    private static final Long CONN_ID = 100L;

    /** 快照里的单列唯一键：t_cust.id、t_cust.code 各自唯一；t_ord.id 唯一。 */
    private static final Map<String, Set<String>> UNIQUE = Map.of(
            "t_cust", Set.of("id", "code"),
            "t_ord", Set.of("id"),
            "t_item", Set.of("id"));

    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId("t1");
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    private static ConnectorSemantic candidate(String from, String column, String to, String toColumn, String origin) {
        ConnectorSemantic row = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, from, column, "");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to_object", to);
        detail.put("to_column", toColumn);
        detail.put("basis", "未经数据验证");
        detail.put("origin", origin);
        row.setDetailJson(SemanticRowAssembler.toJson(detail));
        row.setGloss("关联 " + to + "." + toColumn + "。本条未经数据验证，join 前建议先看该列的取值分布。");
        row.setEvidence(ConnectorSemanticService.EV_NAME);
        row.setConfidence(80);
        row.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
        row.setAnchorHash("new-anchor");
        return row;
    }

    private static ConnectorSemantic existing(String from, String column, String to, String toColumn,
                                              String source, String verified, Map<String, Object> more) {
        ConnectorSemantic row = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, from, column, "");
        row.setId(9L);
        row.setTenantId("t1");
        row.setConnectorId(CONN_ID);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to_object", to);
        detail.put("to_column", toColumn);
        detail.putAll(more);
        row.setDetailJson(SemanticRowAssembler.toJson(detail));
        row.setSource(source);
        row.setVerified(verified);
        row.setGloss("旧的说明");
        row.setAnchorHash("old-anchor");
        return row;
    }

    private ConnectorSemanticService.DiscoveryWrite write(List<ConnectorSemantic> existingRows,
                                                          ConnectorSemantic... candidates) {
        when(semanticMapper.selectList(any())).thenReturn(existingRows);
        return service.mergeDiscoveredJoins(CONN_ID, List.of(candidates), UNIQUE);
    }

    private LambdaUpdateWrapper<ConnectorSemantic> capturedUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticMapper).update(any(), cap.capture());
        return cap.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailIn(LambdaUpdateWrapper<ConnectorSemantic> w) throws Exception {
        for (Object v : w.getParamNameValuePairs().values()) {
            if (v instanceof String s && s.contains("to_object")) {
                return CommonUtil.getObjectMapper().readValue(s, Map.class);
            }
        }
        throw new AssertionError("set 子句里没有 detail_json：" + w.getSqlSet());
    }

    @Test
    @DisplayName("这一列还没有关系：新增一条推断行（DRAFT / NONE，带租户与连接）")
    void 新增() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
        verify(semanticMapper).insert(cap.capture());
        ConnectorSemantic row = cap.getValue();
        assertEquals("t1", row.getTenantId());
        assertEquals(CONN_ID, row.getConnectorId());
        assertEquals(ConnectorSemanticService.SOURCE_INFERRED, row.getSource());
        assertEquals(ConnectorSemanticService.ST_DRAFT, row.getStatus());
        assertEquals(ConnectorSemanticService.V_NONE, row.getVerified());
        assertEquals(1, w.inserted());
        assertEquals(List.of("t_ord"), w.touchedObjects());
    }

    @Test
    @DisplayName("模型那一遍的候选，这一列还没有关系时同样新增")
    void 模型候选新增() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(),
                candidate("t_item", "so_id", "t_ord", "id", RelationCandidates.ORIGIN_RELATION_PASS));
        assertEquals(1, w.inserted());
    }

    @Test
    @DisplayName("人工或导入的行：一个字不碰，哪怕它指向的不是唯一键")
    void 人工行不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_cust", "name", ConnectorSemanticService.SOURCE_HUMAN, "NONE",
                                Map.of("human_verdict", "RELATED")),
                        existing("t_ord", "shop_id", "t_cust", "name", ConnectorSemanticService.SOURCE_IMPORTED, "NONE",
                                Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK),
                candidate("t_ord", "shop_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        verify(semanticMapper, never()).insert(any(ConnectorSemantic.class));
        verify(semanticMapper, never()).update(any(), any());
        assertEquals(2, w.kept());
    }

    @Test
    @DisplayName("推断的行已核对通过：不动（数据说它成立）")
    void 已核对的不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_cust", "name", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_CONFIRMED, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));

        verify(semanticMapper, never()).update(any(), any());
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("★ 推断的行指向非唯一键、也没核对通过：外键 / 命名规则的候选替换它，留下原终点")
    void 弱推断被替换() throws Exception {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_UNDECIDABLE, Map.of("sample_n", 3))),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        LambdaUpdateWrapper<ConnectorSemantic> u = capturedUpdate();
        Map<String, Object> detail = detailIn(u);
        assertEquals("t_cust", detail.get("to_object"));
        assertEquals("id", detail.get("to_column"));
        assertEquals("t_item.cust_id", detail.get("replaced_target"));
        assertEquals(RelationCandidates.ORIGIN_NAME_RULE, detail.get("origin"));
        assertTrue(u.getSqlSet().contains("verified"), "替换后要回到未核对：" + u.getSqlSet());
        assertTrue(u.getParamNameValuePairs().containsValue(ConnectorSemanticService.V_NONE));
        // 写回带「这一行仍是读到时的样子」的条件：读与写之间被人确认或刚落了核对结论的行不覆盖。
        assertTrue(u.getSqlSegment().contains("update_time"), u.getSqlSegment());
        assertEquals(1, w.replaced());
        assertEquals(List.of("t_ord"), w.touchedObjects());
    }

    @Test
    @DisplayName("终点已不在快照里：同样算弱推断，可被替换")
    void 终点消失() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_gone", "id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));
        assertEquals(1, w.replaced());
    }

    @Test
    @DisplayName("模型那一遍的候选不替换任何已有的行")
    void 模型候选不替换() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_RELATION_PASS));

        verify(semanticMapper, never()).update(any(), any());
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("推断的行已经指向唯一键：不动（它本来就是一条像样的关系）")
    void 指向唯一键的不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_cust", "code", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));

        verify(semanticMapper, never()).update(any(), any());
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("带业务方结论的行不动")
    void 业务方结论不动() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of("human_verdict", "UNRELATED"))),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("按唯一键的排序规则比：大小写不同的同一列算已有")
    void 大小写不敏感() {
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("T_ORD", "CUST_ID", "t_cust", "id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_NAME_RULE));

        verify(semanticMapper, never()).insert(any(ConnectorSemantic.class));
        assertEquals(1, w.kept());
    }

    @Test
    @DisplayName("替换时这一行刚被别处改过：不覆盖，记一次并发跳过")
    void 替换撞上并发() {
        when(semanticMapper.update(any(), any())).thenReturn(0);
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(
                        existing("t_ord", "cust_id", "t_item", "cust_id", ConnectorSemanticService.SOURCE_INFERRED,
                                ConnectorSemanticService.V_NONE, Map.of())),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK));

        assertEquals(0, w.replaced());
        assertEquals(1, w.concurrentSkips());
        assertEquals(List.of(), w.touchedObjects());
    }

    @Test
    @DisplayName("插入撞唯一键：只丢这一条，其余照写")
    void 插入撞键() {
        when(semanticMapper.insert(any(ConnectorSemantic.class)))
                .thenThrow(new DuplicateKeyException("dup"))
                .thenReturn(1);
        ConnectorSemanticService.DiscoveryWrite w = write(List.of(),
                candidate("t_ord", "cust_id", "t_cust", "id", RelationCandidates.ORIGIN_FK),
                candidate("t_item", "ord_id", "t_ord", "id", RelationCandidates.ORIGIN_NAME_RULE));

        assertEquals(1, w.conflicts());
        assertEquals(1, w.inserted());
        assertEquals(List.of("t_item"), w.touchedObjects());
    }
}
```

- [ ] **Step 4: 写实现**

`ConnectorSemanticService.java`：

第 1 处，把：

```java
        return new UpsertResult(inserted, updated, notInferred, kept, conflicts, concurrent);
    }

    /**
     * ★ 一条已有的 INFERRED 行被同键的新推断撞上时，落库的是什么。<b>这里决定证据会不会被悄悄抹掉。</b>
     *
```

替换为：

```java
        return new UpsertResult(inserted, updated, notInferred, kept, conflicts, concurrent);
    }

    /**
     * 关系发现写 JOIN 行（数据星图设计 v3 §5.4）。逐列处理，<b>只动 JOIN 行</b>：
     *
     * <pre>
     * 这一列现有的 JOIN 行                                    外键 / 命名规则的候选   模型那一遍的候选
     * 没有                                                    新增                   新增
     * HUMAN / IMPORTED，或带业务方结论                        不动                   不动
     * 推断的行，已核对通过（verified=CONFIRMED）              不动                   不动
     * 推断的行，终点不是单列唯一键（或已不在快照里）、未核对通过  替换                   不动
     * 其余                                                    不动                   不动
     * </pre>
     *
     * <h3>为什么要能替换</h3>
     * 语义层每一列只能有一条关系（唯一键决定）。一条指向兄弟明细表同名列的推断占着这一列，只补空缺的话，
     * 指向主数据主键的那条对的关系永远写不进去。能替换的只限「终点不是唯一键、也没核对通过」的推断：
     * 它在星图上本来就画不出来，对 Agent 也只是一句没有依据的猜测；而替换它的候选来自客户声明的外键或确定性的命名规则。
     * 模型那一遍的候选不替换任何东西——两次模型判断之间没有谁比谁更可信。
     *
     * <p>替换走和 {@link #upsertInferred} 一样的写回纪律（{@link #unchangedSince}）：读与写之间被人确认、刚落了核对结论的行不覆盖。
     *
     * @param rows                已经过 {@link SemanticRowAssembler} 过滤的 JOIN 行，{@code detail_json.origin} 标着来源
     * @param singleUniqueColumns 快照里每张表的单列唯一键，表名、列名都按 {@link SemanticRowAssembler#fold} 折叠；
     *                            表不在里面 = 终点已不在快照里
     */
    @Transactional
    public DiscoveryWrite mergeDiscoveredJoins(Long connectorId, List<ConnectorSemantic> rows,
                                               Map<String, Set<String>> singleUniqueColumns) {
        Connection conn = requireOwned(connectorId);
        Map<String, ConnectorSemantic> byKey = new HashMap<>();
        for (ConnectorSemantic e : semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .eq(ConnectorSemantic::getScope, SCOPE_JOIN))) {
            byKey.putIfAbsent(uniqueKey(e), e);
        }
        int inserted = 0;
        int replaced = 0;
        int kept = 0;
        int conflicts = 0;
        int concurrent = 0;
        Set<String> touched = new LinkedHashSet<>();
        for (ConnectorSemantic r : rows == null ? List.<ConnectorSemantic>of() : rows) {
            if (r == null || !SCOPE_JOIN.equals(r.getScope())) {
                continue;
            }
            if (r.getObjectName() == null) r.setObjectName("");
            if (r.getFieldName() == null) r.setFieldName("");
            r.setTerm("");
            String key = uniqueKey(r);
            ConnectorSemantic old = byKey.get(key);
            if (old == null) {
                r.setId(null);
                r.setTenantId(conn.getTenantId());
                r.setConnectorId(connectorId);
                r.setSource(SOURCE_INFERRED);
                r.setStatus(ST_DRAFT);
                r.setVerified(V_NONE);
                try {
                    semanticMapper.insert(r);
                    byKey.put(key, r);
                    inserted++;
                    touched.add(r.getObjectName());
                } catch (DuplicateKeyException e) {
                    conflicts++;
                    log.warn("关系发现写入撞唯一键，本条跳过 connectorId={} object={} field={}",
                            connectorId, r.getObjectName(), r.getFieldName());
                }
                continue;
            }
            if (!deterministicOrigin(r) || !weakInference(old, singleUniqueColumns)) {
                kept++;
                continue;
            }
            Map<String, Object> oldD = readDetail(old);
            Map<String, Object> newD = new LinkedHashMap<>(readDetail(r));
            newD.put(KEY_REPLACED_TARGET, text(oldD.get(KEY_TO_OBJECT)) + "." + text(oldD.get(KEY_TO_COLUMN)));
            String detailJson = toJson(newD);
            if (detailJson == null) {
                kept++;
                continue;
            }
            LambdaUpdateWrapper<ConnectorSemantic> w = new LambdaUpdateWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getId, old.getId());
            unchangedSince(w, old);
            // 整行按候选重写：对端换了，旧对端上的核对结论、置信度、锚点一个都不再成立，所以全部显式 set（含可能为 null 的列）。
            w.set(ConnectorSemantic::getObjectName, r.getObjectName())
                    .set(ConnectorSemantic::getFieldName, r.getFieldName())
                    .set(ConnectorSemantic::getGloss, r.getGloss())
                    .set(ConnectorSemantic::getDetailJson, detailJson)
                    .set(ConnectorSemantic::getEvidence, r.getEvidence())
                    .set(ConnectorSemantic::getConfidence, r.getConfidence())
                    .set(ConnectorSemantic::getAnchorKind, r.getAnchorKind())
                    .set(ConnectorSemantic::getAnchorHash, r.getAnchorHash())
                    .set(ConnectorSemantic::getStatus, ST_DRAFT)
                    .set(ConnectorSemantic::getVerified, V_NONE);
            if (semanticMapper.update(null, w) > 0) {
                replaced++;
                touched.add(r.getObjectName());
                log.info("关系发现替换了一条弱推断 connectorId={} {}.{}：{} → {}.{}（来源 {}）", connectorId,
                        r.getObjectName(), r.getFieldName(), newD.get(KEY_REPLACED_TARGET),
                        newD.get(KEY_TO_OBJECT), newD.get(KEY_TO_COLUMN), newD.get(KEY_ORIGIN));
            } else {
                concurrent++;
            }
        }
        return new DiscoveryWrite(inserted, replaced, kept, conflicts, concurrent, List.copyOf(touched));
    }

    /** 被替换的弱推断原来指向哪里（{@code 表.列}）。只为排查，不参与任何判定。 */
    static final String KEY_REPLACED_TARGET = "replaced_target";

    /** 候选来自客户声明的外键或确定性的命名规则（{@link RelationCandidates}），而不是模型。 */
    private static boolean deterministicOrigin(ConnectorSemantic r) {
        Object origin = readDetailStatic(r.getDetailJson()).get(KEY_ORIGIN);
        return RelationCandidates.ORIGIN_FK.equals(origin) || RelationCandidates.ORIGIN_NAME_RULE.equals(origin);
    }

    /** §5.4 表里唯一可替换的那一格：推断的、没有业务方结论、未核对通过、终点不是单列唯一键（或已不在快照里）。 */
    private boolean weakInference(ConnectorSemantic old, Map<String, Set<String>> singleUniqueColumns) {
        if (!SOURCE_INFERRED.equals(old.getSource()) || V_CONFIRMED.equals(old.getVerified())) {
            return false;
        }
        Map<String, Object> d = readDetail(old);
        if (d.get(KEY_HUMAN_VERDICT) != null) {
            return false;
        }
        Set<String> unique = singleUniqueColumns == null ? null
                : singleUniqueColumns.get(SemanticRowAssembler.fold(text(d.get(KEY_TO_OBJECT))));
        return unique == null || !unique.contains(SemanticRowAssembler.fold(text(d.get(KEY_TO_COLUMN))));
    }

    private static Map<String, Object> readDetailStatic(String json) {
        Map<String, Object> m = parseDetailOrNull(json);
        return m == null ? Map.of() : m;
    }

    /**
     * {@link #mergeDiscoveredJoins} 的结果。
     *
     * @param touchedObjects 这次新增或替换了关系的起点表（原样写法）：补全链把它们交给采样核对
     */
    public record DiscoveryWrite(int inserted, int replaced, int kept, int conflicts, int concurrentSkips,
                                 List<String> touchedObjects) {
    }

    /**
     * ★ 一条已有的 INFERRED 行被同键的新推断撞上时，落库的是什么。<b>这里决定证据会不会被悄悄抹掉。</b>
     *
```

第 2 处，把：

```java
        private String answeredBy;
        private String answeredName;
        private String traceId;
    }

    /**
     * 写下一条人确认过的语义（任意 scope）。<b>这是全仓库唯一一条从对话写 {@code source=HUMAN} 的路。</b>
     *
```

替换为：

```java
        private String answeredBy;
        private String answeredName;
        private String traceId;
        /**
         * 覆盖<b>同一条关系</b>时，从旧行带过来的 detail 键（调用方这次没给的才带）。只对 JOIN 有意义：
         * 换了对端就不带，那是另一条关系的结构。见 {@link #JOIN_STRUCTURE_KEYS}。
         */
        private List<String> carryOver;
    }

    /**
     * JOIN 行上采样核对落下的<b>结构</b>：关系的形态、多态关系的判别列、组合键的成员列。业务方确认一条关系，确认的是
     * 「这两列是同一个东西」，不是「这条关系没有条件」，所以确认时这三个键跟着旧行走（数据星图设计 v3 §5.5）。
     * 采样数字（包含率、样本数）不在其中：那是数据的结论，人的一句话不该替它背书。
     */
    static final List<String> JOIN_STRUCTURE_KEYS = List.of("join_kind", "discriminator_column", "composite_columns");

    /**
     * 写下一条人确认过的语义（任意 scope）。<b>这是全仓库唯一一条从对话写 {@code source=HUMAN} 的路。</b>
     *
```

第 3 处，把：

```java

            // 覆盖：先把旧值留痕，再改。
            String history = appendHistory(existing, w.getAnsweredBy(), w.getAnsweredName(), w.getTraceId(), now);
            String newDetail = overwriteDetail(existing, w.getDetail(), columns);
            String setKind = null;
            String setHash = null;
            if (w.getOnOverwrite() == HumanAnchor.SET) {
```

替换为：

```java

            // 覆盖：先把旧值留痕，再改。
            String history = appendHistory(existing, w.getAnsweredBy(), w.getAnsweredName(), w.getTraceId(), now);
            String newDetail = overwriteDetail(existing, w.getDetail(), columns, w.getCarryOver());
            String setKind = null;
            String setHash = null;
            if (w.getOnOverwrite() == HumanAnchor.SET) {
```

第 4 处，把：

```java
                .anchorKind(anchorHash == null ? ANCHOR_NONE : ANCHOR_JOIN)
                .anchorHash(anchorHash)
                .onOverwrite(HumanAnchor.SET)
                .subject("关系「" + objectName + "." + columnName + " → " + toObject + "." + toColumn + "」")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
```

替换为：

```java
                .anchorKind(anchorHash == null ? ANCHOR_NONE : ANCHOR_JOIN)
                .anchorHash(anchorHash)
                .onOverwrite(HumanAnchor.SET)
                .carryOver(JOIN_STRUCTURE_KEYS)
                .subject("关系「" + objectName + "." + columnName + " → " + toObject + "." + toColumn + "」")
                .answeredBy(answeredBy)
                .answeredName(answeredName)
```

第 5 处，把：

```java
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
```

替换为：

```java
     *
     * <p>三种情形刻意分开：这次给了 detail 就整份替换；没给但补了依赖列，就<b>只</b>换掉依赖列名单、
     * 其余键一个字不动（「重新确认一次口径」不等于「把我没提的那些细节清空」）；两样都没给就沿用既有的，
     * 只去掉过期名单。给了 detail 又给了 {@code carryOver} 的，同一条关系上旧行的那几个键跟着走。
     */
    private String overwriteDetail(ConnectorSemantic existing, Map<String, Object> detail,
                                   List<AnchorColumn> columns, List<String> carryOver) {
        if (detail != null) {
            return humanDetailJson(withCarriedOver(existing, detail, carryOver), columns);
        }
        if (!columns.isEmpty()) {
            return withAnchorColumns(withoutStaleRemoved(existing.getDetailJson(), existing.getId()),
```

第 6 处，把：

```java
        return withoutStaleRemoved(existing.getDetailJson(), existing.getId());
    }

    /** 在既有 detail 上原地换掉依赖列名单。序列化失败就原样返回——少一次 re-baseline，好过把整份 detail 写丢。 */
    private String withAnchorColumns(String detailJson, Long id, List<AnchorColumn> columns) {
        Map<String, Object> d = new LinkedHashMap<>(readDetail(detailJson, id));
```

替换为：

```java
        return withoutStaleRemoved(existing.getDetailJson(), existing.getId());
    }

    /** 见 {@link HumanWrite#carryOver}：对端没变才带，调用方这次给了的键不覆盖。 */
    private Map<String, Object> withCarriedOver(ConnectorSemantic existing, Map<String, Object> detail,
                                                List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return detail;
        }
        Map<String, Object> old = readDetail(existing);
        if (!sameName(old.get(KEY_TO_OBJECT), detail.get(KEY_TO_OBJECT))
                || !sameName(old.get(KEY_TO_COLUMN), detail.get(KEY_TO_COLUMN))) {
            return detail;
        }
        Map<String, Object> merged = new LinkedHashMap<>(detail);
        for (String k : keys) {
            if (!merged.containsKey(k) && old.get(k) != null) {
                merged.put(k, old.get(k));
            }
        }
        return merged;
    }

    /** 在既有 detail 上原地换掉依赖列名单。序列化失败就原样返回——少一次 re-baseline，好过把整份 detail 写丢。 */
    private String withAnchorColumns(String detailJson, Long id, List<AnchorColumn> columns) {
        Map<String, Object> d = new LinkedHashMap<>(readDetail(detailJson, id));
```

- [ ] **Step 5: 跑语义层服务的全部测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='ConnectorSemantic*Test*,SemanticRowAssemblerTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && echo "cases=$(cat TEST-*.xml | grep -c '<testcase') failures=$(cat TEST-*.xml | grep -c '<failure\|<error')"
```
Expected: `failures=0`（ConnectorSemanticDiscoveredJoinTest 12 个用例，ConnectorSemanticHumanWriteTest 多出 2 个）。

- [ ] **Step 6: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticService.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticDiscoveredJoinTest.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticHumanWriteTest.java && \
git commit -q -F - <<'MSG'
feat(connector): 关系发现的写入规则；业务方确认关系时保留结构键

mergeDiscoveredJoins 逐列写 JOIN 行：没有就新增；人工 / 导入 / 带业务方结论 / 已核对通过的不动；
终点不是唯一键、也没核对通过的推断，可被外键或命名规则的候选替换（模型候选不替换），
替换带「仍是读到时的样子」条件并留下原终点。
annotateJoin 覆盖同一对端时带上 join_kind / discriminator_column / composite_columns（v2 审查 #5）。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 5: 业务视图与补全链状态两张新表（data-service）

spec §6.1 与附录 D：`connector_business_view`（一个对象或一条关系的「人话」）、`connector_enrichment_state`（每个连接一行：认领、上次结果、输入指纹、模型那一遍的输入指纹、两段说明）。实体、Mapper、DDL、租户白名单、上线文档一起做；`BusinessViewSchemaTest` 钉住三处对得上——这个项目没有 Flyway，实体多映射一列、DDL 里没有，应用照常启动，碰这张表的每个查询都 `Unknown column`。

**Files:**
- Create: `modules/data-server/src/main/resources/db/migration/V20261001__data_graph_business_view.sql`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/entity/ConnectorBusinessView.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/entity/ConnectorEnrichmentState.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/mapper/ConnectorBusinessViewMapper.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/mapper/ConnectorEnrichmentStateMapper.java`
- Modify: `common/common-core/src/main/java/com/jimeng/common/core/tenant/JimengTenantLineHandler.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/businessview/BusinessViewSchemaTest.java`
- Modify: `docs/RELEASE-CHECKLIST-connector.md`
- Create: `docs/config-changes/2026-10-01-data-graph-v3.md`

**Interfaces:**
- Produces（Task 8、9、10 用）：
  - `ConnectorBusinessView`：`tenantId, connectorId, kind, objectName, fieldName, displayName, summary, domain, source, inputHash, modelCode, promptVersion`；常量 `KIND_OBJECT / KIND_RELATION / SOURCE_MODEL / SOURCE_HUMAN`；
  - `ConnectorBusinessViewMapper.physicalDeleteRow(String tenantId, Long connectorId, Long id): int`；
  - `ConnectorEnrichmentState`：`tenantId, connectorId, claimAt, lastStatus, inputFingerprint, relationPassFingerprint, lastAttemptAt, finishedAt, relationNote, viewNote`；常量 `STATUS_READY / STATUS_FAILED`；
  - `ConnectorEnrichmentStateMapper.claim(String tenantId, Long connectorId, Date now, Date staleBefore): int`（1 = 认领成功）。

- [ ] **Step 1: 写失败的测试**

`BusinessViewSchemaTest.java`：

```java
package com.jimeng.dataserver.ai.connector.businessview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据星图 v3 的两张新表：实体、DDL、租户白名单三处必须对得上。
 *
 * <p>这个项目没有 Flyway，DDL 是手工执行的：实体多映射一列、DDL 里没有，应用照常启动，碰这张表的每个查询都
 * {@code Unknown column}；表没进租户白名单，别的租户的业务名称就会漏出来。两种错都不在编译期报。
 */
class BusinessViewSchemaTest {

    private static final String DDL = "db/migration/V20261001__data_graph_business_view.sql";

    private static String ddl() throws Exception {
        try (InputStream in = BusinessViewSchemaTest.class.getClassLoader().getResourceAsStream(DDL)) {
            assertNotNull(in, DDL + " 不在 classpath 上");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 某张表的 CREATE TABLE 语句正文。 */
    private static String createTable(String ddl, String table) {
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + table + "` \\((.*?)\\) ENGINE", Pattern.DOTALL)
                .matcher(ddl);
        assertTrue(m.find(), "DDL 里没有 " + table);
        return m.group(1);
    }

    private static List<String> columnsOf(Class<?> entity) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfo info = TableInfoHelper.initTableInfo(assistant, entity);
        List<String> out = new ArrayList<>();
        out.add(info.getKeyColumn());
        for (TableFieldInfo f : info.getFieldList()) {
            out.add(f.getColumn());
        }
        return out;
    }

    @Test
    @DisplayName("★ 两张新表都在租户白名单里：少一张，别的租户的业务名称与运行状态就会漏出来")
    void 租户隔离() {
        JimengTenantLineHandler handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
        assertFalse(handler.ignoreTable("connector_business_view"));
        assertFalse(handler.ignoreTable("connector_enrichment_state"));
    }

    @Test
    @DisplayName("实体映射的每一列 DDL 里都有")
    void 实体与DDL一致() throws Exception {
        String ddl = ddl();
        for (Class<?> entity : List.of(ConnectorBusinessView.class, ConnectorEnrichmentState.class)) {
            String table = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    entity).getTableName();
            String body = createTable(ddl, table);
            for (String column : columnsOf(entity)) {
                assertTrue(body.contains("`" + column + "`"), table + " 的 DDL 缺列 " + column);
            }
        }
    }

    /** 唯一键不含 deleted 的前提是「不产生软删死行」：MODEL 行物理删除，HUMAN 行只原地更新。 */
    @Test
    @DisplayName("唯一键刻意不含 deleted")
    void 唯一键不含deleted() throws Exception {
        String ddl = ddl();
        Matcher uk = Pattern.compile("UNIQUE KEY `(\\w+)` \\(([^)]*)\\)").matcher(ddl);
        int n = 0;
        while (uk.find()) {
            n++;
            assertFalse(uk.group(2).contains("deleted"), uk.group(1));
            assertTrue(uk.group(2).startsWith("`tenant_id`, `connector_id`"), "唯一键以租户、连接打头：" + uk.group(1));
        }
        assertEquals(2, n);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='BusinessViewSchemaTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `ConnectorBusinessView`、`ConnectorEnrichmentState`。

- [ ] **Step 3: DDL**

`modules/data-server/src/main/resources/db/migration/V20261001__data_graph_business_view.sql`：

```sql
-- 数据星图 v3：给人看的业务视图 + 语义层补全链的运行状态（设计文档 docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md §6、附录 D）。
--
-- 没有 Flyway：这份 DDL 要手工执行（见 docs/RELEASE-CHECKLIST-connector.md 的 ① 与 ⑥ schema 自检）。
-- 两张表都带 tenant_id，已登记进 JimengTenantLineHandler.TENANT_AWARE_TABLES。
-- CREATE TABLE IF NOT EXISTS，重复执行无害。
--
-- 唯一键刻意【不含 deleted】，做法同 connector_semantic：MODEL 行物理删除，HUMAN 行只原地更新，
-- 表上永远不出现软删死行。不要给这两张表加逻辑删除入口。deleted 列只是 BaseEntity 的全局 @TableLogic 要求它存在。

CREATE TABLE IF NOT EXISTS `connector_business_view` (
  `id`             BIGINT        NOT NULL COMMENT '雪花ID',
  `tenant_id`      VARCHAR(64)   NOT NULL COMMENT '租户ID',
  `connector_id`   BIGINT        NOT NULL COMMENT 'connection.id',
  `kind`           VARCHAR(16)   NOT NULL COMMENT 'OBJECT / RELATION',
  `object_name`    VARCHAR(191)  NOT NULL COMMENT '表名；RELATION 为起点表',
  `field_name`     VARCHAR(191)  NOT NULL DEFAULT '' COMMENT 'RELATION 为起点列；OBJECT 为空串',
  `display_name`   VARCHAR(64)   DEFAULT NULL COMMENT '业务名 / 关系角色名',
  `summary`        VARCHAR(255)  DEFAULT NULL COMMENT '一句话说明（仅 OBJECT）',
  `domain`         VARCHAR(32)   DEFAULT NULL COMMENT '业务领域（仅 OBJECT）',
  `source`         VARCHAR(16)   NOT NULL COMMENT 'MODEL / HUMAN；HUMAN 行永远不被模型覆盖',
  `input_hash`     CHAR(64)      DEFAULT NULL COMMENT '生成名称 / 说明 / 角色名时输入的 SHA-256，输入变了才重新生成',
  `model_code`     VARCHAR(64)   DEFAULT NULL COMMENT '生成所用模型',
  `prompt_version` VARCHAR(32)   DEFAULT NULL COMMENT '生成所用提示词版本',
  `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT 'BaseEntity 全局 @TableLogic 要求有这一列；本表不做逻辑删除',
  `create_time`    DATETIME      DEFAULT NULL,
  `create_user`    VARCHAR(64)   DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT NULL,
  `update_user`    VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  -- 64*4 + 8 + 16*4 + 191*4*2 = 1856 字节，低于 InnoDB 索引 3072 字节上限（191 的来历同 connector_semantic）
  UNIQUE KEY `uk_business_view` (`tenant_id`, `connector_id`, `kind`, `object_name`, `field_name`),
  KEY `idx_business_view_conn` (`connector_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据星图：给人看的业务名称与说明';

CREATE TABLE IF NOT EXISTS `connector_enrichment_state` (
  `id`                        BIGINT        NOT NULL COMMENT '雪花ID',
  `tenant_id`                 VARCHAR(64)   NOT NULL COMMENT '租户ID',
  `connector_id`              BIGINT        NOT NULL COMMENT 'connection.id',
  `claim_at`                  DATETIME      DEFAULT NULL COMMENT '认领时间（秒）；非空且未满 30 分钟 = 正在跑，跑的过程中每步续期',
  `last_status`               VARCHAR(16)   DEFAULT NULL COMMENT '上次结果 READY / FAILED；NULL = 从没跑完过',
  `input_fingerprint`         CHAR(64)      DEFAULT NULL COMMENT '上次尝试时的输入指纹（设计文档 §4），定时对账据此判断要不要补跑',
  `relation_pass_fingerprint` CHAR(64)      DEFAULT NULL COMMENT '上次模型关系那一遍的输入指纹；没变就不再问模型',
  `last_attempt_at`           DATETIME      DEFAULT NULL COMMENT '上次开始时间；失败后的 6 小时退避按它算',
  `finished_at`               DATETIME      DEFAULT NULL,
  `relation_note`             VARCHAR(500)  DEFAULT NULL COMMENT '关系发现：新增 / 替换条数、来源分布、失败原因',
  `view_note`                 VARCHAR(500)  DEFAULT NULL COMMENT '业务文字：生成条数、校验退回条数、失败原因',
  `deleted`                   TINYINT       NOT NULL DEFAULT 0 COMMENT 'BaseEntity 全局 @TableLogic 要求有这一列；本表不做逻辑删除',
  `create_time`               DATETIME      DEFAULT NULL,
  `create_user`               VARCHAR(64)   DEFAULT NULL,
  `update_time`               DATETIME      DEFAULT NULL,
  `update_user`               VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_enrichment_state` (`tenant_id`, `connector_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据星图：语义层补全链的运行状态';
```

- [ ] **Step 4: 实体与 Mapper**

`ConnectorBusinessView.java`：

```java
package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据星图的业务视图：一个对象或一条关系的「人话」——业务名、一句说明、业务领域、关系角色名。
 *
 * <p>语义层是写给 AI 的说明书，这张表是同一份理解写给业务人员和产品看的版本，由补全链按面向业务的要求改写后单独存放，
 * <b>不是语义层原文</b>。租户隔离表（已加入 TENANT_AWARE_TABLES）。
 *
 * <p><b>本表不产生软删死行</b>，这是 {@code uk_business_view} 不含 deleted 的前提：{@code MODEL} 行需要删除时物理删除
 * （{@code ConnectorBusinessViewMapper#physicalDeleteRow}），{@code HUMAN} 行只原地更新。不要给本表加逻辑删除入口。
 */
@Schema(description = "数据星图：给人看的业务名称与说明")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_business_view")
@Data
public class ConnectorBusinessView extends BaseEntity {

    public static final String KIND_OBJECT = "OBJECT";
    public static final String KIND_RELATION = "RELATION";
    public static final String SOURCE_MODEL = "MODEL";
    /** 以后客户自己改的名字。模型永远不覆盖它。 */
    public static final String SOURCE_HUMAN = "HUMAN";

    @TableField("tenant_id")
    private String tenantId;

    @TableField("connector_id")
    private Long connectorId;

    @Schema(description = "OBJECT / RELATION")
    @TableField("kind")
    private String kind;

    @Schema(description = "表名；RELATION 为起点表")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "RELATION 为起点列；OBJECT 为空串")
    @TableField("field_name")
    private String fieldName;

    @Schema(description = "业务名（OBJECT）/ 关系角色名（RELATION）")
    @TableField("display_name")
    private String displayName;

    @Schema(description = "一句话说明（仅 OBJECT）")
    @TableField("summary")
    private String summary;

    @Schema(description = "业务领域（仅 OBJECT）")
    @TableField("domain")
    private String domain;

    @Schema(description = "MODEL / HUMAN")
    @TableField("source")
    private String source;

    @Schema(description = "生成名称 / 说明 / 角色名时输入的 SHA-256")
    @TableField("input_hash")
    private String inputHash;

    @TableField("model_code")
    private String modelCode;

    @TableField("prompt_version")
    private String promptVersion;
}
```

`ConnectorEnrichmentState.java`：

```java
package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 语义层补全链（关系发现 → 采样核对 → 业务文字）在一条连接上的运行状态，每个连接一行。
 *
 * <p>{@link #claimAt} 是认领：非空且未满 30 分钟就是正在跑，跑的过程中每一步续期。「正在跑」不另存一个状态值——
 * 进程在中途被杀掉时，一个写死的 RUNNING 会永远挂着；认领时间过期就自然让出。
 * 租户隔离表（已加入 TENANT_AWARE_TABLES）；不产生软删死行，理由同 {@link ConnectorBusinessView}。
 */
@Schema(description = "数据星图：语义层补全链的运行状态")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_enrichment_state")
@Data
public class ConnectorEnrichmentState extends BaseEntity {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    @TableField("tenant_id")
    private String tenantId;

    @TableField("connector_id")
    private Long connectorId;

    @Schema(description = "认领时间（秒）；非空且未满 30 分钟 = 正在跑")
    @TableField("claim_at")
    private Date claimAt;

    @Schema(description = "上次结果 READY / FAILED；null = 从没跑完过")
    @TableField("last_status")
    private String lastStatus;

    @Schema(description = "上次尝试时的输入指纹")
    @TableField("input_fingerprint")
    private String inputFingerprint;

    @Schema(description = "上次模型关系那一遍的输入指纹")
    @TableField("relation_pass_fingerprint")
    private String relationPassFingerprint;

    @Schema(description = "上次开始时间；失败后的退避按它算")
    @TableField("last_attempt_at")
    private Date lastAttemptAt;

    @TableField("finished_at")
    private Date finishedAt;

    @Schema(description = "关系发现：新增 / 替换条数、来源分布、失败原因")
    @TableField("relation_note")
    private String relationNote;

    @Schema(description = "业务文字：生成条数、校验退回条数、失败原因")
    @TableField("view_note")
    private String viewNote;
}
```

`ConnectorBusinessViewMapper.java`：

```java
package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConnectorBusinessViewMapper extends BaseMapper<ConnectorBusinessView> {

    /**
     * 物理删除一行（对象或关系已经从快照、语义层里消失的 MODEL 行）。
     *
     * <p>为什么是物理删除：唯一键不含 deleted，{@code BaseMapper.delete} 在全局 {@code @TableLogic} 下是软删，
     * 死行会占着唯一键，同一个对象再出现时就写不进去。
     *
     * <p>显式写死租户与连接：{@code runAsSystem} 下租户拦截器整个不生效，只按 id 定位就能删到别的租户。
     * 调用方必须在真实租户上下文里、先确认连接属于当前租户。
     */
    @Delete("DELETE FROM connector_business_view WHERE tenant_id = #{tenantId} "
            + "AND connector_id = #{connectorId} AND id = #{id}")
    int physicalDeleteRow(@Param("tenantId") String tenantId,
                          @Param("connectorId") Long connectorId,
                          @Param("id") Long id);
}
```

`ConnectorEnrichmentStateMapper.java`：

```java
package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ConnectorEnrichmentStateMapper extends BaseMapper<ConnectorEnrichmentState> {

    /**
     * 认领：只有没人认领、或上一次认领已经过期（{@code claim_at < staleBefore}）时才成功。
     *
     * <p>一条 UPDATE 完成「判断 + 占位」，多副本同时来抢只有一个拿到 1。{@code now} 必须截到秒：列是 DATETIME，
     * 带毫秒的值写进去会被舍入，之后按 {@code claim_at = 认领时间} 续期和收尾就再也对不上。
     *
     * @return 1 = 认领成功；0 = 别人正在跑（或行不存在）
     */
    @Update("UPDATE connector_enrichment_state SET claim_at = #{now}, last_attempt_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND connector_id = #{connectorId} "
            + "AND (claim_at IS NULL OR claim_at < #{staleBefore})")
    int claim(@Param("tenantId") String tenantId,
              @Param("connectorId") Long connectorId,
              @Param("now") Date now,
              @Param("staleBefore") Date staleBefore);
}
```

- [ ] **Step 5: 租户白名单**

`JimengTenantLineHandler.java`：

第 1 处，把：

```java
            "connector_semantic_generation",
            "connector_semantic_generation_table",
            "connector_semantic_staged",
            "skill_eval_run",
            "chat_conversation",
            "chat_message",
```

替换为：

```java
            "connector_semantic_generation",
            "connector_semantic_generation_table",
            "connector_semantic_staged",
            // 数据星图：给人看的业务名称与说明、补全链的运行状态（同样是客户的表名、列名与对它们的说明）
            "connector_business_view",
            "connector_enrichment_state",
            "skill_eval_run",
            "chat_conversation",
            "chat_message",
```

- [ ] **Step 6: 上线文档**

`docs/RELEASE-CHECKLIST-connector.md`：

第 1 处，把：

````markdown
  < modules/data-server/docs/ops-20260614-product-feedback.sql
```

跑完用下面 ⑥ 的自检脚本确认，不要凭"应该跑过了"。

## ② Nacos 配置（不配则能力静默不可用）
````

替换为：

````markdown
  < modules/data-server/docs/ops-20260614-product-feedback.sql
```

### 数据星图 v3（2026-10-01）

```bash
docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server \
  < modules/data-server/src/main/resources/db/migration/V20261001__data_graph_business_view.sql
```

新表 `connector_business_view` / `connector_enrichment_state`。漏跑的症状：应用照常 `Started`，
数据星图接口 500（`Table ... doesn't exist`），补全链每次触发都失败、定时对账每 10 分钟报一次。
详见 `docs/config-changes/2026-10-01-data-graph-v3.md`。

跑完用下面 ⑥ 的自检脚本确认，不要凭"应该跑过了"。

## ② Nacos 配置（不配则能力静默不可用）
````

`docs/config-changes/2026-10-01-data-graph-v3.md`：

```markdown
# 2026-10-01 数据星图 v3：给业务人员看的对象关系图

> 本目录的约定见 `2026-09-21-enable-connector-agent-plane.md` 开头：改了数据库结构或运行时配置就留一份记录，
> 只写键名、语义与回滚办法，绝不写任何真实密钥值。设计见 `docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md`。

## 一、数据库（生产由你执行，先于部署）

| 文件 | 内容 | 漏跑时的症状 |
|---|---|---|
| `db/migration/V20261001__data_graph_business_view.sql` | 新表 `connector_business_view`（对象的业务名、一句说明、业务领域；关系的角色名）、`connector_enrichment_state`（每个连接一行：补全链的认领、上次结果、输入指纹） | 应用照常 `Started`；数据星图接口 500（`Table ... doesn't exist`），补全链每次触发都失败 |

两张表都是租户隔离表，已登记进 `JimengTenantLineHandler.TENANT_AWARE_TABLES`（代码里，不需要配置）。
执行方式同其它 DDL：`docker exec -i <mysql容器> mysql --default-character-set=utf8mb4 -uroot -p... data-server < <文件>`。
`CREATE TABLE IF NOT EXISTS`，重复执行无害。

## 二、Nacos（`data-server.yml`）

**没有必须新增的键**。模型沿用 `connector.semantic.infer-model`。可选的键：

| 键 | 默认 | 说明 |
|---|---|---|
| `connector.semantic.enrichment.enabled` | `true` | 补全链总开关。关掉后推导成功照旧直接派发采样核对，定时对账不跑，星图照常用已有数据 |
| `connector.semantic.enrichment.reconcile-interval-ms` | `600000` | 定时对账间隔（`@Scheduled` 直接读占位符，改它要重启） |

## 三、权限

新增模块码 `DATA_GRAPH_MODULE`（显示名「数据星图」）。企业超管始终可用；普通成员要在 jm-admin 的角色授权里勾上「数据星图」
才能看到入口和数据。jm-admin 的授权弹窗按接口渲染，不用发版。

## 四、上线后会发生什么

- 部署后定时对账（首次在启动 5 分钟后）逐个给已有连接补跑补全链，每 10 分钟最多一个连接。每个连接大约调用模型 4–6 次，
  并对新补出的关系派发一轮采样核对（打客户库，走原有的速率预算）。
- 之后语义层每次生成完成（规则推导、增量补写、agent 生成定稿）都会自动接着跑一遍。

## 五、回滚

- data-service：把 `connector.semantic.enrichment.enabled` 设为 `false` 即可停掉补全链；回滚代码也行，两张新表是纯新增，留着不影响旧代码。
  关系发现写进语义层的 JOIN 行是普通的推断行（`detail_json.origin` = `FK` / `NAME_RULE` / `RELATION_PASS`），旧代码照常读。
- 前端：回滚代码即可。
```

- [ ] **Step 7: 装两个 common 模块，跑测试**

```bash
cd $DS && mvn -o -q install -DskipTests -pl common/common-core,common/common-persistence && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='BusinessViewSchemaTest*,DataGraphServiceTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 两个文件都 `failures=0`；BusinessViewSchemaTest 3 个用例。

- [ ] **Step 8: 在本地 dev 库建表并核对**

```bash
cd $DS && docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server \
  < modules/data-server/src/main/resources/db/migration/V20261001__data_graph_business_view.sql; \
docker exec dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 -N data-server -e \
  "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='data-server' AND TABLE_NAME IN ('connector_business_view','connector_enrichment_state');" 2>/dev/null
```
Expected: 两行，注释是中文原文（`数据星图：给人看的业务名称与说明`、`数据星图：语义层补全链的运行状态`），不是乱码。`CREATE TABLE IF NOT EXISTS`，表已存在时重复执行无害。**生产库不在这里执行**。

- [ ] **Step 9: 提交**

```bash
cd $DS && git add common/common-core/src/main/java/com/jimeng/common/core/tenant/JimengTenantLineHandler.java \
  common/common-persistence/src/main/java/com/jimeng/persistence/entity/ConnectorBusinessView.java \
  common/common-persistence/src/main/java/com/jimeng/persistence/entity/ConnectorEnrichmentState.java \
  common/common-persistence/src/main/java/com/jimeng/persistence/mapper/ConnectorBusinessViewMapper.java \
  common/common-persistence/src/main/java/com/jimeng/persistence/mapper/ConnectorEnrichmentStateMapper.java \
  modules/data-server/src/main/resources/db/migration/V20261001__data_graph_business_view.sql \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/businessview/BusinessViewSchemaTest.java \
  docs/RELEASE-CHECKLIST-connector.md docs/config-changes/2026-10-01-data-graph-v3.md && \
git commit -q -F - <<'MSG'
feat(graph): 业务视图与补全链状态两张新表（实体、DDL、租户白名单、上线文档）

connector_business_view：对象的业务名、一句说明、业务领域；关系的角色名。
connector_enrichment_state：每个连接一行，认领、上次结果、输入指纹。
唯一键不含 deleted，MODEL 行物理删除、HUMAN 行只原地更新；两张表进租户白名单。
没有 Flyway：DDL 手工执行，上线清单 ① 与变更文档已补。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---
### Task 6: 语义层关系发现——规则候选 + 整库模型一遍（data-service）

spec §5.1–§5.4。两个类：

- `SemanticModelCall`：补全链「调一次模型、要一个 JSON 回来」的公共部分（请求体、抽文本、去围栏和闲话、截断修复）。和语义层推导同一个模型、同一种调法。不能并进 `ConnectorSemanticService`（那个类不能碰 `ClaudeService`）。
- `SemanticRelationDiscovery.discover(connectorId, lastPassFingerprint)`：① 确定性候选（Task 3）；② 模型一遍——只问「像引用（名字以 id/code/no/num/key 结尾）、不是本表单列唯一键、还没有关系、也没有规则候选」的列，**每一片都带全部表的目标索引**（语义层推导漏关系的根因就是分批后看不见别的表），输入指纹没变就不再问；③ 规则候选和模型产出一起经 `SemanticRowAssembler.toRows` 过滤（两端都在快照里、丢 GUESS、每列去重），`detail_json.origin` 标来源；④ 交给 Task 4 的 `mergeDiscoveredJoins`。模型那一遍失败不影响规则候选落库，失败原因进结果，指纹不前移。

**存下的指纹按写完之后的样子算**（`SemanticRelationDiscoveryTest#自己写下的不算变化`）：这一遍自己写下的关系下一轮会进「已知关系」、把那些列挪出待判列，按写之前的输入存的话，之后不管因为什么重跑都对不上，模型答过的列和没把握不答的列会被再问一遍，多出来的随机产出还要去客户库上采样核对。验证时真出过这事：一次定时刷新结构（第一次给快照补上外键信息）引出重跑，模型被重问，又多给了 26 条。

外键候选的依据标 `COMMENT`（客户写在库里的约束），命名规则标 `NAME`；模型产出只收这一片问到的列（模型顺手写的别的列没经过「还没有关系」的筛选，可能改写已有关系），自报把握低于 60 的不收（挡「库中无对应主数据表」「仅为占位」这类占位条目），每片最多问 200 列（答案要装得进 max_tokens）。

**模型那一遍的产出只给 Agent 当线索**：2026-10-01 在本地两个真实库上试跑三次，提示词和自报把握都挡不住误报（要求「把握不低于 70 才写」之后，「币种 → 公司代码」也报 70），所以星图不画 `origin=RELATION_PASS` 的关系，直到采样核对通过或业务方确认（Task 10 的判定表第 7′ 行）。提示词里的措辞（宁缺毋滥、终点必须是那个业务对象本身、明细与单据头成对的规律）是三次试跑后定下的，原样照抄。

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/SemanticModelCall.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/SemanticRelationDiscovery.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/SemanticModelCallTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/SemanticRelationDiscoveryTest.java`

**Interfaces:**
- Consumes: Task 3 `RelationCandidates.of / norm / ORIGIN_*`、`RelationRuleFixtures.schema`；Task 4 `mergeDiscoveredJoins`、`DiscoveryWrite`；`ConnectorSemanticDeriveService.modelTimeout`、`repairTruncatedJson`（包内静态方法）；`ConnectorSemanticService.shortGloss / sha256 / KEY_ORIGIN`。
- Produces（Task 8、9 用）：
  - `SemanticModelCall.askJson(String what, Long connectorId, String system, String user, int maxTokens): Map<String,Object>`，解析不了抛 `IllegalStateException`；`SemanticModelCall.modelCode(): String`（没配为 `null`）；
  - `SemanticRelationDiscovery.discover(Long connectorId, String lastPassFingerprint): Result`；
  - `record SemanticRelationDiscovery.Result(int foreignKeyCandidates, int nameRuleCandidates, int modelProposed, int modelAccepted, boolean modelAsked, String passFingerprint, String modelError, ConnectorSemanticService.DiscoveryWrite write)`，`note()` 给状态表的 `relation_note`；`passFingerprint` 是这一遍写完之后的模型输入指纹（模型那一遍失败时原样返回传进来的 `lastPassFingerprint`），Task 9 原样存进 `relation_pass_fingerprint`。

- [ ] **Step 1: 写失败的测试**

`SemanticModelCallTest.java`：

````java
package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticModelCallTest {

    private ClaudeService claude;
    private SemanticModelCall call;

    @BeforeEach
    void setUp() {
        claude = mock(ClaudeService.class);
        call = new SemanticModelCall(claude, new ConnectorProperties());
    }

    private void reply(String text) {
        when(claude.messagesInternal(any(), any()))
                .thenReturn(Map.of("content", List.of(Map.of("type", "text", "text", text))));
    }

    @Test
    @DisplayName("请求体：可变集合、带 system 与一条 user 消息、max_tokens 照传；没配模型时不下发 model")
    @SuppressWarnings("unchecked")
    void 请求体() {
        reply("{\"joins\":[]}");

        call.askJson("关系发现", 7L, "系统提示", "用户内容", 8000);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(claude).messagesInternal(body.capture(), timeout.capture());
        assertEquals("系统提示", body.getValue().get("system"));
        assertEquals(8000, body.getValue().get("max_tokens"));
        assertFalse(body.getValue().containsKey("model"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getValue().get("messages");
        assertEquals("用户内容", messages.get(0).get("content"));
        // 下游 ModelResolver / GenericChatClient 会就地改写 body 与 messages：不可变集合在那里抛一个 message 为 null 的异常。
        assertDoesNotThrow(() -> body.getValue().put("stream", false));
        assertDoesNotThrow(() -> ((List<Object>) body.getValue().get("messages")).add(Map.of()));
        assertEquals(Duration.ofSeconds(900), timeout.getValue());
    }

    @Test
    @DisplayName("配了 connector.semantic.infer-model 就下发 model，并如实报出来")
    @SuppressWarnings("unchecked")
    void 配了模型() {
        ReflectionTestUtils.setField(call, "inferModel", " claude-x ");
        reply("{}");

        call.askJson("业务文字", 7L, "s", "u", 100);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(claude).messagesInternal(body.capture(), any());
        assertEquals("claude-x", body.getValue().get("model"));
        assertEquals("claude-x", call.modelCode());
    }

    @Test
    @DisplayName("markdown 围栏和正文前的闲话都去掉")
    void 围栏与闲话() {
        reply("好的，结果如下：\n```json\n{\"joins\":[{\"object\":\"a\"}]}\n```");
        Map<String, Object> out = call.askJson("关系发现", 7L, "s", "u", 100);
        assertEquals(1, ((List<?>) out.get("joins")).size());
    }

    @Test
    @DisplayName("被 max_tokens 截断：收尾到最后一个完整条目")
    void 截断修复() {
        reply("{\"joins\":[{\"object\":\"a\",\"column\":\"b\"},{\"object\":\"c\",\"colu");
        Map<String, Object> out = call.askJson("关系发现", 7L, "s", "u", 100);
        assertEquals(1, ((List<?>) out.get("joins")).size());
    }

    @Test
    @DisplayName("完全不是 JSON：抛出去，由调用方按失败处理")
    void 不是JSON() {
        reply("抱歉，我无法完成");
        assertThrows(IllegalStateException.class, () -> call.askJson("关系发现", 7L, "s", "u", 100));
    }

    @Test
    @DisplayName("多段 text 块按顺序拼起来")
    void 多段文本() {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", "{\"a\":"));
        content.add(Map.of("type", "text", "text", "1}"));
        when(claude.messagesInternal(any(), any())).thenReturn(Map.of("content", content));
        assertEquals(1, call.askJson("x", 7L, "s", "u", 100).get("a"));
    }
}
````

`SemanticRelationDiscoveryTest.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticRelationDiscoveryTest {

    private static final Long CONN_ID = 7L;

    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService semanticService;
    private SemanticModelCall modelCall;
    private SemanticRelationDiscovery discovery;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        semanticService = mock(ConnectorSemanticService.class);
        modelCall = mock(SemanticModelCall.class);
        discovery = new SemanticRelationDiscovery(schemaMapper, semanticMapper, semanticService, modelCall);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setName("erp");
        when(semanticService.requireOwned(CONN_ID)).thenReturn(c);
        when(semanticService.mergeDiscoveredJoins(eq(CONN_ID), any(), any()))
                .thenReturn(new ConnectorSemanticService.DiscoveryWrite(0, 0, 0, 0, 0, List.of()));
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")),
                RelationRuleFixtures.schema("T_ORDER_HEAD", "OID", List.of("OID", "VENDORID", "BILLNO")),
                RelationRuleFixtures.schema("T_ORDER_DTL", "OID", List.of("OID", "SOID", "POID", "MEMO", "ITEMCODE"))));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
    }

    private static Map<String, Object> join(String object, String column, String to, String toColumn, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("object", object);
        m.put("column", column);
        m.put("to_object", to);
        m.put("to_column", toColumn);
        m.put("evidence", evidence);
        m.put("confidence", 70);
        return m;
    }

    @SafeVarargs
    private void modelReplies(Map<String, Object>... joins) {
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000)))
                .thenReturn(Map.of("joins", List.of(joins)));
    }

    @SuppressWarnings("unchecked")
    private List<ConnectorSemantic> written() {
        ArgumentCaptor<List<ConnectorSemantic>> rows = ArgumentCaptor.forClass(List.class);
        verify(semanticService).mergeDiscoveredJoins(eq(CONN_ID), rows.capture(), any());
        return rows.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(ConnectorSemantic row) throws Exception {
        return CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
    }

    private List<String> userPrompts(int times) {
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(modelCall, times(times)).askJson(anyString(), eq(CONN_ID), anyString(), user.capture(), eq(8000));
        return user.getAllValues();
    }

    @Test
    @DisplayName("★ 规则候选与模型产出一起写：来源各自标好，模型越界、GUESS、终点不存在的都丢掉")
    void 规则与模型() throws Exception {
        modelReplies(
                join("T_ORDER_DTL", "SOID", "T_ORDER_HEAD", "OID", "NAME"),
                join("T_ORDER_HEAD", "VENDORID", "T_ORDER_DTL", "OID", "NAME"),
                join("T_ORDER_DTL", "POID", "T_ORDER_HEAD", "OID", "GUESS"),
                join("T_ORDER_DTL", "ITEMCODE", "M_ITEM", "CODE", "COMMENT"));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        List<ConnectorSemantic> rows = written();
        assertEquals(2, rows.size());
        ConnectorSemantic rule = rows.stream().filter(r -> r.getFieldName().equals("VENDORID")).findFirst().orElseThrow();
        assertEquals("M_VENDOR", detail(rule).get("to_object"));
        assertEquals(RelationCandidates.ORIGIN_NAME_RULE, detail(rule).get("origin"));
        assertEquals(ConnectorSemanticService.EV_NAME, rule.getEvidence());
        ConnectorSemantic model = rows.stream().filter(r -> r.getFieldName().equals("SOID")).findFirst().orElseThrow();
        assertEquals("T_ORDER_HEAD", detail(model).get("to_object"));
        assertEquals(RelationCandidates.ORIGIN_RELATION_PASS, detail(model).get("origin"));
        assertEquals(ConnectorSemanticService.V_NONE, model.getVerified());
        assertEquals(1, result.nameRuleCandidates());
        assertEquals(3, result.modelProposed(), "越界的那一条在进组装之前就丢了");
        assertEquals(1, result.modelAccepted());
        assertTrue(result.modelAsked());
        assertNotNull(result.passFingerprint());
    }

    @Test
    @DisplayName("★ 模型自己都没把握（低于 60 或没报）的关联不收：真实库上它会把找不到对象的列硬指向公司表的主键")
    void 没把握的不收() {
        Map<String, Object> weak = join("T_ORDER_DTL", "SOID", "M_VENDOR", "OID", "NAME");
        weak.put("confidence", 40);
        weak.put("note", "库中无对应主数据表，宁缺毋滥");
        Map<String, Object> silent = join("T_ORDER_DTL", "ITEMCODE", "M_VENDOR", "OID", "NAME");
        silent.remove("confidence");
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000)))
                .thenReturn(Map.of("joins", List.of(weak, silent), "unresolved", List.of("T_ORDER_DTL.POID")));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        assertEquals(1, written().size(), "只剩规则推出的那一条");
        assertEquals(0, result.modelProposed());
    }

    @Test
    @DisplayName("待判列：像引用、不是本表单列唯一键、还没有关系也没有规则候选；每片都带全部的表")
    void 待判列() {
        modelReplies();
        ConnectorSemantic existing = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, "T_ORDER_DTL", "POID", "");
        existing.setDetailJson("{\"to_object\":\"T_ORDER_HEAD\",\"to_column\":\"OID\"}");
        when(semanticMapper.selectList(any())).thenReturn(List.of(existing));

        discovery.discover(CONN_ID, null);

        String user = userPrompts(1).get(0);
        assertTrue(user.contains("T_ORDER_DTL.SOID |"), user);
        assertTrue(user.contains("T_ORDER_DTL.ITEMCODE |"), user);
        assertTrue(user.contains("T_ORDER_HEAD.BILLNO |"), user);
        assertFalse(user.contains("T_ORDER_DTL.POID |"), "已经有关系的列不再问");
        assertFalse(user.contains("T_ORDER_HEAD.VENDORID |"), "规则已经推出来的列不再问");
        assertFalse(user.contains("T_ORDER_DTL.OID |"), "本表自己的主键不是引用");
        assertFalse(user.contains("T_ORDER_DTL.MEMO |"), "不像引用的列不问");
        assertTrue(user.contains("T_ORDER_DTL.POID → T_ORDER_HEAD.OID"), "已知关联作为上下文给出");
        for (String table : List.of("M_VENDOR", "T_ORDER_HEAD", "T_ORDER_DTL")) {
            assertTrue(user.contains(table + " | "), "目标索引缺 " + table);
        }
    }

    @Test
    @DisplayName("一片最多问 200 列：450 列分 3 片，每一片都带完整的目标索引")
    void 按列数分片() {
        modelReplies();
        List<String> wide = new ArrayList<>();
        wide.add("OID");
        for (int i = 1; i <= 450; i++) {
            wide.add(String.format("C%04d_CODE", i));
        }
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")),
                RelationRuleFixtures.schema("T_WIDE", "OID", wide)));

        discovery.discover(CONN_ID, null);

        List<String> prompts = userPrompts(3);
        for (String user : prompts) {
            assertTrue(user.contains("M_VENDOR | ") && user.contains("T_WIDE | "), "有一片看不见全部的表");
        }
        assertTrue(prompts.get(2).contains("T_WIDE.C0450_CODE |"));
    }

    @Test
    @DisplayName("待判列装不下一片（6 万字符）：分片，每一片都带完整的目标索引")
    void 分片() {
        modelReplies();
        List<String> wide = new ArrayList<>();
        wide.add("OID");
        for (int i = 1; i <= 180; i++) {
            wide.add(String.format("C%04d_CODE", i));
        }
        ConnectorSchema wideTable = RelationRuleFixtures.schema("T_WIDE", "OID", wide);
        // 注释在待判列里截到 60 个字，一行约 80 字符；再把目标索引撑长，180 列就装不进一片。
        wideTable.setDetailJson(wideTable.getDetailJson().replace("\"comment\":\"\"",
                "\"comment\":\"" + "这一列的注释写得很长".repeat(10) + "\""));
        wideTable.setObjectComment("宽表");
        List<ConnectorSchema> schemas = new ArrayList<>();
        schemas.add(RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")));
        schemas.add(wideTable);
        // 1200 张只有主键的表：目标索引约 6 万字符，留给待判列的只剩最低保底的 4000 字符，180 列要分好几片。
        for (int i = 0; i < 1200; i++) {
            ConnectorSchema t = RelationRuleFixtures.schema(String.format("T_PAD_%04d", i), "OID", List.of("OID"));
            t.setObjectComment("填充用的一张表，只为把目标索引撑长到几万字符那么长");
            schemas.add(t);
        }
        when(schemaMapper.selectList(any())).thenReturn(schemas);

        discovery.discover(CONN_ID, null);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(modelCall, atLeast(2)).askJson(anyString(), eq(CONN_ID), anyString(), user.capture(), eq(8000));
        List<String> prompts = user.getAllValues();
        for (String p : prompts) {
            assertTrue(p.contains("M_VENDOR | ") && p.contains("T_WIDE | ") && p.contains("T_PAD_1199 | "),
                    "有一片看不见全部的表");
        }
        assertTrue(prompts.get(0).contains("T_WIDE.C0001_CODE |")
                && prompts.get(prompts.size() - 1).contains("T_WIDE.C0180_CODE |"));
    }

    @Test
    @DisplayName("模型的输入没变：不再问，规则候选照写")
    void 输入没变不再问() {
        modelReplies();
        String fingerprint = discovery.discover(CONN_ID, null).passFingerprint();

        SemanticRelationDiscovery.Result again = discovery.discover(CONN_ID, fingerprint);

        verify(modelCall, times(1)).askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000));
        assertFalse(again.modelAsked());
        assertEquals(fingerprint, again.passFingerprint());
        verify(semanticService, times(2)).mergeDiscoveredJoins(eq(CONN_ID), any(), any());
    }

    @Test
    @DisplayName("★ 上一轮自己写下的关系不算输入变化：规则候选和模型产出落库之后因为别的原因重跑，不再问模型")
    void 自己写下的不算变化() {
        // 上一个用例的假库两次都是空的，等于假设第一轮什么都没写；这里让写进去的行下一轮读得到，和真库一样。
        List<ConnectorSemantic> db = new ArrayList<>();
        when(semanticMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(db));
        when(semanticService.mergeDiscoveredJoins(eq(CONN_ID), any(), any())).thenAnswer(inv -> {
            List<ConnectorSemantic> rows = inv.getArgument(1);
            for (ConnectorSemantic r : rows) {
                db.removeIf(d -> d.getObjectName().equals(r.getObjectName())
                        && d.getFieldName().equals(r.getFieldName()));
                db.add(r);
            }
            return new ConnectorSemanticService.DiscoveryWrite(rows.size(), 0, 0, 0, 0, List.of());
        });
        modelReplies(join("T_ORDER_DTL", "SOID", "T_ORDER_HEAD", "OID", "NAME"));
        String fingerprint = discovery.discover(CONN_ID, null).passFingerprint();

        SemanticRelationDiscovery.Result again = discovery.discover(CONN_ID, fingerprint);

        verify(modelCall, times(1)).askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000));
        assertFalse(again.modelAsked(), "答过的列和没把握不答的列都不该再问一遍");
        assertEquals(fingerprint, again.passFingerprint());
    }

    @Test
    @DisplayName("★ 模型失败：规则候选照样落库；指纹不前移，下次照样再问")
    void 模型失败() {
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000)))
                .thenThrow(new IllegalStateException("模型回复解析失败"));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, "old");

        assertEquals(1, written().size());
        assertEquals("模型回复解析失败", result.modelError());
        assertEquals("old", result.passFingerprint());
        assertTrue(result.note().contains("模型那一遍失败"), result.note());
    }

    @Test
    @DisplayName("没有待判列：不调模型")
    void 没有待判列() {
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                RelationRuleFixtures.schema("M_VENDOR", "OID", List.of("OID", "NAME")),
                RelationRuleFixtures.schema("T_ORDER", "OID", List.of("OID", "VENDORID"))));

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        verify(modelCall, never()).askJson(anyString(), any(), anyString(), anyString(), eq(8000));
        assertFalse(result.modelAsked());
        assertNull(result.modelError());
        assertEquals(1, written().size());
    }

    @Test
    @DisplayName("声明的外键：依据标 COMMENT，来源标 FK")
    void 外键依据() throws Exception {
        modelReplies();
        List<ConnectorSchema> schemas = new ArrayList<>(List.of(
                RelationRuleFixtures.schema("M_PARTNER", "OID", List.of("OID")),
                RelationRuleFixtures.schema("T_ORDER", "OID", List.of("OID", "BUYER"))));
        schemas.get(1).setDetailJson(schemas.get(1).getDetailJson().replace("\"unique_keys\":",
                "\"foreign_keys\":[{\"name\":\"fk\",\"columns\":[\"BUYER\"],\"ref_table\":\"M_PARTNER\","
                        + "\"ref_columns\":[\"OID\"]}],\"unique_keys\":"));
        when(schemaMapper.selectList(any())).thenReturn(schemas);

        SemanticRelationDiscovery.Result result = discovery.discover(CONN_ID, null);

        ConnectorSemantic row = written().get(0);
        assertEquals(ConnectorSemanticService.EV_COMMENT, row.getEvidence());
        assertEquals(RelationCandidates.ORIGIN_FK, detail(row).get("origin"));
        assertEquals(1, result.foreignKeyCandidates());
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='SemanticModelCallTest*,SemanticRelationDiscoveryTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `SemanticModelCall`、`SemanticRelationDiscovery`。

- [ ] **Step 3: 写实现**

`SemanticModelCall.java`：

````java
package com.jimeng.dataserver.ai.connector.service;

import cn.hutool.json.JSONObject;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补全链（关系发现、业务文字）要一个 JSON 回来的那一次模型调用（数据星图设计 v3 §5.3、§6.2）。
 *
 * <p>和语义层推导同一个模型、同一种调法：{@code connector.semantic.infer-model}，经
 * {@link ClaudeService#messagesInternal}——不带任何工具（客户的表名列名不能被模型拿去 web_search）、只调一轮。
 * 超时沿用推导的 {@code connector.semantic.model-timeout-seconds}（{@link ConnectorSemanticDeriveService#modelTimeout}）。
 *
 * <p>这里不能并进 {@link ConnectorSemanticService}：那个类被 {@code ConnectorToolExecutor} 注入，
 * 碰到 {@code ClaudeService} 会闭合启动期的构造循环（见那个类的注释）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticModelCall {

    private final ClaudeService claudeService;
    private final ConnectorProperties properties;

    /** 留空即不下发 model，由 provider 回落到默认模型（同推导，见 {@code ConnectorSemanticDeriveService#inferModel}）。 */
    @Value("${connector.semantic.infer-model:}")
    private String inferModel;

    /** 本次会用的模型名；{@code null} = 没配，回落 provider 默认模型。业务视图把它记进 {@code model_code}。 */
    public String modelCode() {
        return inferModel == null || inferModel.isBlank() ? null : inferModel.trim();
    }

    /**
     * 调一次模型并把回复解析成一个 JSON 对象。
     *
     * @param what 日志开头那几个字（「关系发现」「业务领域」……）
     * @throws IllegalStateException 回复里找不到能解析的 JSON 对象。调用方按这一步失败处理，不要吞成「没有结果」
     */
    public Map<String, Object> askJson(String what, Long connectorId, String system, String user, int maxTokens) {
        // 必须用【可变】集合：下游 ModelResolver / GenericChatClient 会就地改写 body 与 messages，
        // Map.of / List.of 在那里抛 UnsupportedOperationException，而它的 getMessage() 是 null。
        Map<String, Object> body = new LinkedHashMap<>();
        String model = modelCode();
        if (model != null) {
            body.put("model", model);
        }
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", user);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);
        body.put("messages", messages);

        Duration timeout = ConnectorSemanticDeriveService.modelTimeout(properties.getSemantic());
        log.info("{} connectorId={} 请求模型={} 输入={}字符 max_tokens={} 超时={}秒", what, connectorId,
                model == null ? "(未配 connector.semantic.infer-model，回落 provider 默认模型)" : model,
                user == null ? 0 : user.length(), maxTokens, timeout.getSeconds());
        Object resp = claudeService.messagesInternal(body, timeout);
        return parse(extractText(resp));
    }

    /** 从 Anthropic messages 响应里抽 content[].text。与 {@code ConnectorSemanticDeriveService.extractText} 同形。 */
    private static String extractText(Object resp) {
        try {
            JSONObject j = new JSONObject(resp);
            var content = j.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; content != null && i < content.size(); i++) {
                JSONObject blk = content.getJSONObject(i);
                if ("text".equals(blk.getStr("type"))) {
                    sb.append(blk.getStr("text"));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(resp);
        }
    }

    /** 去掉 markdown 围栏和正文前的闲话；被 max_tokens 截断的收尾到最后一个完整条目（同推导的 {@code parseJson}）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end).trim();
            }
        }
        int a = s.indexOf('{');
        if (a < 0) {
            throw new IllegalStateException("模型回复里没有 JSON 对象：" + abbrev(raw));
        }
        s = s.substring(a);
        int fence = s.lastIndexOf("```");
        if (fence > 0) {
            s = s.substring(0, fence).trim();
        }
        try {
            return CommonUtil.getObjectMapper().readValue(s, Map.class);
        } catch (Exception first) {
            String repaired = ConnectorSemanticDeriveService.repairTruncatedJson(s);
            try {
                if (!repaired.equals(s)) {
                    log.warn("补全链的模型输出被截断，已收尾到最后一个完整条目：原长 {} 字符，修复后 {} 字符",
                            s.length(), repaired.length());
                    return CommonUtil.getObjectMapper().readValue(repaired, Map.class);
                }
            } catch (Exception ignored) {
                // 落到下面统一报错
            }
            throw new IllegalStateException("模型回复解析失败：" + abbrev(raw), first);
        }
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "(空)";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…(" + s.length() + ")";
    }
}
````

`SemanticRelationDiscovery.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer.NamedKey;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.RelationCandidates.Candidate;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;

/**
 * 语义层的关系发现（数据星图设计 v3 §5）：规则候选 + 整库模型一遍 → 新增或替换 JOIN 行。
 *
 * <h3>为什么要单独一遍</h3>
 * 语义层推导按 6 万字符分批、按表名字母序装批，提示词要求「没出现的表不要写关系」，跨批次的关系在规则上就被排除了；
 * 输出顺序又是 表用途 → 字段含义 → 关系，max_tokens 一截断最先丢关系。这一遍只做关系，而且<b>每一片都带着全部表的目标索引</b>，
 * 保证模型看得见所有可能的终点。
 *
 * <h3>三步</h3>
 * <ol>
 *   <li>确定性候选：声明的外键、命名规律（{@link RelationCandidates}），不花一分钱；</li>
 *   <li>模型一遍：只问「像引用、还没有关系、也没有规则候选」的列。输入没变（{@code lastPassFingerprint}）就不再问；</li>
 *   <li>全部经 {@link SemanticRowAssembler#toRows} 的同一套过滤（两端都在快照里、丢 GUESS、每列去重），
 *       再交给 {@link ConnectorSemanticService#mergeDiscoveredJoins} 按 §5.4 的表写入。</li>
 * </ol>
 * 模型那一遍失败不影响规则候选落库：失败原因记进结果，指纹不前移，下次照样再问。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRelationDiscovery {

    /** 模型那一遍的提示词版本。改了提示词就改它：它在指纹里，改了之后每条连接都会重问一次。 */
    static final String PROMPT_VERSION = "relation-pass-2026-10-01c";
    /** 每片交给模型的最大字符数（含目标索引、已知关系和待判列）。 */
    static final int PASS_MAX_CHARS = 60000;
    static final int PASS_MAX_TOKENS = 8000;
    /** 待判列：列名（去掉 {@code _}、转小写）以这些结尾的才像引用。 */
    static final List<String> REFERENCE_SUFFIXES = List.of("id", "code", "no", "num", "key");
    /** 一片里「已知关系」最多占多少字符：它只是给模型的上下文，不能挤掉待判列。 */
    static final int KNOWN_MAX_CHARS = 8000;
    /** 目标索引本身就很长（表多、注释长）时，每片至少还留这么多给待判列：宁可一片超一点，也不要一列一片地问。 */
    static final int MIN_CHUNK_CHARS = 4000;
    /** 每片最多问多少列：答案也要装进 {@link #PASS_MAX_TOKENS}，一片问得太多，回答会被截断。 */
    static final int MAX_CHUNK_COLUMNS = 200;
    /**
     * 模型自己报的把握低于这个数的关联不收（没报的也不收）。
     *
     * <p>这不是拿模型的自信当依据——依据仍是 NAME / COMMENT。它挡的是「占位条目」：2026-10-01 用真实库跑，模型会把找不到对象的列
     * 也写进 joins，随手指向公司代码表的主键，备注写着「库中无对应主数据表」「宁缺毋滥」「仅为占位」，把握报 0–50。
     * 高于这个数的也不全对（同一次试跑里「币种 → 公司代码」报了 70），所以这一遍的产出只进语义层当线索：
     * 星图要等采样核对通过、或业务方确认之后才画（{@code DataGraphProjector} 判定表第 7′ 行）。
     */
    static final int MIN_MODEL_CONFIDENCE = 60;

    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorSemanticService semanticService;
    private final SemanticModelCall modelCall;

    /**
     * 跑一遍关系发现。必须在真实租户上下文里调（写库靠租户拦截器，见 {@link ConnectorSemanticService#requireOwned}）。
     *
     * @param lastPassFingerprint 上次模型那一遍的输入指纹；与这次相同就不再问模型
     */
    public Result discover(Long connectorId, String lastPassFingerprint) {
        Connection conn = semanticService.requireOwned(connectorId);
        List<ConnectorSchema> schemas = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId));
        List<ConnectorSchema> objects = schemas.stream()
                .filter(r -> r != null && r.getObjectName() != null)
                .sorted(Comparator.comparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(objects);

        // ① 确定性候选
        List<Candidate> rules = RelationCandidates.of(objects);

        // ② 模型一遍：只问还没有关系、也没有规则候选的「像引用」的列
        PassInput input = passInput(objects, fields, selectSemantic(connectorId), rules);

        List<Map<String, Object>> proposed = new ArrayList<>();
        boolean asked = false;
        String modelError = null;
        if (!input.pending().isEmpty() && !input.fingerprint().equals(lastPassFingerprint)) {
            asked = true;
            try {
                proposed = askModel(connectorId, conn, input.index(), input.known(), input.pending());
            } catch (RuntimeException e) {
                modelError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("关系发现的模型那一遍失败，规则候选照常写入 connectorId={}: {}", connectorId, modelError, e);
            }
        }

        // ③ 组装：规则候选与模型产出走同一套过滤
        Map<String, String> originByColumn = new HashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Candidate c : rules) {
            entries.add(ruleEntry(c));
            originByColumn.put(columnKey(c.fromTable(), c.fromColumn()), c.origin());
        }
        int accepted = 0;
        for (Map<String, Object> m : proposed) {
            entries.add(m);
            originByColumn.putIfAbsent(columnKey(SemanticRowAssembler.str(m, "object"),
                    SemanticRowAssembler.str(m, "column")), RelationCandidates.ORIGIN_RELATION_PASS);
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put(SemanticRowAssembler.KIND_JOINS, entries);
        SemanticRowAssembler.AssemblyReport report = new SemanticRowAssembler.AssemblyReport();
        List<ConnectorSemantic> rows = SemanticRowAssembler.toRows(parsed, fields, Set.of(), report, List.of());
        for (ConnectorSemantic row : rows) {
            String origin = originByColumn.get(columnKey(row.getObjectName(), row.getFieldName()));
            row.setDetailJson(withOrigin(row.getDetailJson(), origin));
            if (RelationCandidates.ORIGIN_RELATION_PASS.equals(origin)) {
                accepted++;
            }
        }

        // ④ 写入
        ConnectorSemanticService.DiscoveryWrite write =
                semanticService.mergeDiscoveredJoins(connectorId, rows, singleUniqueColumns(objects));
        // 存下的指纹按写完之后的样子算：这一遍自己写下的关系（规则候选、模型产出）下一轮会进「已知关系」、把那些列挪出待判列。
        // 按写之前的输入存，下一轮不管因为什么重跑（刷新结构、采样核对、业务方确认）都对不上，模型答过的列和没把握不答的列
        // 会被再问一遍，多出来的随机产出还要再去客户库上采样核对。模型那一遍失败时仍存上一次的，下次照样再问。
        String passFingerprint = modelError == null
                ? passInput(objects, fields, selectSemantic(connectorId), rules).fingerprint()
                : lastPassFingerprint;
        int fk = (int) rules.stream().filter(c -> RelationCandidates.ORIGIN_FK.equals(c.origin())).count();
        Result result = new Result(fk, rules.size() - fk, proposed.size(), accepted, asked, passFingerprint,
                modelError, write);
        log.info("关系发现完成 connectorId={} {}", connectorId, result.note());
        return result;
    }

    private List<ConnectorSemantic> selectSemantic(Long connectorId) {
        return semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .in(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT,
                        ConnectorSemanticService.SCOPE_JOIN));
    }

    /** 模型那一遍的输入：目标索引、已知关系（语义层现有的 JOIN 行 + 规则候选）、待判列，以及由这三样算出的指纹。 */
    private static PassInput passInput(List<ConnectorSchema> objects, Map<String, Map<String, FieldDetail>> fields,
                                       List<ConnectorSemantic> semantic, List<Candidate> rules) {
        Set<String> covered = new HashSet<>();
        List<String> known = new ArrayList<>();
        for (ConnectorSemantic j : semantic) {
            if (ConnectorSemanticService.SCOPE_JOIN.equals(j.getScope())) {
                covered.add(columnKey(j.getObjectName(), j.getFieldName()));
                String[] to = target(j);
                if (to != null) {
                    known.add(j.getObjectName() + "." + j.getFieldName() + " → " + to[0] + "." + to[1]);
                }
            }
        }
        for (Candidate c : rules) {
            covered.add(columnKey(c.fromTable(), c.fromColumn()));
            known.add(c.fromTable() + "." + c.fromColumn() + " → " + c.toTable() + "." + c.toColumn());
        }
        List<Pending> pending = pending(objects, fields, covered);
        String index = targetIndex(objects, semantic);
        return new PassInput(index, known, pending, fingerprint(index, known, pending));
    }

    private record PassInput(String index, List<String> known, List<Pending> pending, String fingerprint) {
    }

    // ------------------------------------------------------------------ 模型一遍

    private List<Map<String, Object>> askModel(Long connectorId, Connection conn, String index, List<String> known,
                                               List<Pending> pending) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<List<Pending>> chunks = chunks(index, pending);
        for (int i = 0; i < chunks.size(); i++) {
            List<Pending> chunk = chunks.get(i);
            Set<String> tables = new HashSet<>();
            Set<String> allowed = new HashSet<>();
            for (Pending p : chunk) {
                tables.add(p.table());
                allowed.add(p.table() + '\u0001' + p.column());
            }
            String user = userPrompt(conn, index, knownFor(known, tables), chunk, i + 1, chunks.size());
            Map<String, Object> reply = modelCall.askJson("关系发现（第 " + (i + 1) + "/" + chunks.size() + " 片）",
                    connectorId, SYSTEM_PROMPT, user, PASS_MAX_TOKENS);
            for (Map<String, Object> m : SemanticRowAssembler.arr(reply, SemanticRowAssembler.KIND_JOINS)) {
                String object = SemanticRowAssembler.str(m, "object");
                String column = SemanticRowAssembler.str(m, "column");
                // 只收这一片问到的列：模型顺手写的别的列，既没有经过「还没有关系」的筛选，也可能改写已有的关系。
                if (object == null || column == null || !allowed.contains(object + '\u0001' + column)) {
                    continue;
                }
                String toObject = SemanticRowAssembler.str(m, "to_object");
                String toColumn = SemanticRowAssembler.str(m, "to_column");
                if (object.equals(toObject) && column.equals(toColumn)) {
                    continue;
                }
                Integer confidence = SemanticRowAssembler.confidence(m);
                if (confidence == null || confidence < MIN_MODEL_CONFIDENCE) {
                    continue;
                }
                out.add(m);
            }
        }
        return out;
    }

    /** 按待判列分片，每一片都带完整的目标索引。 */
    private List<List<Pending>> chunks(String index, List<Pending> pending) {
        int budget = Math.max(MIN_CHUNK_CHARS, PASS_MAX_CHARS - index.length() - KNOWN_MAX_CHARS);
        List<List<Pending>> out = new ArrayList<>();
        List<Pending> current = new ArrayList<>();
        int size = 0;
        for (Pending p : pending) {
            int line = p.line().length() + 1;
            if (!current.isEmpty() && (size + line > budget || current.size() >= MAX_CHUNK_COLUMNS)) {
                out.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(p);
            size += line;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    static final String SYSTEM_PROMPT = """
            你在帮一家企业整理他们业务数据库里「表与表之间的关联」。给你三样东西：
            1. 这个库里全部的表（表名 | 表注释 | 一句话说明 | 唯一键）；
            2. 已经知道的关联，不要重复写；
            3. 一批还没找到关联对象的列（表.列 | 列注释）。

            请判断每一个待判列是不是在引用另一张表的某一列。只输出一个 JSON 对象，不要任何别的文字：
            {"joins":[{"object":"起点表","column":"起点列","to_object":"终点表","to_column":"终点列",
            "evidence":"NAME 或 COMMENT","confidence":0到100的整数,"note":"不超过 20 个字的理由"}]}

            规则：
            - 只写你能从列名或列注释里明确看出指向的关联，宁缺毋滥。看不出来的列直接不写，不要写成低把握的条目。
            - 终点表必须就是这一列所说的那个业务对象本身（比如「付款方」「售达方」指向客户表），不能只是「有点相关」的表。
              这一列说的对象（比如工厂、币种、物料、部门、客户端）如果在清单里没有对应的表，就不要写，不要拿公司、组织这类表凑数。
            - 只为待判列写关联；表名、列名必须原样照抄给你的写法（大小写敏感），不要补前缀。
            - 终点列通常是终点表的主键或唯一键。
            - 明细表指向所属单据头的列也要找出来：表名成对出现（例如「某某明细」与「某某单据头」、XXX_DTL 与 XXX_HEAD），
              明细表里常用 SOID、POID、HEADID、BILLID 这类列指向单据头的主键；看得出是哪一列才写。
            - evidence 只能是 NAME（列名与表名的命名规律，包括表名成对出现的规律）或 COMMENT（列注释写明了指向哪里）。
              不要写 GUESS。
            - 每一列最多写一个终点；不要让一列指向它自己。
            """;

    private static String userPrompt(Connection conn, String index, String known, List<Pending> chunk,
                                     int no, int total) {
        StringBuilder s = new StringBuilder();
        s.append("连接：").append(conn.getName() == null ? "(未命名)" : conn.getName());
        if (total > 1) {
            s.append("（待判列分 ").append(total).append(" 片给你，这是第 ").append(no).append(" 片；表的清单每片都是全的）");
        }
        s.append("\n\n【全部的表】（表名 | 表注释 | 一句话说明 | 唯一键）\n").append(index);
        s.append("\n【已知关联】\n").append(known.isEmpty() ? "（无）\n" : known);
        s.append("\n【待判列】（表.列 | 列注释）\n");
        for (Pending p : chunk) {
            s.append(p.line()).append('\n');
        }
        return s.toString();
    }

    /** 只带和这一片待判列同表的已知关联，而且有上限：它是上下文，不能挤掉待判列。 */
    private static String knownFor(List<String> known, Set<String> tables) {
        StringBuilder s = new StringBuilder();
        for (String k : known) {
            String from = k.substring(0, Math.max(0, k.indexOf('.')));
            if (!tables.contains(from)) {
                continue;
            }
            if (s.length() + k.length() + 1 > KNOWN_MAX_CHARS) {
                break;
            }
            s.append(k).append('\n');
        }
        return s.toString();
    }

    /** 全部表的目标索引：表名 | 表注释（去掉平台加的估算行数）| 语义层一句话说明 | 唯一键。 */
    private static String targetIndex(List<ConnectorSchema> objects, List<ConnectorSemantic> semantic) {
        Map<String, String> gloss = new HashMap<>();
        for (ConnectorSemantic s : semantic) {
            if (ConnectorSemanticService.SCOPE_OBJECT.equals(s.getScope()) && s.getGloss() != null) {
                gloss.putIfAbsent(fold(s.getObjectName()), ConnectorSemanticService.shortGloss(s.getGloss()));
            }
        }
        StringBuilder s = new StringBuilder();
        for (ConnectorSchema r : objects) {
            s.append(r.getObjectName()).append(" | ")
                    .append(oneLine(RowEstimateNote.strip(r.getObjectComment()), 40)).append(" | ")
                    .append(oneLine(gloss.get(fold(r.getObjectName())), 80)).append(" | ")
                    .append(keysText(SemanticTableRenderer.namedUniqueKeys(r))).append('\n');
        }
        return s.toString();
    }

    private static String keysText(List<NamedKey> keys) {
        if (keys == null) {
            return "未知";
        }
        if (keys.isEmpty()) {
            return "无";
        }
        List<String> out = new ArrayList<>();
        for (NamedKey k : keys) {
            out.add((k.primary() ? "主键" : "唯一") + "(" + String.join(",", k.columns()) + ")");
        }
        return String.join(" ", out);
    }

    /** 待判列：像引用（名字以 id/code/no/num/key 结尾）、不是本表的单列唯一键、还没有关系、也没有规则候选。 */
    private static List<Pending> pending(List<ConnectorSchema> objects, Map<String, Map<String, FieldDetail>> fields,
                                         Set<String> covered) {
        List<Pending> out = new ArrayList<>();
        for (ConnectorSchema r : objects) {
            Set<String> ownKeys = new HashSet<>();
            List<NamedKey> keys = SemanticTableRenderer.namedUniqueKeys(r);
            if (keys != null) {
                for (NamedKey k : keys) {
                    if (k.columns().size() == 1) {
                        ownKeys.add(fold(k.columns().get(0)));
                    }
                }
            }
            for (FieldDetail f : fields.getOrDefault(r.getObjectName(), Map.of()).values()) {
                String n = RelationCandidates.norm(f.name());
                if (REFERENCE_SUFFIXES.stream().noneMatch(n::endsWith) || ownKeys.contains(fold(f.name()))
                        || covered.contains(columnKey(r.getObjectName(), f.name()))) {
                    continue;
                }
                out.add(new Pending(r.getObjectName(), f.name(),
                        r.getObjectName() + "." + f.name() + " | " + oneLine(f.comment(), 60)));
            }
        }
        return out;
    }

    private static String fingerprint(String index, List<String> known, List<Pending> pending) {
        StringBuilder s = new StringBuilder(PROMPT_VERSION).append('\n').append(index).append('\n');
        known.stream().sorted().forEach(k -> s.append(k).append('\n'));
        s.append('\n');
        for (Pending p : pending) {
            s.append(p.line()).append('\n');
        }
        return ConnectorSemanticService.sha256(s.toString());
    }

    // ------------------------------------------------------------------ 组装

    /** 规则候选写成与模型产出同形的条目：外键是客户写在库里的约束（COMMENT），命名规律是名字像（NAME）。 */
    private static Map<String, Object> ruleEntry(Candidate c) {
        boolean fk = RelationCandidates.ORIGIN_FK.equals(c.origin());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("object", c.fromTable());
        m.put("column", c.fromColumn());
        m.put("to_object", c.toTable());
        m.put("to_column", c.toColumn());
        m.put("cardinality", "N:1");
        m.put("evidence", fk ? ConnectorSemanticService.EV_COMMENT : ConnectorSemanticService.EV_NAME);
        m.put("confidence", fk ? 95 : 80);
        m.put("note", fk ? "数据库里声明了这条外键" : "按列名与表名的命名规律推出");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String withOrigin(String detailJson, String origin) {
        if (origin == null) {
            return detailJson;
        }
        try {
            Map<String, Object> d = new LinkedHashMap<>(
                    CommonUtil.getObjectMapper().readValue(detailJson, Map.class));
            d.put(ConnectorSemanticService.KEY_ORIGIN, origin);
            String json = SemanticRowAssembler.toJson(d);
            return json == null ? detailJson : json;
        } catch (Exception e) {
            return detailJson;
        }
    }

    /** 快照里每张表的单列唯一键（表名、列名折叠过）。唯一键未知的表不进来：写入侧按「终点不是唯一键」处理。 */
    private static Map<String, Set<String>> singleUniqueColumns(List<ConnectorSchema> objects) {
        Map<String, Set<String>> out = new HashMap<>();
        for (ConnectorSchema r : objects) {
            List<NamedKey> keys = SemanticTableRenderer.namedUniqueKeys(r);
            Set<String> cols = new LinkedHashSet<>();
            if (keys != null) {
                for (NamedKey k : keys) {
                    if (k.columns().size() == 1) {
                        cols.add(fold(k.columns().get(0)));
                    }
                }
            }
            out.put(fold(r.getObjectName()), cols);
        }
        return out;
    }

    private static String[] target(ConnectorSemantic j) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = CommonUtil.getObjectMapper()
                    .readValue(j.getDetailJson(), Map.class);
            String to = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_OBJECT);
            String col = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_COLUMN);
            return to == null || col == null ? null : new String[]{to, col};
        } catch (Exception e) {
            return null;
        }
    }

    private static String columnKey(String table, String column) {
        return fold(table) + '\u0001' + fold(column);
    }

    private static String oneLine(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = SemanticRowAssembler.nz(s).trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private record Pending(String table, String column, String line) {
    }

    /**
     * 一次关系发现的结果。
     *
     * @param passFingerprint 下次比对用的模型输入指纹；模型那一遍失败时仍是上一次的（这样下次照样会再问）
     * @param modelError      模型那一遍失败的原因；{@code null} = 没失败（含「没问」）
     */
    public record Result(int foreignKeyCandidates, int nameRuleCandidates, int modelProposed, int modelAccepted,
                         boolean modelAsked, String passFingerprint, String modelError,
                         ConnectorSemanticService.DiscoveryWrite write) {

        /** 写进 {@code connector_enrichment_state.relation_note} 的一句话。只给排查用，不上屏。 */
        public String note() {
            StringBuilder s = new StringBuilder("新增 ").append(write.inserted()).append(" 条、替换 ")
                    .append(write.replaced()).append(" 条；候选：外键 ").append(foreignKeyCandidates)
                    .append("、命名规律 ").append(nameRuleCandidates).append("、模型 ").append(modelAccepted);
            if (!modelAsked) {
                s.append("（模型的输入没变，这次没问）");
            }
            if (modelError != null) {
                s.append("；模型那一遍失败：").append(modelError);
            }
            return s.toString();
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='SemanticModelCallTest*,SemanticRelationDiscoveryTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 两个文件都 `failures=0`；SemanticModelCallTest 6、SemanticRelationDiscoveryTest 10 个用例。

- [ ] **Step 5: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/SemanticModelCall.java \
  modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/SemanticRelationDiscovery.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/SemanticModelCallTest.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/SemanticRelationDiscoveryTest.java && \
git commit -q -F - <<'MSG'
feat(connector): 语义层关系发现——规则候选 + 整库模型一遍

规则候选（外键、命名规律）不花钱；模型只问像引用、还没有关系也没有规则候选的列，
每一片都带全部表的目标索引，输入没变就不再问（指纹按写完之后的样子存，自己写下的关系
不算输入变化）。全部经 SemanticRowAssembler 过滤后按 §5.4 写入；模型那一遍失败不影响规则候选落库。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 7: 业务文字的合格标准（data-service）

纯函数 `BusinessTextRules`（spec §6.2、附录 B）：长度（按字数，中文一个字算一个）、禁用词（AI 口吻和平台内部用语，「可能」不在其中）、代码扫描（本系统的 ASCII 表名、长度 ≥ 4 的 ASCII 列名，按完整单词、不分大小写；中文表名本身就是业务叫法，不算）。模型的产出过 `problem`；兜底时顶替业务文字的客户注释过 `fitsFallback`（同样不许夹代码、带禁用词，但不看字数）。

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/businessview/BusinessTextRules.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/businessview/BusinessTextRulesTest.java`

**Interfaces:**
- Produces（Task 8、10 用）：常量 `NAME_MAX=12, SUMMARY_MAX=50, DOMAIN_MAX=8, ROLE_MAX=10, MAX_DOMAINS=8, UNCLASSIFIED="未分类", BANNED`；`identifiers(Collection<String> tables, Collection<String> columns): Set<String>`；`problem(String text, int maxChars, Set<String> identifiers): String`（`null` = 合格）；`fitsFallback(String text, Set<String> identifiers): boolean`（兜底时能不能拿客户注释顶替业务文字：不为空、不夹代码、不带禁用词，不看字数）。

- [ ] **Step 1: 写失败的测试**

`BusinessTextRulesTest.java`：

```java
package com.jimeng.dataserver.ai.connector.businessview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessTextRulesTest {

    /** 一个系统：表 T_ORDER / 采购单（中文表名）/ ERP；列 VENDORID、NAME、ID、ORDER_NO。 */
    private static final Set<String> IDS = BusinessTextRules.identifiers(
            List.of("T_ORDER", "采购单", "ERP"), List.of("VENDORID", "NAME", "ID", "ORDER_NO"));

    @Test
    @DisplayName("物理标识符：全部 ASCII 表名 + 长度 ≥ 4 的 ASCII 列名，小写；中文表名本身就是业务叫法，不算")
    void 标识符() {
        assertEquals(Set.of("t_order", "erp", "vendorid", "name", "order_no"), IDS);
    }

    @Test
    @DisplayName("合格的业务文字")
    void 合格() {
        assertNull(BusinessTextRules.problem("采购订单明细", BusinessTextRules.NAME_MAX, IDS));
        assertNull(BusinessTextRules.problem("一张订单可能有多条明细，记录下单客户与金额。", BusinessTextRules.SUMMARY_MAX, IDS));
        assertNull(BusinessTextRules.problem("采购单", BusinessTextRules.NAME_MAX, IDS), "中文表名不是代码");
    }

    @Test
    @DisplayName("空白不合格")
    void 空白() {
        assertNotNull(BusinessTextRules.problem("  ", BusinessTextRules.NAME_MAX, IDS));
        assertNotNull(BusinessTextRules.problem(null, BusinessTextRules.NAME_MAX, IDS));
    }

    @Test
    @DisplayName("按字数算长度（中文一个字算一个）")
    void 长度() {
        assertNull(BusinessTextRules.problem("一二三四五六七八九十一二", 12, IDS));
        String problem = BusinessTextRules.problem("一二三四五六七八九十一二三", 12, IDS);
        assertNotNull(problem);
        assertTrue(problem.contains("12"), problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"疑似供应商", "未经核实的订单", "按采样结果", "估算的行数", "按 innodb 统计", "推测是客户",
            "这是语义层里的对象", "订单主键", "外键指向客户", "客户字段"})
    @DisplayName("含附录 B 的禁用词不合格（InnoDB 不分大小写）")
    void 禁用词(String text) {
        assertNotNull(BusinessTextRules.problem(text, 50, IDS), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"按 VENDORID 关联", "见 t_order 表", "客户 Name", "(ORDER_NO)", "ERP里的订单"})
    @DisplayName("含本系统的物理表名或长度 ≥ 4 的列名不合格（按完整单词、不分大小写）")
    void 代码(String text) {
        String problem = BusinessTextRules.problem(text, 50, IDS);
        assertNotNull(problem, text);
        assertTrue(problem.contains("代码"), problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"身份证 ID", "VENDORIDS 之外", "名字 names"})
    @DisplayName("不是完整单词、或列名不足 4 个字符：不算代码")
    void 不算代码(String text) {
        assertNull(BusinessTextRules.problem(text, 50, IDS), text);
    }

    @Test
    @DisplayName("兜底用的注释：不夹代码、不带禁用词、不为空才用；不看字数")
    void 兜底注释() {
        assertTrue(BusinessTextRules.fitsFallback("供应商", IDS));
        assertTrue(BusinessTextRules.fitsFallback("这是一段超过五十个字的客户注释，说明这张表记录了每一张采购订单的抬头信息以及审批流转的全部状态和时间点", IDS));
        assertFalse(BusinessTextRules.fitsFallback("供应商ID（VENDORID）", IDS));
        assertFalse(BusinessTextRules.fitsFallback("估算金额", IDS));
        assertFalse(BusinessTextRules.fitsFallback("  ", IDS));
        assertFalse(BusinessTextRules.fitsFallback(null, IDS));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='BusinessTextRulesTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `BusinessTextRules`。

- [ ] **Step 3: 写实现**

`BusinessTextRules.java`：

```java
package com.jimeng.dataserver.ai.connector.businessview;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给人看的业务文字的合格标准（数据星图设计 v3 §6.2、附录 B）。纯函数。
 *
 * <p>看的人是业务人员和产品：名字要短、要是业务叫法；不能出现表名、字段名这类代码；也不能带 AI 口吻和平台内部用语。
 * 模型的产出、以及兜底时拿来顶替的客户注释，都要过这一关。
 */
public final class BusinessTextRules {

    /** 对象的业务名最多几个字。 */
    public static final int NAME_MAX = 12;
    /** 一句话说明最多几个字。 */
    public static final int SUMMARY_MAX = 50;
    /** 业务领域最多几个字。 */
    public static final int DOMAIN_MAX = 8;
    /** 关系角色名最多几个字。 */
    public static final int ROLE_MAX = 10;
    /** 一个系统最多几个业务领域。 */
    public static final int MAX_DOMAINS = 8;
    /** 没有领域时的兜底。 */
    public static final String UNCLASSIFIED = "未分类";

    /**
     * 附录 B：AI 口吻或平台内部用语。「可能」不在其中——「一张订单可能有多条明细」是正常的业务描述。
     */
    public static final List<String> BANNED = List.of("判不出", "未经", "验证", "样本", "采样", "置信", "估算",
            "InnoDB", "疑似", "或许", "大概", "推测", "语义层", "说明书", "主键", "外键", "字段");

    /** 列名至少几个字符才算「代码」：{@code ID}、{@code NO} 这种太短，拦了会误伤正常说法。表名不论长短都算。 */
    static final int CODE_MIN_COLUMN = 4;

    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_$]+$");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_$]+");

    private BusinessTextRules() {
    }

    /**
     * 一个系统的物理标识符（小写）：全部表名 + 长度 ≥ {@value #CODE_MIN_COLUMN} 的列名。
     * 只收纯 ASCII 的标识符：中文表名、中文列名本身就是业务叫法，不算代码。
     */
    public static Set<String> identifiers(Collection<String> tables, Collection<String> columns) {
        Set<String> out = new HashSet<>();
        for (String t : tables) {
            if (t != null && ASCII_IDENTIFIER.matcher(t).matches()) {
                out.add(t.toLowerCase(Locale.ROOT));
            }
        }
        for (String c : columns) {
            if (c != null && c.length() >= CODE_MIN_COLUMN && ASCII_IDENTIFIER.matcher(c).matches()) {
                out.add(c.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * @return 不合格的原因（给模型重试时原样带回去）；{@code null} = 合格
     */
    public static String problem(String text, int maxChars, Set<String> identifiers) {
        if (text == null || text.isBlank()) {
            return "为空";
        }
        String t = text.trim();
        int length = t.codePointCount(0, t.length());
        if (length > maxChars) {
            return "超过 " + maxChars + " 个字（现在 " + length + " 个）";
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String word : BANNED) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return "含「" + word + "」，这是 AI 口吻或平台内部用语";
            }
        }
        String code = codeIn(t, identifiers);
        if (code != null) {
            return "含代码「" + code + "」，要用业务叫法";
        }
        return null;
    }

    /**
     * 兜底时能不能拿客户注释顶替业务文字（§6.3）：不为空、不夹代码、不带禁用词。不看字数——注释不受业务文字的字数约束，
     * 长短由调用方按用途判断（标题只收「像名称」的短注释）。注释里常夹着字段名，也可能写着「估算」「主键」这类词。
     */
    public static boolean fitsFallback(String text, Set<String> identifiers) {
        return text != null && !text.isBlank() && problem(text, Integer.MAX_VALUE, identifiers) == null;
    }

    /** 按完整单词、不分大小写找物理标识符：单词是连续的字母、数字、下划线、{@code $}。 */
    private static String codeIn(String text, Set<String> identifiers) {
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (identifiers.contains(m.group().toLowerCase(Locale.ROOT))) {
                return m.group();
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='BusinessTextRulesTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  f=modules/data-server/target/surefire-reports/TEST-com.jimeng.dataserver.ai.connector.businessview.BusinessTextRulesTest.xml; \
  echo "cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"
```
Expected: `cases=23 failures=0`

- [ ] **Step 5: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/businessview/BusinessTextRules.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/businessview/BusinessTextRulesTest.java && \
git commit -q -F - <<'MSG'
feat(graph): 业务文字的合格标准——长度、禁用词、代码扫描

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 8: 业务视图生成（data-service）

`BusinessViewGenerator.generate(connectorId, progress)`（spec §6.2–§6.3）分三步：① 业务领域——只给还没有领域的对象分配，第一次是全部对象，以后只给新对象，并把已有的领域清单给模型（一个系统最多 8 个）；② 对象名称与说明——每批 50 个，同一系统里不许重名（表头和明细要分得开）；③ 关系角色名——每批 150 条，喂给模型的是两端对象的**业务名**。

纪律：只补缺的和 `input_hash` 变了的（原样再跑一遍一次模型都不调）；`HUMAN` 行一个字不碰，也不替它补领域；不合格的带着原因重试一次，仍不合格就不写（页面按兜底显示）；对象或关系已经消失的 `MODEL` 行物理删除；每一批写完就落库。起角色名的关系比星图实际画的略宽（终点不是唯一键、还没核对的也在），它们一旦核对通过就会画出来，名字先备好；业务方说无关、采样否掉、已过期的不起名。`progress.beforeModelCall()` 在每次调模型之前回调，补全链用它续期认领。

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/businessview/BusinessViewGenerator.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/businessview/BusinessViewGeneratorTest.java`

**Interfaces:**
- Consumes: Task 5 的实体与 Mapper；Task 6 `SemanticModelCall`；Task 7 `BusinessTextRules`。
- Produces（Task 9 用）：
  - `BusinessViewGenerator.generate(Long connectorId, Progress progress): Result`，模型调用失败时抛出（已写下的批次保留）；
  - `@FunctionalInterface interface BusinessViewGenerator.Progress { void beforeModelCall(); }`；
  - `record BusinessViewGenerator.Result(int domainsAssigned, int namesWritten, int rolesWritten, int rejected, int deleted)`，`note()` 给状态表的 `view_note`。

- [ ] **Step 1: 写失败的测试**

`BusinessViewGeneratorTest.java`（假的业务视图表：insert 像 MyBatis-Plus 一样回填 id；假模型按步骤名回复）：

```java
package com.jimeng.dataserver.ai.connector.businessview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticModelCall;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessViewGeneratorTest {

    private static final Long CONN_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorBusinessViewMapper viewMapper;
    private SemanticModelCall modelCall;
    private BusinessViewGenerator generator;

    /** 假的业务视图表：insert 像 MyBatis-Plus 一样回填 id，selectList 返回当前的全部行。 */
    private final List<ConnectorBusinessView> table = new ArrayList<>();
    /** 每一步（「业务领域」「业务名称」「关系角色名」）收到的用户提示词，按调用顺序。 */
    private final Map<String, List<String>> prompts = new HashMap<>();
    /** 每一步的回复：拿到第几次调用、这次的提示词，返回模型的 JSON。 */
    private final Map<String, Function<Integer, Map<String, Object>>> replies = new HashMap<>();

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorBusinessView.class);
    }

    @BeforeEach
    void setUp() {
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        viewMapper = mock(ConnectorBusinessViewMapper.class);
        modelCall = mock(SemanticModelCall.class);
        ConnectorSemanticService semanticService = mock(ConnectorSemanticService.class);
        generator = new BusinessViewGenerator(schemaMapper, semanticMapper, viewMapper, semanticService, modelCall);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId("t1");
        when(semanticService.requireOwned(CONN_ID)).thenReturn(c);
        when(modelCall.modelCode()).thenReturn("claude-x");

        long[] ids = {100};
        when(viewMapper.insert(any(ConnectorBusinessView.class))).thenAnswer(inv -> {
            ConnectorBusinessView v = inv.getArgument(0);
            v.setId(ids[0]++);
            table.add(v);
            return 1;
        });
        when(viewMapper.update(any(), any())).thenReturn(1);
        when(viewMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(table));
        when(viewMapper.physicalDeleteRow(anyString(), any(), any())).thenAnswer(inv -> {
            Long id = inv.getArgument(2);
            table.removeIf(v -> id.equals(v.getId()));
            return 1;
        });
        when(modelCall.askJson(anyString(), eq(CONN_ID), anyString(), anyString(), eq(8000))).thenAnswer(inv -> {
            String what = inv.getArgument(0);
            prompts.computeIfAbsent(what, k -> new ArrayList<>()).add(inv.getArgument(3));
            return replies.get(what).apply(prompts.get(what).size());
        });

        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schema("M_VENDOR", "供应商表", "OID", "NAME"),
                schema("T_PO_HEAD", "", "OID", "VENDORID"),
                schema("T_PO_DTL", "采购订单明细", "OID", "SOID", "QTY")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(
                object("M_VENDOR", "供应商主数据。"),
                object("T_PO_HEAD", "采购订单表头，一行一张订单。"),
                join("T_PO_HEAD", "VENDORID", "M_VENDOR", "OID", "NONE"),
                join("T_PO_DTL", "SOID", "T_PO_HEAD", "OID", "UNDECIDABLE"),
                join("T_PO_DTL", "QTY", "M_VENDOR", "OID", "REJECTED")));

        replies.put("业务领域", n -> Map.of("domains", List.of("采购", "主数据"), "assign", List.of(
                Map.of("name", "M_VENDOR", "domain", "主数据"),
                Map.of("name", "T_PO_HEAD", "domain", "采购"),
                Map.of("name", "T_PO_DTL", "domain", "采购"))));
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "供应商", "一条记录是一个供应商，记着名称和联系方式。"),
                named("T_PO_HEAD", "采购订单", "一条记录是一张采购订单，记着供应商和下单日期。"),
                named("T_PO_DTL", "采购订单明细", "一条记录是订单里的一行商品，记着数量。"))));
        replies.put("关系角色名", n -> Map.of("relations", List.of(
                Map.of("object", "T_PO_HEAD", "column", "VENDORID", "role", "供应商"),
                Map.of("object", "T_PO_DTL", "column", "SOID", "role", "所属订单"))));
    }

    // ------------------------------------------------------------------ 夹具

    private static ConnectorSchema schema(String name, String comment, String... columns) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String c : columns) {
            fields.add(Map.of("name", c, "type", "bigint", "nullable", true, "comment", ""));
        }
        ConnectorSchema row = new ConnectorSchema();
        row.setConnectorId(CONN_ID);
        row.setObjectType("TABLE");
        row.setObjectName(name);
        row.setObjectComment(comment);
        row.setDetailJson(json(Map.of("name", name, "type", "TABLE", "fields", fields,
                "extra", Map.of("unique_keys", List.of(Map.of("name", "PRIMARY", "primary", true, "columns", List.of("OID")))))));
        return row;
    }

    private static ConnectorSemantic object(String name, String gloss) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope("OBJECT");
        row.setObjectName(name);
        row.setFieldName("");
        row.setGloss(gloss);
        return row;
    }

    private static ConnectorSemantic join(String from, String column, String to, String toColumn, String verified) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope("JOIN");
        row.setObjectName(from);
        row.setFieldName(column);
        row.setDetailJson(json(Map.of("to_object", to, "to_column", toColumn)));
        row.setVerified(verified);
        row.setStatus("DRAFT");
        return row;
    }

    private static Map<String, Object> named(String name, String display, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("display_name", display);
        m.put("summary", summary);
        return m;
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConnectorBusinessView row(String kind, String object, String field) {
        return table.stream().filter(v -> v.getKind().equals(kind) && v.getObjectName().equals(object)
                && v.getFieldName().equals(field)).findFirst().orElse(null);
    }

    private BusinessViewGenerator.Result run() {
        return generator.generate(CONN_ID, () -> { });
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("★ 首次生成：三步各调一次模型；对象有业务名、说明、领域，关系有角色名；采样否掉的关系不起名")
    void 首次生成() {
        BusinessViewGenerator.Result result = run();

        assertEquals(1, prompts.get("业务领域").size());
        assertEquals(1, prompts.get("业务名称").size());
        assertEquals(1, prompts.get("关系角色名").size());
        ConnectorBusinessView vendor = row("OBJECT", "M_VENDOR", "");
        assertEquals("供应商", vendor.getDisplayName());
        assertEquals("主数据", vendor.getDomain());
        assertNotNull(vendor.getSummary());
        assertNotNull(vendor.getInputHash());
        assertEquals("claude-x", vendor.getModelCode());
        assertEquals(BusinessViewGenerator.PROMPT_VERSION, vendor.getPromptVersion());
        assertEquals("t1", vendor.getTenantId());
        assertEquals("MODEL", vendor.getSource());
        assertEquals("所属订单", row("RELATION", "T_PO_DTL", "SOID").getDisplayName());
        assertNull(row("RELATION", "T_PO_DTL", "QTY"), "采样否掉的关系不起名");
        assertFalse(prompts.get("关系角色名").get(0).contains("QTY"));
        assertEquals(3, result.namesWritten());
        assertEquals(2, result.rolesWritten());
        assertEquals(3, result.domainsAssigned());
        assertEquals(0, result.rejected());
    }

    @Test
    @DisplayName("角色名的提示词里用对象的业务名，而不是表名")
    void 角色用业务名() {
        run();
        String prompt = prompts.get("关系角色名").get(0);
        assertTrue(prompt.contains("T_PO_HEAD | VENDORID |  | 采购订单 | 供应商"), prompt);
    }

    @Test
    @DisplayName("★ 不合格的带着原因重试一次，第二次合格就写下")
    void 重试一次() {
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "供应商", "一条记录是一个供应商。"),
                named("T_PO_HEAD", n == 1 ? "T_PO_HEAD 订单" : "采购订单", "一条记录是一张采购订单。"),
                named("T_PO_DTL", "采购订单明细", "一条记录是订单里的一行商品。"))));

        BusinessViewGenerator.Result result = run();

        List<String> asked = prompts.get("业务名称");
        assertEquals(2, asked.size());
        assertTrue(asked.get(1).contains("T_PO_HEAD：业务名含代码「T_PO_HEAD」"), asked.get(1));
        assertFalse(asked.get(1).contains("M_VENDOR |"), "合格的不再问");
        assertEquals("采购订单", row("OBJECT", "T_PO_HEAD", "").getDisplayName());
        assertEquals(0, result.rejected());
    }

    @Test
    @DisplayName("两次都不合格：不写，页面按兜底显示")
    void 两次都不合格() {
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "疑似供应商", "一条记录是一个供应商。"),
                named("T_PO_HEAD", "采购订单", "一条记录是一张采购订单。"),
                named("T_PO_DTL", "采购订单明细", "一条记录是订单里的一行商品。"))));

        BusinessViewGenerator.Result result = run();

        assertNull(row("OBJECT", "M_VENDOR", "").getDisplayName());
        assertEquals(1, result.rejected());
    }

    @Test
    @DisplayName("业务名和别的表重名：退回重起")
    void 重名() {
        replies.put("业务名称", n -> Map.of("objects", List.of(
                named("M_VENDOR", "供应商", "一条记录是一个供应商。"),
                named("T_PO_HEAD", n == 1 ? "采购订单" : "采购订单抬头", "一条记录是一张采购订单。"),
                named("T_PO_DTL", "采购订单", "一条记录是订单里的一行商品。"))));

        run();

        assertEquals("采购订单", row("OBJECT", "T_PO_DTL", "").getDisplayName());
        assertEquals("采购订单抬头", row("OBJECT", "T_PO_HEAD", "").getDisplayName());
        assertTrue(prompts.get("业务名称").get(1).contains("重名"));
    }

    @Test
    @DisplayName("★ 只补缺的和输入变了的：原样再跑一遍不调模型；改了表注释只重起那一张的名字")
    void 只补缺的() {
        run();
        prompts.clear();

        run();
        assertTrue(prompts.isEmpty(), "输入没变，一次模型都不调：" + prompts.keySet());

        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schema("M_VENDOR", "供应商表", "OID", "NAME"),
                schema("T_PO_HEAD", "采购订单头", "OID", "VENDORID"),
                schema("T_PO_DTL", "采购订单明细", "OID", "SOID", "QTY")));
        run();
        assertEquals(1, prompts.get("业务名称").size());
        assertTrue(prompts.get("业务名称").get(0).contains("T_PO_HEAD | 采购订单头"));
        assertFalse(prompts.get("业务名称").get(0).contains("M_VENDOR |"));
        assertNull(prompts.get("业务领域"), "领域只在缺的时候分配");
    }

    @Test
    @DisplayName("★ HUMAN 行一个字不碰：不问它的领域和名字，也不更新它")
    void HUMAN行不动() {
        ConnectorBusinessView human = new ConnectorBusinessView();
        human.setId(1L);
        human.setKind("OBJECT");
        human.setObjectName("M_VENDOR");
        human.setFieldName("");
        human.setDisplayName("我们的供应商");
        human.setSource("HUMAN");
        table.add(human);

        run();

        assertFalse(prompts.get("业务领域").get(0).contains("M_VENDOR |"));
        assertFalse(prompts.get("业务名称").get(0).contains("M_VENDOR |"));
        assertTrue(prompts.get("业务名称").get(0).contains("我们的供应商"), "人起的名字算已用过的名字");
        assertEquals("我们的供应商", human.getDisplayName());
        assertNull(human.getDomain());
    }

    @Test
    @DisplayName("对象从快照里消失：MODEL 行物理删除，HUMAN 行留着")
    void 消失的对象() {
        ConnectorBusinessView goneModel = new ConnectorBusinessView();
        goneModel.setId(1L);
        goneModel.setKind("OBJECT");
        goneModel.setObjectName("T_GONE");
        goneModel.setFieldName("");
        goneModel.setSource("MODEL");
        ConnectorBusinessView goneHuman = new ConnectorBusinessView();
        goneHuman.setId(2L);
        goneHuman.setKind("OBJECT");
        goneHuman.setObjectName("T_GONE_TOO");
        goneHuman.setFieldName("");
        goneHuman.setSource("HUMAN");
        ConnectorBusinessView goneRelation = new ConnectorBusinessView();
        goneRelation.setId(3L);
        goneRelation.setKind("RELATION");
        goneRelation.setObjectName("T_PO_DTL");
        goneRelation.setFieldName("QTY");
        goneRelation.setSource("MODEL");
        table.addAll(List.of(goneModel, goneHuman, goneRelation));

        BusinessViewGenerator.Result result = run();

        verify(viewMapper).physicalDeleteRow("t1", CONN_ID, 1L);
        verify(viewMapper).physicalDeleteRow("t1", CONN_ID, 3L);
        verify(viewMapper, never()).physicalDeleteRow("t1", CONN_ID, 2L);
        assertEquals(2, result.deleted());
    }

    @Test
    @DisplayName("一个系统最多 8 个领域：多出来的领域不收，归进去的表两次都不合格就留空（兜底「未分类」）")
    void 领域上限() {
        List<ConnectorSchema> many = new ArrayList<>();
        List<Map<String, Object>> assign = new ArrayList<>();
        List<String> domains = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(schema("T_" + i, "", "OID"));
            assign.add(Map.of("name", "T_" + i, "domain", "领域" + i));
            domains.add("领域" + i);
        }
        when(schemaMapper.selectList(any())).thenReturn(many);
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        replies.put("业务领域", n -> Map.of("domains", domains, "assign", assign));
        replies.put("业务名称", n -> Map.of("objects", List.of()));

        BusinessViewGenerator.Result result = run();

        assertEquals(8, result.domainsAssigned());
        assertEquals("领域8", row("OBJECT", "T_8", "").getDomain());
        assertNull(row("OBJECT", "T_9", ""));
        assertTrue(prompts.get("业务领域").get(1).contains("还能新增 0 个领域"), prompts.get("业务领域").get(1));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='BusinessViewGeneratorTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `BusinessViewGenerator`。

- [ ] **Step 3: 写实现**

`BusinessViewGenerator.java`：

```java
package com.jimeng.dataserver.ai.connector.businessview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticModelCall;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;

/**
 * 业务视图的生成（数据星图设计 v3 §6.2）：业务领域 → 对象名称与说明 → 关系角色名。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li><b>只补缺的和输入变了的</b>（{@code input_hash}）。重跑一遍的代价是几次空查询，不是几十次模型调用。</li>
 *   <li><b>{@code HUMAN} 行一个字不碰</b>，也不替它补领域：那是客户自己写的。</li>
 *   <li>模型产出过 {@link BusinessTextRules} 这一关；不合格的带着原因重试一次，仍不合格就不写，页面按兜底显示。</li>
 *   <li>对象或关系从快照、语义层里消失了，对应的 {@code MODEL} 行物理删除（唯一键不含 deleted，理由见实体注释）。</li>
 * </ul>
 * 每一批写完就落库：跑到一半失败，已经写下的不白花。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessViewGenerator {

    /** 提示词版本，记进每一行的 {@code prompt_version}。 */
    static final String PROMPT_VERSION = "business-view-2026-10-01";
    static final int NAME_BATCH = 50;
    static final int ROLE_BATCH = 150;
    static final int MAX_TOKENS = 8000;

    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorBusinessViewMapper viewMapper;
    private final ConnectorSemanticService semanticService;
    private final SemanticModelCall modelCall;

    /** 每次调模型之前回调一次：补全链用它续期认领，认领丢了就抛出去中止这一轮。 */
    @FunctionalInterface
    public interface Progress {
        void beforeModelCall();
    }

    /**
     * 为一条连接补齐业务文字。必须在真实租户上下文里调。
     *
     * @throws RuntimeException 模型调用失败等。已经写下的批次保留
     */
    public Result generate(Long connectorId, Progress progress) {
        Connection conn = semanticService.requireOwned(connectorId);
        Snapshot s = load(connectorId);
        Counter c = new Counter();

        cleanup(conn, s, c);
        assignDomains(conn, s, progress, c);
        nameObjects(conn, s, progress, c);
        nameRelations(conn, s, progress, c);

        Result result = new Result(c.domains, c.names, c.roles, c.rejected, c.deleted);
        log.info("业务文字生成完成 connectorId={} {}", connectorId, result.note());
        return result;
    }

    // ------------------------------------------------------------------ 读

    private Snapshot load(Long connectorId) {
        List<ConnectorSchema> schemas = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId));
        List<ConnectorSchema> rows = schemas.stream()
                .filter(r -> r != null && r.getObjectName() != null)
                .sorted(Comparator.comparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(rows);
        List<Obj> objects = new ArrayList<>();
        for (ConnectorSchema r : rows) {
            if (!fields.getOrDefault(r.getObjectName(), Map.of()).isEmpty()) {
                objects.add(new Obj(r.getObjectName(), RowEstimateNote.strip(r.getObjectComment())));
            }
        }
        List<ConnectorSemantic> semantic = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .in(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT,
                        ConnectorSemanticService.SCOPE_FIELD, ConnectorSemanticService.SCOPE_JOIN));
        Map<String, String> objectGloss = new HashMap<>();
        Map<String, String> fieldGloss = new HashMap<>();
        List<Rel> relations = new ArrayList<>();
        Set<String> tableNames = new LinkedHashSet<>();
        objects.forEach(o -> tableNames.add(fold(o.name())));
        for (ConnectorSemantic row : semantic) {
            switch (row.getScope()) {
                case ConnectorSemanticService.SCOPE_OBJECT ->
                        objectGloss.putIfAbsent(fold(row.getObjectName()), ConnectorSemanticService.shortGloss(row.getGloss()));
                case ConnectorSemanticService.SCOPE_FIELD ->
                        fieldGloss.putIfAbsent(key(row.getObjectName(), row.getFieldName()),
                                ConnectorSemanticService.shortGloss(row.getGloss()));
                default -> {
                    Rel rel = relation(row, fields);
                    if (rel != null) {
                        relations.add(rel);
                    }
                }
            }
        }
        relations.sort(Comparator.comparing(Rel::from).thenComparing(Rel::column));
        List<String> columns = new ArrayList<>();
        fields.values().forEach(cols -> columns.addAll(cols.keySet()));
        Set<String> identifiers = BusinessTextRules.identifiers(objects.stream().map(Obj::name).toList(), columns);

        Map<String, ConnectorBusinessView> views = new LinkedHashMap<>();
        for (ConnectorBusinessView v : viewMapper.selectList(new LambdaQueryWrapper<ConnectorBusinessView>()
                .eq(ConnectorBusinessView::getConnectorId, connectorId))) {
            views.putIfAbsent(viewKey(v.getKind(), v.getObjectName(), v.getFieldName()), v);
        }
        return new Snapshot(objects, relations, fields, objectGloss, fieldGloss, identifiers, views);
    }

    /**
     * 要起角色名的关系：两端都在快照里、没过期、业务方没说无关、采样没否掉。
     * 比星图实际画出来的略宽（终点不是唯一键、还没核对的也在）：它们一旦核对通过就会画出来，名字先备好。
     */
    private static Rel relation(ConnectorSemantic row, Map<String, Map<String, FieldDetail>> fields) {
        Map<String, Object> d = detail(row.getDetailJson());
        String to = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_OBJECT);
        String toColumn = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_COLUMN);
        if (to == null || toColumn == null || SemanticRowAssembler.lookup(fields, row.getObjectName(), row.getFieldName()) == null
                || SemanticRowAssembler.lookup(fields, to, toColumn) == null
                || ConnectorSemanticService.ST_STALE.equals(row.getStatus())
                || ConnectorSemanticService.HV_UNRELATED.equals(d.get(ConnectorSemanticService.KEY_HUMAN_VERDICT))
                || ConnectorSemanticService.V_REJECTED.equals(row.getVerified())
                || ConnectorSemanticService.V_WEAK.equals(row.getVerified())) {
            return null;
        }
        FieldDetail f = SemanticRowAssembler.lookup(fields, row.getObjectName(), row.getFieldName());
        return new Rel(row.getObjectName(), row.getFieldName(), to, toColumn, f.comment());
    }

    // ------------------------------------------------------------------ 清理

    /** 对象或关系已经不在了的 MODEL 行物理删除；HUMAN 行留着，等客户自己决定。 */
    private void cleanup(Connection conn, Snapshot s, Counter c) {
        Set<String> liveObjects = new LinkedHashSet<>();
        s.objects().forEach(o -> liveObjects.add(fold(o.name())));
        Set<String> liveRelations = new LinkedHashSet<>();
        s.relations().forEach(r -> liveRelations.add(key(r.from(), r.column())));
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, ConnectorBusinessView> e : s.views().entrySet()) {
            ConnectorBusinessView v = e.getValue();
            if (!ConnectorBusinessView.SOURCE_MODEL.equals(v.getSource())) {
                continue;
            }
            boolean live = ConnectorBusinessView.KIND_OBJECT.equals(v.getKind())
                    ? liveObjects.contains(fold(v.getObjectName()))
                    : liveRelations.contains(key(v.getObjectName(), v.getFieldName()));
            if (!live) {
                viewMapper.physicalDeleteRow(conn.getTenantId(), conn.getId(), v.getId());
                gone.add(e.getKey());
                c.deleted++;
            }
        }
        gone.forEach(s.views()::remove);
    }

    // ------------------------------------------------------------------ ① 业务领域

    private void assignDomains(Connection conn, Snapshot s, Progress progress, Counter c) {
        List<String> domains = new ArrayList<>();
        List<Obj> targets = new ArrayList<>();
        for (Obj o : s.objects()) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, o.name(), ""));
            if (v != null && v.getDomain() != null && !domains.contains(v.getDomain())) {
                domains.add(v.getDomain());
            }
            if (v == null || (ConnectorBusinessView.SOURCE_MODEL.equals(v.getSource()) && v.getDomain() == null)) {
                targets.add(o);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        List<Obj> pending = targets;
        for (int attempt = 1; attempt <= 2 && !pending.isEmpty(); attempt++) {
            progress.beforeModelCall();
            Map<String, Object> reply = modelCall.askJson("业务领域", conn.getId(), DOMAIN_SYSTEM,
                    domainPrompt(s, pending, domains, reasons), MAX_TOKENS);
            for (String d : SemanticRowAssembler.strList(reply, "domains")) {
                String name = d.trim();
                if (BusinessTextRules.problem(name, BusinessTextRules.DOMAIN_MAX, s.identifiers()) == null
                        && !domains.contains(name) && domains.size() < BusinessTextRules.MAX_DOMAINS) {
                    domains.add(name);
                }
            }
            Map<String, String> assigned = new HashMap<>();
            for (Map<String, Object> m : SemanticRowAssembler.arr(reply, "assign")) {
                String table = SemanticRowAssembler.str(m, "name");
                String domain = SemanticRowAssembler.str(m, "domain");
                if (table != null && domain != null) {
                    assigned.putIfAbsent(table, domain.trim());
                }
            }
            reasons = new LinkedHashMap<>();
            List<Obj> failed = new ArrayList<>();
            for (Obj o : pending) {
                String domain = assigned.get(o.name());
                String problem = domain == null ? "没有给出领域"
                        : !domains.contains(domain) ? "领域「" + domain + "」不合格或超出了 " + BusinessTextRules.MAX_DOMAINS + " 个"
                        : null;
                if (problem != null) {
                    failed.add(o);
                    reasons.put(o.name(), problem);
                    continue;
                }
                ConnectorBusinessView v = objectRow(conn, s, o.name());
                v.setDomain(domain);
                save(v, s);
                c.domains++;
            }
            pending = failed;
        }
        c.rejected += pending.size();
    }

    static final String DOMAIN_SYSTEM = """
            你在帮一家企业的业务人员和产品看懂他们的业务系统。给你这个系统里的一批数据表（表名 | 表注释 | 一句话说明），
            请把它们归到业务领域里。只输出一个 JSON 对象，不要任何别的文字：
            {"domains":["领域一","领域二"],"assign":[{"name":"表名","domain":"领域一"}]}

            要求：
            - 领域用业务叫法，比如「采购」「销售」「财务」「库存」「主数据」，每个不超过 8 个字；整个系统最多 8 个领域。
            - 已经有的领域会列给你，优先沿用，确实放不进去才新增；domains 里只写新增的。
            - 每张表只归一个领域；表名原样照抄给你的写法。
            - 不要出现表名、字段名、英文代码，也不要写「疑似」「推测」这类措辞。
            """;

    private static String domainPrompt(Snapshot s, List<Obj> targets, List<String> domains, Map<String, String> reasons) {
        StringBuilder b = new StringBuilder();
        b.append("已有领域：").append(domains.isEmpty() ? "（还没有）" : String.join("、", domains)).append('\n');
        b.append("还能新增 ").append(Math.max(0, BusinessTextRules.MAX_DOMAINS - domains.size())).append(" 个领域。\n\n");
        b.append("【表】（表名 | 表注释 | 一句话说明）\n");
        for (Obj o : targets) {
            b.append(o.name()).append(" | ").append(nz(o.comment())).append(" | ")
                    .append(nz(s.objectGloss().get(fold(o.name())))).append('\n');
        }
        appendReasons(b, reasons);
        return b.toString();
    }

    // ------------------------------------------------------------------ ② 对象名称与说明

    private void nameObjects(Connection conn, Snapshot s, Progress progress, Counter c) {
        List<Obj> targets = new ArrayList<>();
        Map<String, String> used = new HashMap<>();
        for (Obj o : s.objects()) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, o.name(), ""));
            String hash = objectHash(s, o);
            boolean fresh = v != null && v.getDisplayName() != null && hash.equals(v.getInputHash());
            if (v != null && (ConnectorBusinessView.SOURCE_HUMAN.equals(v.getSource()) || fresh)) {
                if (v.getDisplayName() != null) {
                    used.put(v.getDisplayName(), o.name());
                }
                continue;
            }
            targets.add(o);
        }
        for (int from = 0; from < targets.size(); from += NAME_BATCH) {
            List<Obj> pending = targets.subList(from, Math.min(targets.size(), from + NAME_BATCH));
            Map<String, String> reasons = new LinkedHashMap<>();
            for (int attempt = 1; attempt <= 2 && !pending.isEmpty(); attempt++) {
                progress.beforeModelCall();
                Map<String, Object> reply = modelCall.askJson("业务名称", conn.getId(), NAME_SYSTEM,
                        namePrompt(s, pending, used, reasons), MAX_TOKENS);
                Map<String, Map<String, Object>> byName = new HashMap<>();
                for (Map<String, Object> m : SemanticRowAssembler.arr(reply, "objects")) {
                    String name = SemanticRowAssembler.str(m, "name");
                    if (name != null) {
                        byName.putIfAbsent(name, m);
                    }
                }
                reasons = new LinkedHashMap<>();
                List<Obj> failed = new ArrayList<>();
                for (Obj o : pending) {
                    Map<String, Object> m = byName.get(o.name());
                    String display = m == null ? null : trim(SemanticRowAssembler.str(m, "display_name"));
                    String summary = m == null ? null : trim(SemanticRowAssembler.str(m, "summary"));
                    String problem = m == null ? "没有给出"
                            : prefixed("业务名", BusinessTextRules.problem(display, BusinessTextRules.NAME_MAX, s.identifiers()));
                    if (problem == null) {
                        problem = prefixed("说明", BusinessTextRules.problem(summary, BusinessTextRules.SUMMARY_MAX, s.identifiers()));
                    }
                    if (problem == null && used.containsKey(display) && !used.get(display).equals(o.name())) {
                        problem = "业务名「" + display + "」和别的表重名了，表头和明细要能区分开";
                    }
                    if (problem != null) {
                        failed.add(o);
                        reasons.put(o.name(), problem);
                        continue;
                    }
                    ConnectorBusinessView v = objectRow(conn, s, o.name());
                    v.setDisplayName(display);
                    v.setSummary(summary);
                    v.setInputHash(objectHash(s, o));
                    save(v, s);
                    used.put(display, o.name());
                    c.names++;
                }
                pending = failed;
            }
            c.rejected += pending.size();
        }
    }

    static final String NAME_SYSTEM = """
            你在帮一家企业的业务人员和产品看懂他们的业务系统。给你一批数据表（表名 | 表注释 | 一句话说明 | 所属领域），
            请为每张表起一个业务名，并写一句话说明。只输出一个 JSON 对象，不要任何别的文字：
            {"objects":[{"name":"表名","display_name":"业务名","summary":"一句话说明"}]}

            要求：
            - 业务名是业务人员平时的叫法，比如「采购订单」「采购订单明细」「供应商」「会计科目」，不超过 12 个字。
              同一个系统里不要重名，表头和明细要能区分开；已经用过的名字会列给你。
            - 一句话说明写「一条记录代表什么、主要记了什么」，不超过 50 个字。
            - 不要出现表名、字段名、英文代码；不要写「疑似」「推测」「未经验证」这类措辞，
              也不要提数据库、字段、主键、外键这些技术词。
            - 表名原样照抄给你的写法，每张表一条。
            """;

    private static String namePrompt(Snapshot s, List<Obj> targets, Map<String, String> used,
                                     Map<String, String> reasons) {
        StringBuilder b = new StringBuilder();
        if (!used.isEmpty()) {
            b.append("已经用过的业务名（不要重名）：").append(String.join("、", used.keySet().stream().sorted().toList()))
                    .append("\n\n");
        }
        b.append("【表】（表名 | 表注释 | 一句话说明 | 所属领域）\n");
        for (Obj o : targets) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, o.name(), ""));
            b.append(o.name()).append(" | ").append(nz(o.comment())).append(" | ")
                    .append(nz(s.objectGloss().get(fold(o.name())))).append(" | ")
                    .append(v == null || v.getDomain() == null ? BusinessTextRules.UNCLASSIFIED : v.getDomain()).append('\n');
        }
        appendReasons(b, reasons);
        return b.toString();
    }

    // ------------------------------------------------------------------ ③ 关系角色名

    private void nameRelations(Connection conn, Snapshot s, Progress progress, Counter c) {
        List<Rel> targets = new ArrayList<>();
        for (Rel r : s.relations()) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_RELATION, r.from(), r.column()));
            if (v != null && (ConnectorBusinessView.SOURCE_HUMAN.equals(v.getSource())
                    || (v.getDisplayName() != null && relationHash(s, r).equals(v.getInputHash())))) {
                continue;
            }
            targets.add(r);
        }
        for (int from = 0; from < targets.size(); from += ROLE_BATCH) {
            List<Rel> pending = targets.subList(from, Math.min(targets.size(), from + ROLE_BATCH));
            Map<String, String> reasons = new LinkedHashMap<>();
            for (int attempt = 1; attempt <= 2 && !pending.isEmpty(); attempt++) {
                progress.beforeModelCall();
                Map<String, Object> reply = modelCall.askJson("关系角色名", conn.getId(), ROLE_SYSTEM,
                        rolePrompt(s, pending, reasons), MAX_TOKENS);
                Map<String, String> byColumn = new HashMap<>();
                for (Map<String, Object> m : SemanticRowAssembler.arr(reply, "relations")) {
                    String object = SemanticRowAssembler.str(m, "object");
                    String column = SemanticRowAssembler.str(m, "column");
                    String role = SemanticRowAssembler.str(m, "role");
                    if (object != null && column != null && role != null) {
                        byColumn.putIfAbsent(object + '\u0001' + column, role.trim());
                    }
                }
                reasons = new LinkedHashMap<>();
                List<Rel> failed = new ArrayList<>();
                for (Rel r : pending) {
                    String role = byColumn.get(r.from() + '\u0001' + r.column());
                    String problem = role == null ? "没有给出"
                            : BusinessTextRules.problem(role, BusinessTextRules.ROLE_MAX, s.identifiers());
                    if (problem != null) {
                        failed.add(r);
                        reasons.put(r.from() + "." + r.column(), problem);
                        continue;
                    }
                    ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_RELATION, r.from(), r.column()));
                    if (v == null) {
                        v = newRow(conn, ConnectorBusinessView.KIND_RELATION, r.from(), r.column());
                    }
                    v.setDisplayName(role);
                    v.setInputHash(relationHash(s, r));
                    save(v, s);
                    c.roles++;
                }
                pending = failed;
            }
            c.rejected += pending.size();
        }
    }

    static final String ROLE_SYSTEM = """
            你在帮一家企业的业务人员和产品看懂业务对象之间的关联。给你一批关联：起点表、起点列、这一列的说明、
            起点对象的业务名、终点对象的业务名。请为每条关联起一个角色名，说清「终点对象在这里扮演什么角色」。
            只输出一个 JSON 对象，不要任何别的文字：
            {"relations":[{"object":"起点表","column":"起点列","role":"角色名"}]}

            要求：
            - 角色名不超过 10 个字，用业务叫法，比如「供应商」「收款供应商」「总账科目」「所属订单」「上级科目」。
            - 角色名里不要出现表名、字段名、英文代码，也不要写「疑似」「推测」这类措辞。
            - object、column 原样照抄给你的写法，每条关联一条。
            """;

    private static String rolePrompt(Snapshot s, List<Rel> targets, Map<String, String> reasons) {
        StringBuilder b = new StringBuilder("【关联】（起点表 | 起点列 | 列的说明 | 起点对象 | 终点对象）\n");
        for (Rel r : targets) {
            b.append(r.from()).append(" | ").append(r.column()).append(" | ").append(nz(columnText(s, r)))
                    .append(" | ").append(title(s, r.from())).append(" | ").append(title(s, r.to())).append('\n');
        }
        appendReasons(b, reasons);
        return b.toString();
    }

    // ------------------------------------------------------------------ 输入指纹

    /** 名称与说明的输入：表名、表注释、语义层说明。领域不在里面：它只在缺的时候分配一次。 */
    private static String objectHash(Snapshot s, Obj o) {
        return sha256(o.name() + '\u0001' + nz(o.comment()) + '\u0001' + nz(s.objectGloss().get(fold(o.name()))));
    }

    /** 角色名的输入：起点对象的业务名、起点列及其说明、终点表列、终点对象的业务名。 */
    private static String relationHash(Snapshot s, Rel r) {
        return sha256(title(s, r.from()) + '\u0001' + r.column() + '\u0001' + nz(columnText(s, r)) + '\u0001'
                + r.to() + '.' + r.toColumn() + '\u0001' + title(s, r.to()));
    }

    /** 列的说明：语义层的字段说明优先，没有就用客户的列注释。 */
    private static String columnText(Snapshot s, Rel r) {
        String gloss = s.fieldGloss().get(key(r.from(), r.column()));
        return gloss != null && !gloss.isBlank() ? gloss : r.comment();
    }

    /** 对象这会儿的叫法：业务名；还没有就用表注释；再没有就是表名。只用来喂模型，不上屏。 */
    private static String title(Snapshot s, String table) {
        ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, table, ""));
        if (v != null && v.getDisplayName() != null) {
            return v.getDisplayName();
        }
        for (Obj o : s.objects()) {
            if (o.name().equals(table) && o.comment() != null && !o.comment().isBlank()) {
                return o.comment();
            }
        }
        return table;
    }

    // ------------------------------------------------------------------ 写

    private ConnectorBusinessView objectRow(Connection conn, Snapshot s, String table) {
        ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, table, ""));
        return v != null ? v : newRow(conn, ConnectorBusinessView.KIND_OBJECT, table, "");
    }

    private static ConnectorBusinessView newRow(Connection conn, String kind, String object, String field) {
        ConnectorBusinessView v = new ConnectorBusinessView();
        v.setTenantId(conn.getTenantId());
        v.setConnectorId(conn.getId());
        v.setKind(kind);
        v.setObjectName(object);
        v.setFieldName(field);
        v.setSource(ConnectorBusinessView.SOURCE_MODEL);
        return v;
    }

    /** 插入或更新一行。更新带 {@code source = MODEL} 条件：跑的过程中客户刚改成 HUMAN 的行不覆盖。 */
    private void save(ConnectorBusinessView v, Snapshot s) {
        v.setModelCode(modelCall.modelCode());
        v.setPromptVersion(PROMPT_VERSION);
        if (v.getId() == null) {
            try {
                viewMapper.insert(v);
                s.views().put(viewKey(v.getKind(), v.getObjectName(), v.getFieldName()), v);
            } catch (DuplicateKeyException e) {
                log.warn("业务视图写入撞唯一键，本条跳过 connectorId={} kind={} object={} field={}",
                        v.getConnectorId(), v.getKind(), v.getObjectName(), v.getFieldName());
            }
            return;
        }
        viewMapper.update(null, new LambdaUpdateWrapper<ConnectorBusinessView>()
                .eq(ConnectorBusinessView::getId, v.getId())
                .eq(ConnectorBusinessView::getSource, ConnectorBusinessView.SOURCE_MODEL)
                .set(ConnectorBusinessView::getDisplayName, v.getDisplayName())
                .set(ConnectorBusinessView::getSummary, v.getSummary())
                .set(ConnectorBusinessView::getDomain, v.getDomain())
                .set(ConnectorBusinessView::getInputHash, v.getInputHash())
                .set(ConnectorBusinessView::getModelCode, v.getModelCode())
                .set(ConnectorBusinessView::getPromptVersion, v.getPromptVersion()));
    }

    // ------------------------------------------------------------------ 小工具

    private static void appendReasons(StringBuilder b, Map<String, String> reasons) {
        if (reasons.isEmpty()) {
            return;
        }
        b.append("\n上一次这几条不合格，请按原因改写：\n");
        reasons.forEach((k, v) -> b.append(k).append("：").append(v).append('\n'));
    }

    private static String prefixed(String what, String problem) {
        return problem == null ? null : what + problem;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : SemanticRowAssembler.nz(s).trim();
    }

    static String viewKey(String kind, String object, String field) {
        return kind + '\u0001' + fold(object) + '\u0001' + fold(field == null ? "" : field);
    }

    private static String key(String table, String column) {
        return fold(table) + '\u0001' + fold(column);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Obj(String name, String comment) {
    }

    private record Rel(String from, String column, String to, String toColumn, String comment) {
    }

    private record Snapshot(List<Obj> objects, List<Rel> relations, Map<String, Map<String, FieldDetail>> fields,
                            Map<String, String> objectGloss, Map<String, String> fieldGloss, Set<String> identifiers,
                            Map<String, ConnectorBusinessView> views) {
    }

    private static final class Counter {
        int domains;
        int names;
        int roles;
        int rejected;
        int deleted;
    }

    /** 一次生成的结果。{@link #note()} 写进 {@code connector_enrichment_state.view_note}，只给排查用，不上屏。 */
    public record Result(int domainsAssigned, int namesWritten, int rolesWritten, int rejected, int deleted) {
        public String note() {
            return "业务名 " + namesWritten + " 条、角色名 " + rolesWritten + " 条、领域 " + domainsAssigned
                    + " 条；两次都不合格退回兜底 " + rejected + " 条；清理 " + deleted + " 条";
        }
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='BusinessViewGeneratorTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  f=modules/data-server/target/surefire-reports/TEST-com.jimeng.dataserver.ai.connector.businessview.BusinessViewGeneratorTest.xml; \
  echo "cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"
```
Expected: `cases=9 failures=0`

- [ ] **Step 5: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/businessview/BusinessViewGenerator.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/businessview/BusinessViewGeneratorTest.java && \
git commit -q -F - <<'MSG'
feat(graph): 业务视图生成——业务领域、对象名称与说明、关系角色名

只补缺的和输入变了的；HUMAN 行不碰；不合格的带原因重试一次，仍不合格退回兜底；
同一系统不许重名；消失对象的 MODEL 行物理删除；每批写完即落库。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 9: 语义层补全链（data-service）

`SemanticEnrichmentService`（spec §4）把前面几块串起来：

```text
触发：规则推导成功 / 增量补写成功 / agent 生成定稿 / 定时对账发现输入变了
  ① 关系发现（Task 6）
  ② 派发原有的采样核对：范围 = 触发方原本要核对的表 ∪ ① 动过的表；agent 路径跳过；范围为空不派发
  ③ 业务文字（Task 8）
```

- **触发**：推导服务（整体推导、增量补写两处成功点）和 agent 定稿（`SemanticGenerationFinalizer` 的两处 READY 提交之后）发 `SemanticEnrichmentRequest` 事件；补全链 `@EventListener` 收到后派到 `semanticStageExecutor`。开关 `connector.semantic.enrichment.enabled`（代码默认 true）关着时，推导服务照旧直接派发采样核对。
- **一条连接同一时刻只跑一条**：`connector_enrichment_state.claim_at` 认领（一条 CAS UPDATE，多副本也只有一个拿到），30 分钟过期，每步续期（同一秒内不续：驱动若按「实际改动行数」计数，写相同的值会报 0 行）；认领丢了中止、不写结果。**没抢到认领、开关关着、池满的那次触发，原本要派发的采样核对照样派出去**。
- **收尾**：成功记 READY、跑完时的输入指纹、模型那一遍的指纹、两段说明；关系发现里模型那一遍失败也记 FAILED（业务文字照常生成）；异常记 FAILED 和开始时的指纹。
- **定时对账**：每 10 分钟（`connector.semantic.enrichment.reconcile-interval-ms`，改它要重启），启动 5 分钟后第一次，每次最多派发一个连接：从没跑完过、输入指纹变了、或上次失败且满 6 小时。选人跨租户（`runAsSystem`），算指纹回到那一行的真实租户下。指纹只读需要的列（快照的 `content_hash` 与 `JSON_EXTRACT` 出来的唯一键 / 外键，语义层 OBJECT 的说明、JOIN 的两端 / 状态 / 是否核对通过 / 业务方结论），不含 `verified` 本身——否则每轮核对完都触发一次重跑。

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/runtime/ConnectorProperties.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/SemanticEnrichmentRequest.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/SemanticEnrichmentService.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticDeriveService.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/generation/SemanticGenerationFinalizer.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/SemanticEnrichmentServiceTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticStageWiringTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticAddedDeriveTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/generation/SemanticGenerationFinalizerTest.java`

**Interfaces:**
- Consumes: Task 5 实体与 `claim`；Task 6 `discover / Result`；Task 8 `generate / Progress / Result`。
- Produces:
  - `ConnectorProperties.Semantic.enrichment.enabled`（`SemanticEnrichment` 类）；
  - `record SemanticEnrichmentRequest(Long connectorId, String tenantId, Trigger trigger, String deriveNote, Set<String> validationScope)`，工厂 `afterDerive / afterAddedDerive / afterAgentGeneration / reconcile`，`validates()`；
  - `ConnectorSemanticDeriveService.dispatchValidation(Long, String, String, Set<String>)` 改为 `public`；
  - `SemanticEnrichmentService.onRequest / submit / run / reconcileOnce / fingerprint`。

- [ ] **Step 1: 写补全链的失败测试**

`SemanticEnrichmentServiceTest.java`（线程池同步执行派发出去的任务，时钟固定）：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.businessview.BusinessViewGenerator;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticEnrichmentServiceTest {

    private static final Long CONN_ID = 7L;
    private static final String TENANT = "t1";
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    private ConnectorEnrichmentStateMapper stateMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private SemanticRelationDiscovery discovery;
    private BusinessViewGenerator generator;
    private ConnectorSemanticDeriveService deriveService;
    private ConnectorProperties properties;
    private ThreadPoolTaskExecutor executor;
    private SemanticEnrichmentService service;
    private ConnectorEnrichmentState state;
    private final List<LambdaUpdateWrapper<ConnectorEnrichmentState>> updates = new ArrayList<>();

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorEnrichmentState.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stateMapper = mock(ConnectorEnrichmentStateMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        discovery = mock(SemanticRelationDiscovery.class);
        generator = mock(BusinessViewGenerator.class);
        deriveService = mock(ConnectorSemanticDeriveService.class);
        properties = new ConnectorProperties();
        executor = mock(ThreadPoolTaskExecutor.class);
        // 同步执行：派发出去的任务当场跑完，好在用例里看结果。
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        service = new SemanticEnrichmentService(stateMapper, connectionMapper, schemaMapper, semanticMapper,
                discovery, generator, deriveService, properties, executor, Clock.fixed(NOW, ZoneId.of("UTC")));

        state = new ConnectorEnrichmentState();
        state.setId(1L);
        state.setConnectorId(CONN_ID);
        state.setTenantId(TENANT);
        state.setRelationPassFingerprint("pass-0");
        when(stateMapper.selectOne(any())).thenReturn(state);
        when(stateMapper.claim(eq(TENANT), eq(CONN_ID), any(), any())).thenReturn(1);
        when(stateMapper.update(any(), any())).thenAnswer(inv -> {
            updates.add(inv.getArgument(1));
            return 1;
        });
        when(schemaMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("object_name", "t_order", "content_hash", "h1", "uk", "[]")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        discoveryReturns(null, "T_ORDER");
        when(generator.generate(eq(CONN_ID), any())).thenReturn(new BusinessViewGenerator.Result(3, 3, 2, 0, 0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void discoveryReturns(String modelError, String... touched) {
        when(discovery.discover(eq(CONN_ID), any())).thenReturn(new SemanticRelationDiscovery.Result(0, 1, 0, 0,
                true, "pass-1", modelError,
                new ConnectorSemanticService.DiscoveryWrite(touched.length, 0, 0, 0, 0, List.of(touched))));
    }

    /** 最后一次收尾写下去的值。 */
    private Map<String, Object> released() {
        LambdaUpdateWrapper<ConnectorEnrichmentState> last = updates.get(updates.size() - 1);
        assertTrue(last.getSqlSet().contains("last_status"), "最后一次写不是收尾：" + last.getSqlSet());
        return last.getParamNameValuePairs();
    }

    @Nested
    @DisplayName("一轮补全链")
    class OneRun {

        @Test
        @DisplayName("★ 顺序：关系发现 → 派发采样核对 → 业务文字 → 收尾 READY（记输入指纹、模型指纹、两段说明）")
        void 顺序() {
            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "推导完成"));

            InOrder order = inOrder(discovery, deriveService, generator);
            order.verify(discovery).discover(CONN_ID, "pass-0");
            order.verify(deriveService).dispatchValidation(CONN_ID, TENANT, "推导完成", null);
            order.verify(generator).generate(eq(CONN_ID), any());
            Map<String, Object> values = released();
            assertTrue(values.containsValue(ConnectorEnrichmentState.STATUS_READY));
            assertTrue(values.containsValue("pass-1"), "模型指纹前移");
            assertTrue(values.containsValue(service.fingerprint(CONN_ID)), "记跑完时的输入指纹");
            assertTrue(values.values().stream().anyMatch(v -> String.valueOf(v).startsWith("业务名 3 条")));
        }

        @Test
        @DisplayName("增量补写：核对范围 = 原本的表 ∪ 关系发现动过的表")
        void 增量范围() {
            service.onRequest(SemanticEnrichmentRequest.afterAddedDerive(CONN_ID, TENANT, "新增 1 张表", Set.of("t_refund")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> scope = ArgumentCaptor.forClass(Set.class);
            verify(deriveService).dispatchValidation(eq(CONN_ID), eq(TENANT), eq("新增 1 张表"), scope.capture());
            assertEquals(Set.of("t_refund", "t_order"), scope.getValue());
        }

        @Test
        @DisplayName("定时对账触发：只核对关系发现动过的表；一张都没动就不派发")
        void 对账范围() {
            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));
            verify(deriveService).dispatchValidation(CONN_ID, TENANT, null, Set.of("t_order"));

            discoveryReturns(null);
            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));
            verify(deriveService).dispatchValidation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("★ agent 定稿触发：关系发现、业务文字照跑，但不派发采样核对（这条路径原来就不做）")
        void agent路径() {
            service.onRequest(SemanticEnrichmentRequest.afterAgentGeneration(CONN_ID, TENANT));

            verify(discovery).discover(CONN_ID, "pass-0");
            verify(generator).generate(eq(CONN_ID), any());
            verify(deriveService, never()).dispatchValidation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("模型关系那一遍失败：业务文字照常生成，这一轮记 FAILED（6 小时后再试），模型指纹不前移")
        void 模型那一遍失败() {
            when(discovery.discover(eq(CONN_ID), any())).thenReturn(new SemanticRelationDiscovery.Result(0, 1, 0, 0,
                    true, "pass-0", "超时", new ConnectorSemanticService.DiscoveryWrite(1, 0, 0, 0, 0, List.of("T_ORDER"))));

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            verify(generator).generate(eq(CONN_ID), any());
            Map<String, Object> values = released();
            assertTrue(values.containsValue(ConnectorEnrichmentState.STATUS_FAILED));
            assertTrue(values.containsValue("pass-0"));
        }

        @Test
        @DisplayName("业务文字那一步抛错：记 FAILED、记开始时的输入指纹，关系发现的结果留在说明里")
        void 生成失败() {
            when(generator.generate(eq(CONN_ID), any())).thenThrow(new IllegalStateException("模型回复解析失败"));
            String before = service.fingerprint(CONN_ID);

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            Map<String, Object> values = released();
            assertTrue(values.containsValue(ConnectorEnrichmentState.STATUS_FAILED));
            assertTrue(values.containsValue(before));
            assertTrue(values.values().stream().anyMatch(v -> String.valueOf(v).contains("模型回复解析失败")));
        }

        @Test
        @DisplayName("★ 这条连接已有一轮在跑（抢不到认领）：不跑，但原本要派发的采样核对照样派出去")
        void 抢不到认领() {
            when(stateMapper.claim(eq(TENANT), eq(CONN_ID), any(), any())).thenReturn(0);

            service.onRequest(SemanticEnrichmentRequest.afterAddedDerive(CONN_ID, TENANT, "n", Set.of("t_refund")));

            verify(discovery, never()).discover(any(), any());
            verify(generator, never()).generate(any(), any());
            verify(deriveService).dispatchValidation(CONN_ID, TENANT, "n", Set.of("t_refund"));
        }

        @Test
        @DisplayName("认领按秒写、按 30 分钟判过期")
        void 认领参数() {
            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));

            ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
            ArgumentCaptor<Date> staleBefore = ArgumentCaptor.forClass(Date.class);
            verify(stateMapper).claim(eq(TENANT), eq(CONN_ID), now.capture(), staleBefore.capture());
            assertEquals(Date.from(NOW), now.getValue());
            assertEquals(Date.from(NOW.minusSeconds(30 * 60)), staleBefore.getValue());
        }

        @Test
        @DisplayName("跑到一半认领被别处接走（续期失败）：中止，不写结果")
        void 认领丢了() {
            when(stateMapper.update(any(), any())).thenReturn(0);
            when(generator.generate(eq(CONN_ID), any())).thenAnswer(inv -> {
                ((BusinessViewGenerator.Progress) inv.getArgument(1)).beforeModelCall();
                return new BusinessViewGenerator.Result(0, 0, 0, 0, 0);
            });
            // 同一秒内续期不打库；换到下一秒才会真的去续。
            service = new SemanticEnrichmentService(stateMapper, connectionMapper, schemaMapper, semanticMapper,
                    discovery, generator, deriveService, properties, executor, new TickingClock(NOW));

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            verify(generator, never()).generate(any(), any());
            verify(stateMapper, never()).update(any(), eqLastStatus());
        }

        @Test
        @DisplayName("状态行还没有：先建一行再认领")
        void 建状态行() {
            when(stateMapper.selectOne(any())).thenReturn(null);
            when(stateMapper.insert(any(ConnectorEnrichmentState.class))).thenAnswer(inv -> {
                ((ConnectorEnrichmentState) inv.getArgument(0)).setId(9L);
                return 1;
            });

            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));

            ArgumentCaptor<ConnectorEnrichmentState> row = ArgumentCaptor.forClass(ConnectorEnrichmentState.class);
            verify(stateMapper).insert(row.capture());
            assertEquals(TENANT, row.getValue().getTenantId());
            assertEquals(CONN_ID, row.getValue().getConnectorId());
            verify(discovery).discover(CONN_ID, null);
        }
    }

    @Nested
    @DisplayName("开关与派发失败")
    class Switches {

        @Test
        @DisplayName("★ 开关关着：不跑补全链，原本要派发的采样核对照原样派出去")
        void 开关关着() {
            properties.getSemantic().getEnrichment().setEnabled(false);

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            verify(executor, never()).execute(any(Runnable.class));
            verify(deriveService).dispatchValidation(CONN_ID, TENANT, "n", null);
            assertNull(service.reconcileOnce());
        }

        @Test
        @DisplayName("后台队列满：不报错，原本要派发的采样核对先派出去，补全链等下一轮对账")
        void 池满() {
            doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));

            assertFalse(service.submit(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n")));

            verify(deriveService).dispatchValidation(CONN_ID, TENANT, "n", null);
        }

        @Test
        @DisplayName("agent 路径排不上：没有要补派的核对")
        void agent排不上() {
            doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));
            service.submit(SemanticEnrichmentRequest.afterAgentGeneration(CONN_ID, TENANT));
            verify(deriveService, never()).dispatchValidation(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("定时对账")
    class Reconcile {

        private Connection connection(long id) {
            Connection c = new Connection();
            c.setId(id);
            c.setTenantId(TENANT);
            c.setSemanticStatus("READY");
            return c;
        }

        @BeforeEach
        void readyConnections() {
            when(connectionMapper.selectList(any())).thenReturn(List.of(connection(CONN_ID)));
        }

        private void stateIs(String lastStatus, String fingerprint, Instant lastAttempt, Instant claimAt) {
            state.setLastStatus(lastStatus);
            state.setInputFingerprint(fingerprint);
            state.setLastAttemptAt(lastAttempt == null ? null : Date.from(lastAttempt));
            state.setClaimAt(claimAt == null ? null : Date.from(claimAt));
            when(stateMapper.selectList(any())).thenReturn(List.of(state));
        }

        @Test
        @DisplayName("从没跑过：派发")
        void 从没跑过() {
            when(stateMapper.selectList(any())).thenReturn(List.of());
            assertEquals(CONN_ID, service.reconcileOnce());
            verify(discovery).discover(eq(CONN_ID), any());
        }

        @Test
        @DisplayName("★ 上次成功、输入指纹没变：不派发")
        void 指纹没变() {
            stateIs("READY", service.fingerprint(CONN_ID), NOW.minusSeconds(3600), null);
            assertNull(service.reconcileOnce());
            verify(discovery, never()).discover(any(), any());
        }

        @Test
        @DisplayName("输入指纹变了：派发")
        void 指纹变了() {
            stateIs("READY", "old", NOW.minusSeconds(3600), null);
            assertEquals(CONN_ID, service.reconcileOnce());
        }

        @Test
        @DisplayName("上次失败、输入没变：满 6 小时才重试")
        void 失败退避() {
            stateIs("FAILED", service.fingerprint(CONN_ID), NOW.minusSeconds(5 * 3600), null);
            assertNull(service.reconcileOnce());

            stateIs("FAILED", service.fingerprint(CONN_ID), NOW.minusSeconds(6 * 3600), null);
            assertEquals(CONN_ID, service.reconcileOnce());
        }

        @Test
        @DisplayName("认领还没过期（正在跑）：跳过")
        void 正在跑() {
            stateIs(null, null, null, NOW.minusSeconds(60));
            assertNull(service.reconcileOnce());
        }

        @Test
        @DisplayName("每一轮最多派发一个连接")
        void 一次一个() {
            when(connectionMapper.selectList(any())).thenReturn(List.of(connection(CONN_ID), connection(8L)));
            when(stateMapper.selectList(any())).thenReturn(List.of());
            assertEquals(CONN_ID, service.reconcileOnce());
            verify(discovery, never()).discover(eq(8L), any());
        }
    }

    private static LambdaUpdateWrapper<ConnectorEnrichmentState> eqLastStatus() {
        return org.mockito.ArgumentMatchers.argThat(w -> w != null && w.getSqlSet() != null
                && w.getSqlSet().contains("last_status"));
    }

    /** 每读一次往后走一秒的时钟：让续期真的发生。 */
    private static final class TickingClock extends Clock {
        private Instant now;

        TickingClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant current = now;
            now = now.plusSeconds(1);
            return current;
        }
    }
}
```

- [ ] **Step 2: 写两个触发点的失败测试**

`ConnectorSemanticStageWiringTest.java`：

第 1 处，把：

```java
            verify(streamExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("推导失败就不派发：没有说明书可验")
        void notDispatchedWhenDeriveFails() {
```

替换为：

```java
            verify(streamExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("★ 补全链开着：推导成功后把后续交给补全链（发事件，由它派发采样核对），这里不直接派发")
        void handsOverToEnrichmentChain() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单主表\",\"evidence\":\"NAME\"}]}");
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            org.springframework.context.ApplicationEventPublisher publisher =
                    mock(org.springframework.context.ApplicationEventPublisher.class);
            service.setApplicationEventPublisher(publisher);

            ConnectorSemanticDeriveService.DeriveResult r = service.derive(CONNECTOR_ID);

            ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
            verify(publisher).publishEvent(event.capture());
            SemanticEnrichmentRequest request = (SemanticEnrichmentRequest) event.getValue();
            assertEquals(SemanticEnrichmentRequest.Trigger.DERIVE, request.trigger());
            assertEquals(TENANT, request.tenantId());
            assertNull(request.validationScope(), "整体推导原本要核对全部表");
            assertEquals(r.getNote(), request.deriveNote());
            verify(semanticStageExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("补全链关着：推导成功后照旧直接派发采样核对，不发事件")
        void enrichmentDisabledDispatchesDirectly() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单主表\",\"evidence\":\"NAME\"}]}");
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            org.springframework.context.ApplicationEventPublisher publisher =
                    mock(org.springframework.context.ApplicationEventPublisher.class);
            service.setApplicationEventPublisher(publisher);
            ((ConnectorProperties) org.springframework.test.util.ReflectionTestUtils.getField(service, "properties"))
                    .getSemantic().getEnrichment().setEnabled(false);

            assertTrue(service.derive(CONNECTOR_ID).isOk());

            verify(publisher, never()).publishEvent(any(Object.class));
            verify(semanticStageExecutor).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("推导失败就不派发：没有说明书可验")
        void notDispatchedWhenDeriveFails() {
```

`ConnectorSemanticAddedDeriveTest.java`：

第 1 处，把：

```java
            verify(semanticStageExecutor).execute(any(Runnable.class));
        }

        /** 增量与全量共用 sendToModel：同样必须是不带工具的内部调用、同样带夹紧后的超时。 */
        @Test
        @DisplayName("★ 增量推导同样走 messagesInternal 并带上超时，不走 messages")
```

替换为：

```java
            verify(semanticStageExecutor).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("★ 补全链开着：增量补写成功后发事件，原本要核对的这批表随事件带过去")
        void handsOverScopedValidationToEnrichmentChain() {
            modelOutputs(MODEL_OUTPUT);
            org.springframework.context.ApplicationEventPublisher publisher =
                    mock(org.springframework.context.ApplicationEventPublisher.class);
            service.setApplicationEventPublisher(publisher);

            service.deriveAdded(CONNECTOR_ID, Set.of("t_refund"));

            ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
            verify(publisher).publishEvent(event.capture());
            SemanticEnrichmentRequest request = (SemanticEnrichmentRequest) event.getValue();
            assertEquals(SemanticEnrichmentRequest.Trigger.DERIVE_ADDED, request.trigger());
            assertEquals(Set.of("t_refund"), request.validationScope());
            verify(semanticStageExecutor, never()).execute(any(Runnable.class));
        }

        /** 增量与全量共用 sendToModel：同样必须是不带工具的内部调用、同样带夹紧后的超时。 */
        @Test
        @DisplayName("★ 增量推导同样走 messagesInternal 并带上超时，不走 messages")
```

`SemanticGenerationFinalizerTest.java`：

第 1 处，把：

```java
        verify(semanticService, org.mockito.Mockito.times(2)).requireOwned(20L);
    }

    @Test
    @DisplayName("STAGED 空暂存但主表已有 INFERRED 时拒绝替换并记 FAILED(EMPTY_REPLACE)")
    void emptyReplaceFailsWithoutDeletingOldRows() {
```

替换为：

```java
        verify(semanticService, org.mockito.Mockito.times(2)).requireOwned(20L);
    }

    // ================================================================ 补全链（数据星图设计 v3 §4）

    @Test
    @DisplayName("★ DIRECT 定稿 READY 提交之后才发补全链事件（agent 路径，不派发采样核对）")
    void directReadyPublishesEnrichment() {
        org.springframework.context.ApplicationEventPublisher publisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        finalizer.setApplicationEventPublisher(publisher);
        ConnectorSemanticGeneration generation = generation("DIRECT");
        stubTables(List.of(table("DONE")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(semantic("OBJECT")));

        finalizer.finish(generation, 0.0);

        assertEquals(1, txManager.commits);
        verify(publisher).publishEvent(
                com.jimeng.dataserver.ai.connector.service.SemanticEnrichmentRequest.afterAgentGeneration(20L, "tenant-a"));
    }

    @Test
    @DisplayName("STAGED 替换成功（READY）后同样发事件")
    void stagedReadyPublishesEnrichment() {
        org.springframework.context.ApplicationEventPublisher publisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        finalizer.setApplicationEventPublisher(publisher);
        ConnectorSemanticGeneration generation = generation("STAGED");
        ConnectorSchema current = schema("orders", "id", "bigint", 1L);
        stubTables(List.of(coveredTable(current)));
        when(schemaMapper.selectList(any())).thenReturn(List.of(current));
        when(stagedMapper.selectCount(any())).thenReturn(2L);
        when(semanticMapper.selectCount(any())).thenReturn(3L);
        when(semanticMapper.selectList(any())).thenReturn(List.of(semantic("OBJECT"), semantic("FIELD")));

        finalizer.finish(generation, 0.0);

        verify(publisher).publishEvent(
                com.jimeng.dataserver.ai.connector.service.SemanticEnrichmentRequest.afterAgentGeneration(20L, "tenant-a"));
    }

    @Test
    @DisplayName("没定稿成 READY（回滚、零产出）：不发事件")
    void noEventWithoutReady() {
        org.springframework.context.ApplicationEventPublisher publisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        finalizer.setApplicationEventPublisher(publisher);
        ConnectorSemanticGeneration lost = generation("DIRECT");
        stubTables(List.of(table("DONE")));
        when(claim.release(any(), any(), any(), any(Boolean.class), any())).thenReturn(false);
        finalizer.finish(lost, 0.0);

        stubTables(List.of());
        finalizer.finish(generation("STAGED"), 0.0);

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("STAGED 空暂存但主表已有 INFERRED 时拒绝替换并记 FAILED(EMPTY_REPLACE)")
    void emptyReplaceFailsWithoutDeletingOldRows() {
```

- [ ] **Step 3: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='SemanticEnrichmentServiceTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报找不到符号 `SemanticEnrichmentRequest`、`SemanticEnrichmentService`、`getEnrichment`、`setApplicationEventPublisher`。

- [ ] **Step 4: 开关与事件**

`ConnectorProperties.java`：

第 1 处，把：

```java
         * 与上面几项（单次推导）互相独立；总开关默认关，见 {@link SemanticAgent#enabled}。
         */
        private SemanticAgent agent = new SemanticAgent();
    }

    /**
```

替换为：

```java
         * 与上面几项（单次推导）互相独立；总开关默认关，见 {@link SemanticAgent#enabled}。
         */
        private SemanticAgent agent = new SemanticAgent();

        /** 语义层补全链（数据星图 v3 §4）：{@code connector.semantic.enrichment.*}。 */
        private SemanticEnrichment enrichment = new SemanticEnrichment();
    }

    /**
     * 语义层补全链：语义层生成完成后接着跑的「关系发现 → 采样核对 → 业务文字」（数据星图设计 v3 §4）。
     *
     * <p>对账间隔不在这里：{@code @Scheduled} 直接读占位符 {@code connector.semantic.enrichment.reconcile-interval-ms}，改它要重启。
     */
    @Data
    public static class SemanticEnrichment {
        /**
         * 总开关，默认<b>开</b>。关掉后推导成功照旧直接派发采样核对，定时对账不跑，星图照常用已有数据。
         * 走配置绑定，Nacos 改完即生效。
         */
        private boolean enabled = true;
    }

    /**
```

`SemanticEnrichmentRequest.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import java.util.Set;

/**
 * 「这条连接的语义层刚变了，请跑一遍补全链」（数据星图设计 v3 §4）。进程内的 Spring 事件，不出网、不落库。
 *
 * <p>为什么用事件而不是直接调用：补全链要回调推导服务派发采样核对，推导服务又是它的触发方，构造器互相依赖会闭合启动期的环；
 * 事件是单向的。
 *
 * @param deriveNote      推导刚写下的那句结论；采样核对把它当阶段 note 的前缀保留
 * @param validationScope 触发方原本要核对的表（按 {@link SemanticRowAssembler#fold} 折叠）；{@code null} = 全部，空 = 没有
 */
public record SemanticEnrichmentRequest(Long connectorId, String tenantId, Trigger trigger, String deriveNote,
                                        Set<String> validationScope) {

    public enum Trigger {
        /** 规则推导（整体重新生成）成功：原本要核对全部表。 */
        DERIVE,
        /** 增量补写成功：原本只核对这批多出了行的表。 */
        DERIVE_ADDED,
        /** agent 生成定稿：这条路径原来就不做采样核对，补全链同样跳过。 */
        AGENT,
        /** 定时对账发现输入变了：只核对关系发现新补出关系的表。 */
        RECONCILE
    }

    public static SemanticEnrichmentRequest afterDerive(Long connectorId, String tenantId, String note) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.DERIVE, note, null);
    }

    public static SemanticEnrichmentRequest afterAddedDerive(Long connectorId, String tenantId, String note,
                                                             Set<String> scope) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.DERIVE_ADDED, note,
                scope == null ? Set.of() : Set.copyOf(scope));
    }

    public static SemanticEnrichmentRequest afterAgentGeneration(Long connectorId, String tenantId) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.AGENT, null, Set.of());
    }

    public static SemanticEnrichmentRequest reconcile(Long connectorId, String tenantId) {
        return new SemanticEnrichmentRequest(connectorId, tenantId, Trigger.RECONCILE, null, Set.of());
    }

    /** 这次触发原本会不会派发采样核对（agent 路径不会）。补全链排不上时据此决定要不要自己先把核对派出去。 */
    public boolean validates() {
        return trigger != Trigger.AGENT && (validationScope == null || !validationScope.isEmpty());
    }
}
```

- [ ] **Step 5: 两个触发点**

`ConnectorSemanticDeriveService.java`：

第 1 处，把：

```java
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
```

替换为：

```java
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
```

第 2 处，把：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSemanticDeriveService {

    // ── connection.semantic_status ──
    public static final String SEM_RUNNING = "RUNNING";
```

替换为：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSemanticDeriveService implements ApplicationEventPublisherAware {

    // ── connection.semantic_status ──
    public static final String SEM_RUNNING = "RUNNING";
```

第 3 处，把：

```java
     */
    private final RedissonClient redissonClient;

    /**
     * 推导用的模型。留空即<b>不下发 model</b>，由 {@code GenericChatClient} 回落到
     * {@code providers.<active>.chat.model}（它是 putIfAbsent，缺 model 不会报错）。
```

替换为：

```java
     */
    private final RedissonClient redissonClient;

    /**
     * 推导写完之后把后续交给补全链的事件总线（数据星图设计 v3 §4）。
     *
     * <p>不走构造器注入：补全链要回调本类的 {@link #dispatchValidation} 派发采样核对，构造器互相依赖会闭合启动期的环；
     * 事件是单向的。单测按位置构造本类时这里是 {@code null}，推导成功后照旧直接派发采样核对。
     */
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 推导用的模型。留空即<b>不下发 model</b>，由 {@code GenericChatClient} 回落到
     * {@code providers.<active>.chat.model}（它是 putIfAbsent，缺 model 不会报错）。
```

第 4 处，把：

```java

            // ★ 说明书【已经可用了】才派发验证阶段：READY 已经写下去，关系那一栏如实写着「未经数据验证」。
            //   验证是把那句话往前推一格，不是这份说明书能不能用的前提——所以它绝不该挂在 READY 前面。
            dispatchValidation(connectorId, tenantOf(conn), note);

            log.info("语义层推导完成 connectorId={} 写入 {} 行：表用途 {} / 字段 {} / 关系 {} / 待确认口径 {}；"
                            + "丢弃：无依据 {}、名字对不上 {}、重复 {}、超长 {}；已答过的口径跳过 {}",
```

替换为：

```java

            // ★ 说明书【已经可用了】才派发验证阶段：READY 已经写下去，关系那一栏如实写着「未经数据验证」。
            //   验证是把那句话往前推一格，不是这份说明书能不能用的前提——所以它绝不该挂在 READY 前面。
            afterDerive(connectorId, tenantOf(conn), note, null, false);

            log.info("语义层推导完成 connectorId={} 写入 {} 行：表用途 {} / 字段 {} / 关系 {} / 待确认口径 {}；"
                            + "丢弃：无依据 {}、名字对不上 {}、重复 {}、超长 {}；已答过的口径跳过 {}",
```

第 5 处，把：

```java
            // ★ 验证只派给这次真的多出了行的表（理由见 tablesWithNewRows）。一行都没多出来就不派：
            //   按「送进过模型的表」派，一张模型写不出东西的表会每个冷却期都在客户库上重跑一遍表形态测量和列取值采集。
            Set<String> toValidate = tablesWithNewRows(scoped, existing, updatableNames, w);
            if (!toValidate.isEmpty()) {
                dispatchValidation(connectorId, tenantOf(conn), note, toValidate);
            }

            log.info("语义层增量推导完成 connectorId={} 待补写 {} 张（其中新增 {}，本次覆盖 {}） 插入 {} 原地更新 {} "
                            + "未动人工 {} 保持原样 {} 撞键 {} 超范围丢弃 {}",
```

替换为：

```java
            // ★ 验证只派给这次真的多出了行的表（理由见 tablesWithNewRows）。一行都没多出来就不派：
            //   按「送进过模型的表」派，一张模型写不出东西的表会每个冷却期都在客户库上重跑一遍表形态测量和列取值采集。
            Set<String> toValidate = tablesWithNewRows(scoped, existing, updatableNames, w);
            afterDerive(connectorId, tenantOf(conn), note, toValidate, true);

            log.info("语义层增量推导完成 connectorId={} 待补写 {} 张（其中新增 {}，本次覆盖 {}） 插入 {} 原地更新 {} "
                            + "未动人工 {} 保持原样 {} 撞键 {} 超范围丢弃 {}",
```

第 6 处，把：

```java
     * @param scope 只验这些表（折叠过大小写的表名）；{@code null} = 全部。
     *              增量推导传它：为 3 张新表把 200 张老表的列取值再全量采一遍，花的是客户的库。
     */
    private void dispatchValidation(Long connectorId, String tenantId, String deriveNote, Set<String> scope) {
        if (!validateStageEnabled || connectorId == null) {
            return;
        }
```

替换为：

```java
     * @param scope 只验这些表（折叠过大小写的表名）；{@code null} = 全部。
     *              增量推导传它：为 3 张新表把 200 张老表的列取值再全量采一遍，花的是客户的库。
     */
    public void dispatchValidation(Long connectorId, String tenantId, String deriveNote, Set<String> scope) {
        if (!validateStageEnabled || connectorId == null) {
            return;
        }
```

第 7 处，把：

```java
        }
    }

    /**
     * 手工触发一次采样验证阶段（管理台入口用）。立刻返回。
     *
```

替换为：

```java
        }
    }

    /**
     * 推导（整体 / 增量）写完之后：补全链开着，就把后续交给它——它先做关系发现，再由它派发采样核对（范围并上它新补出关系的表），
     * 然后生成业务文字；关着（或单测里没接事件总线），照旧直接派发采样核对。
     *
     * @param scope 原本要核对的表（折叠名）；{@code null} = 全部，空 = 这次没有要核对的
     */
    private void afterDerive(Long connectorId, String tenantId, String note, Set<String> scope, boolean added) {
        if (eventPublisher != null && properties.getSemantic().getEnrichment().isEnabled() && !blank(tenantId)) {
            eventPublisher.publishEvent(added
                    ? SemanticEnrichmentRequest.afterAddedDerive(connectorId, tenantId, note, scope)
                    : SemanticEnrichmentRequest.afterDerive(connectorId, tenantId, note));
            return;
        }
        if (scope == null || !scope.isEmpty()) {
            dispatchValidation(connectorId, tenantId, note, scope);
        }
    }

    /**
     * 手工触发一次采样验证阶段（管理台入口用）。立刻返回。
     *
```

`SemanticGenerationFinalizer.java`：

第 1 处，把：

```java
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticCoverage;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
```

替换为：

```java
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticCoverage;
import com.jimeng.dataserver.ai.connector.service.SemanticEnrichmentRequest;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
```

第 2 处，把：

```java
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Completes one owned semantic generation without ever dispatching sampling validation. */
@Component
public class SemanticGenerationFinalizer {

    private static final String RUNNING = "RUNNING";
    private static final String FINALIZING = "FINALIZING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final String INTERRUPTED = "INTERRUPTED";
```

替换为：

```java
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Completes one owned semantic generation without ever dispatching sampling validation.
 *
 * <p>定稿为 READY 并提交之后发一个 {@link SemanticEnrichmentRequest#afterAgentGeneration} 事件：补全链接着做关系发现和业务文字，
 * 但同样不派发采样核对（这条路径原来就不做，数据星图设计 v3 §4）。
 */
@Component
public class SemanticGenerationFinalizer implements ApplicationEventPublisherAware {

    private static final String RUNNING = "RUNNING";
    private static final String FINALIZING = "FINALIZING";
    private static final String READY = "READY";
    private static final String FAILED = "FAILED";
    private static final String INTERRUPTED = "INTERRUPTED";
```

第 3 处，把：

```java
    private final SemanticConnectionClaim connectionClaim;
    private final SemanticGenerationHeartbeat heartbeat;
    private final SemanticGenerationNotes notes;
    private final ConnectorSemanticService semanticService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public SemanticGenerationFinalizer(ConnectorSemanticGenerationMapper generationMapper,
                                       ConnectorSemanticGenerationTableMapper tableMapper,
                                       ConnectorSchemaMapper schemaMapper,
                                       ConnectorSemanticMapper semanticMapper,
```

替换为：

```java
    private final SemanticConnectionClaim connectionClaim;
    private final SemanticGenerationHeartbeat heartbeat;
    private final SemanticGenerationNotes notes;
    private final ConnectorSemanticService semanticService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    /** 单测按构造器建本类时是 {@code null}：不发事件，行为与接入补全链之前一致。 */
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public SemanticGenerationFinalizer(ConnectorSemanticGenerationMapper generationMapper,
                                       ConnectorSemanticGenerationTableMapper tableMapper,
                                       ConnectorSchemaMapper schemaMapper,
                                       ConnectorSemanticMapper semanticMapper,
```

第 4 处，把：

```java
        this.notes = notes;
        this.semanticService = semanticService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public FinishResult finish(ConnectorSemanticGeneration generation, double maxGaveUpRatio) {
        requireOwned(generation);
        if (settleBoundary(generation, boundary())) {
            return FinishResult.DONE;
        }
        // Finalizer 会在 promote 前先做若干 STAGED 小事务；租户归属必须早于这些写入验证。
```

替换为：

```java
        this.notes = notes;
        this.semanticService = semanticService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** READY 已经提交之后才发：补全链读的是定稿后的语义层。 */
    private void publishReady(ConnectorSemanticGeneration generation) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(SemanticEnrichmentRequest.afterAgentGeneration(
                    generation.getConnectorId(), generation.getTenantId()));
        }
    }

    public FinishResult finish(ConnectorSemanticGeneration generation, double maxGaveUpRatio) {
        requireOwned(generation);
        if (settleBoundary(generation, boundary())) {
            return FinishResult.DONE;
        }
        // Finalizer 会在 promote 前先做若干 STAGED 小事务；租户归属必须早于这些写入验证。
```

第 5 处，把：

```java

    private void finishDirect(ConnectorSemanticGeneration generation) {
        heartbeat.stop();
        if (settleBoundary(generation, boundary())) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            semanticService.requireOwned(generation.getConnectorId());
            connectionClaim.lockRow(generation.getConnectorId());
            SemanticGenerationNotes.ReadyStats stats = readyStats(generation);
            String note = notes.ready(generation, stats);
            ConnectorSemanticGeneration update = terminalUpdate(READY, null, note);
            if (updateOwned(generation, update, List.of(FINALIZING)) == 0
                    || !connectionClaim.release(generation.getConnectorId(), READY, note, true,
                            coverageOf(generation, stats))) {
                tx.setRollbackOnly();
            }
        });
    }

    private FinishResult finishStaged(ConnectorSemanticGeneration generation) {
        boolean stopped = false;
        for (int attempt = 1; attempt <= MAX_PROMOTE_ATTEMPTS; attempt++) {
            Precheck precheck = precheck(generation);
```

替换为：

```java

    private void finishDirect(ConnectorSemanticGeneration generation) {
        heartbeat.stop();
        if (settleBoundary(generation, boundary())) {
            return;
        }
        boolean[] ready = {false};
        transactionTemplate.executeWithoutResult(tx -> {
            semanticService.requireOwned(generation.getConnectorId());
            connectionClaim.lockRow(generation.getConnectorId());
            SemanticGenerationNotes.ReadyStats stats = readyStats(generation);
            String note = notes.ready(generation, stats);
            ConnectorSemanticGeneration update = terminalUpdate(READY, null, note);
            if (updateOwned(generation, update, List.of(FINALIZING)) == 0
                    || !connectionClaim.release(generation.getConnectorId(), READY, note, true,
                            coverageOf(generation, stats))) {
                tx.setRollbackOnly();
            } else {
                ready[0] = true;
            }
        });
        if (ready[0]) {
            publishReady(generation);
        }
    }

    private FinishResult finishStaged(ConnectorSemanticGeneration generation) {
        boolean stopped = false;
        for (int attempt = 1; attempt <= MAX_PROMOTE_ATTEMPTS; attempt++) {
            Precheck precheck = precheck(generation);
```

第 6 处，把：

```java
                stopped = true;
            }
            if (settleBoundary(generation, boundary())) {
                return FinishResult.DONE;
            }
            PromoteOutcome outcome = promote(generation, precheck.version());
            if (outcome == PromoteOutcome.READY || outcome == PromoteOutcome.LOST) {
                return FinishResult.DONE;
            }
            if (outcome == PromoteOutcome.EMPTY_REPLACE) {
                failOwned(generation, GenerationReasonCode.EMPTY_REPLACE,
                        "新一轮没有产出任何可写入的行，未替换，上一版原样保留", true,
                        restoredStatus(generation));
```

替换为：

```java
                stopped = true;
            }
            if (settleBoundary(generation, boundary())) {
                return FinishResult.DONE;
            }
            PromoteOutcome outcome = promote(generation, precheck.version());
            if (outcome == PromoteOutcome.READY) {
                publishReady(generation);
                return FinishResult.DONE;
            }
            if (outcome == PromoteOutcome.LOST) {
                return FinishResult.DONE;
            }
            if (outcome == PromoteOutcome.EMPTY_REPLACE) {
                failOwned(generation, GenerationReasonCode.EMPTY_REPLACE,
                        "新一轮没有产出任何可写入的行，未替换，上一版原样保留", true,
                        restoredStatus(generation));
```

- [ ] **Step 6: 补全链**

`SemanticEnrichmentService.java`：

```java
package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.businessview.BusinessViewGenerator;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;

/**
 * 语义层补全链（数据星图设计 v3 §4）：
 * <pre>
 *   ① 关系发现：规则候选 + 整库模型一遍 → 新增或替换 JOIN 行
 *   ② 采样核对：派发原有的采样核对，范围 = 触发方原本要核对的表 ∪ ① 动过的表（agent 路径跳过）
 *   ③ 业务文字：业务领域 → 对象名称与说明 → 关系角色名；只补缺的和输入变了的
 * </pre>
 * ② 是异步派发的，沿用它自己的闸门和补跑机制，和 ③ 同时进行：一个查客户库，一个调模型，互不依赖。
 *
 * <h3>一条连接同一时刻只跑一条</h3>
 * 靠 {@code connector_enrichment_state.claim_at} 认领（多副本也只有一个拿得到），30 分钟过期，跑的过程中每步续期；
 * 认领丢了就中止、不写结果。运行中又来了触发不排队：跑完后由定时对账按输入指纹发现变化再补跑。
 * 但触发方原本要派发的采样核对不能因此丢——抢不到认领时先把它派出去。
 *
 * <h3>定时对账</h3>
 * 每 10 分钟一次，每次最多派发一个连接：输入指纹与上次尝试时不同（从没跑过的也算）、或上次失败且已过 6 小时。
 * 指纹不含采样核对的结论（{@code verified}），否则每轮核对完都会触发一次重跑；只含「是否已核对通过」这一位，
 * 因为一条终点不是唯一键的关系核对通过后就会画出来，需要角色名。
 */
@Slf4j
@Component
public class SemanticEnrichmentService {

    static final long CLAIM_STALE_MINUTES = 30L;
    static final long FAILED_RETRY_HOURS = 6L;
    static final int NOTE_MAX = 500;

    private final ConnectorEnrichmentStateMapper stateMapper;
    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final SemanticRelationDiscovery discovery;
    private final BusinessViewGenerator generator;
    private final ConnectorSemanticDeriveService deriveService;
    private final ConnectorProperties properties;
    /** 字段名与构造器参数名必须叫 semanticStageExecutor：容器里有多个 ThreadPoolTaskExecutor，靠名字消歧。 */
    private final ThreadPoolTaskExecutor semanticStageExecutor;
    private final Clock clock;

    @Autowired
    public SemanticEnrichmentService(ConnectorEnrichmentStateMapper stateMapper, ConnectionMapper connectionMapper,
                                     ConnectorSchemaMapper schemaMapper, ConnectorSemanticMapper semanticMapper,
                                     SemanticRelationDiscovery discovery, BusinessViewGenerator generator,
                                     ConnectorSemanticDeriveService deriveService, ConnectorProperties properties,
                                     ThreadPoolTaskExecutor semanticStageExecutor) {
        this(stateMapper, connectionMapper, schemaMapper, semanticMapper, discovery, generator, deriveService,
                properties, semanticStageExecutor, Clock.systemDefaultZone());
    }

    SemanticEnrichmentService(ConnectorEnrichmentStateMapper stateMapper, ConnectionMapper connectionMapper,
                              ConnectorSchemaMapper schemaMapper, ConnectorSemanticMapper semanticMapper,
                              SemanticRelationDiscovery discovery, BusinessViewGenerator generator,
                              ConnectorSemanticDeriveService deriveService, ConnectorProperties properties,
                              ThreadPoolTaskExecutor semanticStageExecutor, Clock clock) {
        this.stateMapper = stateMapper;
        this.connectionMapper = connectionMapper;
        this.schemaMapper = schemaMapper;
        this.semanticMapper = semanticMapper;
        this.discovery = discovery;
        this.generator = generator;
        this.deriveService = deriveService;
        this.properties = properties;
        this.semanticStageExecutor = semanticStageExecutor;
        this.clock = clock;
    }

    private boolean enabled() {
        return properties.getSemantic().getEnrichment().isEnabled();
    }

    // ------------------------------------------------------------------ 触发

    /** 推导成功、增量补写成功、agent 定稿之后发来的请求。只派发，不在调用线程上跑。 */
    @EventListener
    public void onRequest(SemanticEnrichmentRequest request) {
        if (request == null || request.connectorId() == null || request.tenantId() == null) {
            return;
        }
        if (!enabled()) {
            // 推导侧发事件之后开关才被关掉：原本要派发的采样核对照样派出去，别让它夹在中间丢了。
            dispatchOriginalValidation(request);
            return;
        }
        submit(request);
    }

    /** @return 是否真的交给了线程池。池满被拒绝不报错：原本要派的采样核对先派出去，补全链等下一轮对账。 */
    boolean submit(SemanticEnrichmentRequest request) {
        try {
            semanticStageExecutor.execute(MdcAsyncSupport.wrap("semantic-enrich-" + request.connectorId(),
                    () -> run(request)));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("补全链没有派发出去（后台队列已满），等下一轮定时对账 connectorId={}", request.connectorId());
            dispatchOriginalValidation(request);
            return false;
        }
    }

    // ------------------------------------------------------------------ 一轮

    void run(SemanticEnrichmentRequest request) {
        Long connectorId = request.connectorId();
        if (!request.tenantId().equals(TenantContext.get())) {
            TenantContext.set(request.tenantId());
        }
        ConnectorEnrichmentState state = ensureState(connectorId, request.tenantId());
        Date claimedAt = now();
        if (state == null || stateMapper.claim(request.tenantId(), connectorId, claimedAt,
                new Date(claimedAt.getTime() - Duration.ofMinutes(CLAIM_STALE_MINUTES).toMillis())) != 1) {
            log.info("补全链：这条连接已有一轮在跑，本次不排队，跑完后由定时对账补跑 connectorId={}", connectorId);
            dispatchOriginalValidation(request);
            return;
        }
        Claim claim = new Claim(state.getId(), claimedAt);
        String before = fingerprint(connectorId);
        String passFingerprint = state.getRelationPassFingerprint();
        String relationNote = null;
        String viewNote = null;
        try {
            SemanticRelationDiscovery.Result rel = discovery.discover(connectorId, passFingerprint);
            relationNote = rel.note();
            passFingerprint = rel.passFingerprint();
            dispatchValidation(request, rel.write().touchedObjects());
            heartbeat(claim);
            BusinessViewGenerator.Result view = generator.generate(connectorId, () -> heartbeat(claim));
            viewNote = view.note();
            String status = rel.modelError() == null
                    ? ConnectorEnrichmentState.STATUS_READY : ConnectorEnrichmentState.STATUS_FAILED;
            release(claim, status, fingerprint(connectorId), passFingerprint, relationNote, viewNote);
            log.info("补全链完成 connectorId={} 触发={} 结果={}；关系：{}；业务文字：{}", connectorId,
                    request.trigger(), status, relationNote, viewNote);
        } catch (ClaimLostException e) {
            log.warn("补全链的认领被别处接走了（跑得太久过了期），这一轮不写结果 connectorId={}", connectorId);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("补全链失败，6 小时后或输入变化时重试 connectorId={}: {}", connectorId, reason, e);
            release(claim, ConnectorEnrichmentState.STATUS_FAILED, before, passFingerprint,
                    relationNote != null ? relationNote : "失败：" + reason,
                    viewNote != null ? viewNote : (relationNote != null ? "失败：" + reason : null));
        }
    }

    /**
     * ②：派发原有的采样核对。范围 = 触发方原本要核对的表 ∪ 关系发现动过的表；原本就是全部（整体推导）时仍是全部。
     * agent 路径原来就不做采样核对，保持现状；范围为空不派发。
     */
    private void dispatchValidation(SemanticEnrichmentRequest request, List<String> touched) {
        if (request.trigger() == SemanticEnrichmentRequest.Trigger.AGENT) {
            return;
        }
        Set<String> scope = null;
        if (request.validationScope() != null) {
            scope = new LinkedHashSet<>(request.validationScope());
            for (String t : touched) {
                scope.add(fold(t));
            }
            if (scope.isEmpty()) {
                return;
            }
        }
        deriveService.dispatchValidation(request.connectorId(), request.tenantId(), request.deriveNote(), scope);
    }

    /** 补全链没跑成（开关关着、池满、别人在跑）时，把触发方原本就要派发的采样核对照原样派出去。 */
    private void dispatchOriginalValidation(SemanticEnrichmentRequest request) {
        if (request.validates()) {
            deriveService.dispatchValidation(request.connectorId(), request.tenantId(), request.deriveNote(),
                    request.validationScope());
        }
    }

    // ------------------------------------------------------------------ 定时对账

    @Scheduled(fixedDelayString = "${connector.semantic.enrichment.reconcile-interval-ms:600000}", initialDelay = 300_000L)
    public void scheduledReconcile() {
        try {
            reconcileOnce();
        } catch (Exception e) {
            // 调度线程上抛出去没有人接，只会让下一轮看起来莫名其妙地没跑。
            log.warn("补全链定时对账失败，下一轮再来", e);
        }
    }

    /**
     * 挑第一个需要补跑的连接派发出去（按连接 id 升序）。
     *
     * @return 派发了的连接 id；这一轮没有要补跑的为 {@code null}
     */
    Long reconcileOnce() {
        if (!enabled()) {
            return null;
        }
        // 选人是跨租户的，所以 runAsSystem；算指纹要读租户隔离表，得回到那一行的真实租户下（同 ConnectorHealthJob）。
        List<Connection> ready = TenantContext.runAsSystem(() -> connectionMapper.selectList(
                new LambdaQueryWrapper<Connection>()
                        .eq(Connection::getSemanticStatus, ConnectorSemanticDeriveService.SEM_READY)
                        .orderByAsc(Connection::getId)));
        Map<Long, ConnectorEnrichmentState> states = new HashMap<>();
        for (ConnectorEnrichmentState s : TenantContext.runAsSystem(() -> stateMapper.selectList(null))) {
            states.put(s.getConnectorId(), s);
        }
        Date now = now();
        for (Connection c : ready == null ? List.<Connection>of() : ready) {
            if (c.getTenantId() == null) {
                continue;
            }
            ConnectorEnrichmentState state = states.get(c.getId());
            if (state != null && claimAlive(state, now)) {
                continue;
            }
            SemanticEnrichmentRequest request = SemanticEnrichmentRequest.reconcile(c.getId(), c.getTenantId());
            boolean dispatched = asTenant(c.getTenantId(), () -> due(c.getId(), state, now) && submit(request));
            if (dispatched) {
                log.info("补全链定时对账：派发 connectorId={}（{}）", c.getId(),
                        state == null || state.getLastStatus() == null ? "从没跑完过" : "输入变了或失败待重试");
                return c.getId();
            }
        }
        return null;
    }

    private boolean due(Long connectorId, ConnectorEnrichmentState state, Date now) {
        if (state == null || state.getLastStatus() == null) {
            return true;
        }
        if (!fingerprint(connectorId).equals(state.getInputFingerprint())) {
            return true;
        }
        return ConnectorEnrichmentState.STATUS_FAILED.equals(state.getLastStatus()) && state.getLastAttemptAt() != null
                && state.getLastAttemptAt().getTime() <= now.getTime() - Duration.ofHours(FAILED_RETRY_HOURS).toMillis();
    }

    private static boolean claimAlive(ConnectorEnrichmentState state, Date now) {
        return state.getClaimAt() != null
                && state.getClaimAt().getTime() > now.getTime() - Duration.ofMinutes(CLAIM_STALE_MINUTES).toMillis();
    }

    private static <T> T asTenant(String tenantId, Supplier<T> body) {
        String previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    // ------------------------------------------------------------------ 输入指纹

    /**
     * 补全链的输入指纹：结构快照里每个对象的名称、content_hash、唯一键、外键；语义层 OBJECT 行的说明；
     * JOIN 行的两端、状态、是否已核对通过、业务方结论。只读需要的列：{@code detail_json} 整份读出来一张宽表就是几十 KB。
     */
    String fingerprint(Long connectorId) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : schemaMapper.selectMaps(new QueryWrapper<ConnectorSchema>()
                .select("object_name", "content_hash",
                        "JSON_EXTRACT(detail_json, '$.extra.unique_keys') AS uk",
                        "JSON_EXTRACT(detail_json, '$.extra.foreign_keys') AS fk")
                .eq("connector_id", connectorId))) {
            lines.add("S|" + row.get("object_name") + '|' + row.get("content_hash") + '|' + row.get("uk") + '|'
                    + row.get("fk"));
        }
        for (ConnectorSemantic s : semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .select(ConnectorSemantic::getScope, ConnectorSemantic::getObjectName, ConnectorSemantic::getFieldName,
                        ConnectorSemantic::getGloss, ConnectorSemantic::getDetailJson, ConnectorSemantic::getStatus,
                        ConnectorSemantic::getVerified)
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .in(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT,
                        ConnectorSemanticService.SCOPE_JOIN))) {
            if (ConnectorSemanticService.SCOPE_OBJECT.equals(s.getScope())) {
                lines.add("O|" + fold(s.getObjectName()) + '|' + s.getGloss());
                continue;
            }
            Map<String, Object> d = detail(s.getDetailJson());
            lines.add("J|" + fold(s.getObjectName()) + '|' + fold(s.getFieldName()) + '|'
                    + d.get(ConnectorSemanticService.KEY_TO_OBJECT) + '|' + d.get(ConnectorSemanticService.KEY_TO_COLUMN)
                    + '|' + s.getStatus() + '|' + ConnectorSemanticService.V_CONFIRMED.equals(s.getVerified()) + '|'
                    + d.get(ConnectorSemanticService.KEY_HUMAN_VERDICT));
        }
        lines.sort(null);
        return ConnectorSemanticService.sha256(String.join("\n", lines));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ 认领与状态

    /** 状态行不存在就建一行（并发建撞唯一键时重读）。 */
    private ConnectorEnrichmentState ensureState(Long connectorId, String tenantId) {
        ConnectorEnrichmentState state = selectState(connectorId);
        if (state != null) {
            return state;
        }
        ConnectorEnrichmentState fresh = new ConnectorEnrichmentState();
        fresh.setTenantId(tenantId);
        fresh.setConnectorId(connectorId);
        try {
            stateMapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            return selectState(connectorId);
        }
    }

    private ConnectorEnrichmentState selectState(Long connectorId) {
        return stateMapper.selectOne(new LambdaQueryWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getConnectorId, connectorId)
                .last("limit 1"));
    }

    /** 续期：认领时间仍是自己写下的那个才续得上；续不上说明过期被别人接走了，中止这一轮。 */
    private void heartbeat(Claim claim) {
        Date next = now();
        if (next.equals(claim.claimedAt)) {
            // 同一秒内不必续；而且驱动若按「实际改动行数」计数，写一个相同的值会报 0 行，被误判成认领丢了。
            return;
        }
        int n = stateMapper.update(null, new LambdaUpdateWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getId, claim.stateId)
                .eq(ConnectorEnrichmentState::getClaimAt, claim.claimedAt)
                .set(ConnectorEnrichmentState::getClaimAt, next));
        if (n != 1) {
            throw new ClaimLostException();
        }
        claim.claimedAt = next;
    }

    private void release(Claim claim, String status, String inputFingerprint, String passFingerprint,
                         String relationNote, String viewNote) {
        int n = stateMapper.update(null, new LambdaUpdateWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getId, claim.stateId)
                .eq(ConnectorEnrichmentState::getClaimAt, claim.claimedAt)
                .set(ConnectorEnrichmentState::getClaimAt, null)
                .set(ConnectorEnrichmentState::getLastStatus, status)
                .set(ConnectorEnrichmentState::getInputFingerprint, inputFingerprint)
                .set(ConnectorEnrichmentState::getRelationPassFingerprint, passFingerprint)
                .set(ConnectorEnrichmentState::getFinishedAt, now())
                .set(ConnectorEnrichmentState::getRelationNote, clip(relationNote))
                .set(ConnectorEnrichmentState::getViewNote, clip(viewNote)));
        if (n != 1) {
            log.warn("补全链收尾时认领已经不是自己的了，结果不写 stateId={}", claim.stateId);
        }
    }

    /** DATETIME 只到秒：认领时间截到秒再写，之后按它比对才对得上。 */
    private Date now() {
        return Date.from(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    private static String clip(String s) {
        return s == null || s.length() <= NOTE_MAX ? s : s.substring(0, NOTE_MAX - 1) + "…";
    }

    private static final class Claim {
        private final Long stateId;
        private Date claimedAt;

        private Claim(Long stateId, Date claimedAt) {
            this.stateId = stateId;
            this.claimedAt = claimedAt;
        }
    }

    /** 认领过期被别处接走：这一轮的结果不能再写。 */
    static final class ClaimLostException extends RuntimeException {
        ClaimLostException() {
            super("补全链的认领已被别处接走", null, false, false);
        }
    }
}
```

- [ ] **Step 7: 跑补全链与两个触发点所在类的测试**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='SemanticEnrichmentServiceTest*,SemanticGenerationFinalizerTest*,ConnectorSemanticStageWiringTest*,ConnectorSemanticAddedDeriveTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 四个文件都 `failures=0`；SemanticEnrichmentServiceTest 19 个用例；另三个文件分别多出 3、2、1 个用例。

- [ ] **Step 8: 跑 data-server 全量测试**

补全链改了推导服务与 agent 定稿的收尾，这一步必须全量跑（约 5 分钟）：

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && mvn -o -q -pl modules/data-server test > /tmp/v3-task9-full.log 2>&1; \
  cd modules/data-server/target/surefire-reports && echo "cases=$(cat TEST-*.xml | grep -c '<testcase') failures=$(cat TEST-*.xml | grep -c '<failure\|<error')"
```
Expected: `failures=0`（验证时是 2273 个用例）。

- [ ] **Step 9: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector && \
git commit -q -F - <<'MSG'
feat(connector): 语义层补全链——关系发现、采样核对、业务文字；推导与 agent 定稿后自动触发，定时对账补跑

推导服务与 agent 定稿发事件（ApplicationEventPublisherAware，避免与补全链构造器互相依赖）；
补全链认领 + 续期，一条连接同一时刻只跑一条；没跑成的那次触发照样派出原本的采样核对。
定时对账按输入指纹补跑，失败 6 小时退避；开关 connector.semantic.enrichment.enabled 代码默认开。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 10: 星图投影合并业务视图（data-service）

spec §7、§6.3。出网对象按 §7.2 加字段（`label` 换成 `role`）；判定表加第 7′ 行：关系发现里模型那一遍推出的（`detail_json.origin=RELATION_PASS`）未确认不画（Task 6 的说明）；投影合并业务视图：标题取业务名 → 像名称的表注释（不能夹着代码或禁用词）→ 表名，`nameSource` 说来自哪一档；关系与自关联的角色名取业务视图 → 干净的起点列注释（同样过 `fitsFallback`）→ `null`；判定表加 1′（同表同列丢弃，v2 审查 #3）；自关联带可信度、来源、角色名（#4）；`truncated`（快照对象数达到上限，#2）与 `viewStatus`（补全链认领未过期为 `RUNNING`，否则上次结果）在系统列表和系统图里都带上。上限只有一个来源：`ConnectorSchemaService.MAX_OBJECTS` 改 `public`，推导服务的镜像常量改为引用它。性能测试改成量服务层 + 序列化（#8）。

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphViews.java`（整份覆盖）
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjector.java`（整份覆盖）
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphService.java`（整份覆盖）
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSchemaService.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticDeriveService.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphFixtures.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorTest.java`
- Modify: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphServiceTest.java`
- Delete: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorPerformanceTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphPerformanceTest.java`

**Interfaces:**
- Consumes: Task 3 `RelationCandidates.ORIGIN_RELATION_PASS`；Task 5 实体与 Mapper；Task 7 `BusinessTextRules.identifiers / fitsFallback`。
- Produces（前端 Task 12 的类型照它写）：
  - `SystemSummary(connectorId, name, displayName, kind, status, semanticStatus, int tableCount, boolean truncated, String viewStatus)`；
  - `SystemGraph(connectorId, name, displayName, semanticStatus, boolean truncated, String viewStatus, tables, relations)`；
  - `SelfReference(fromColumn, toColumn, tier, confirmedBy, role)`；
  - `TableCard(name, displayName, nameSource, summary, domain, comment, objectType, boolean related, selfReferences, keyColumns, relationColumns, int fieldCount)`；
  - `Relation(id, fromTable, fromColumn, toTable, toColumn, cardinality, tier, confirmedBy, role, discriminatorColumn)`；
  - `TableDetail(name, displayName, nameSource, summary, domain, comment, objectType, selfReferences, fields, relations)`；
  - `DataGraphProjector.system(Connection, List<ConnectorSchema>, List<ConnectorSemantic>, List<ConnectorBusinessView>, String viewStatus)`、`table(schemas, joins, views, name)`、`truncated(int)`；`NAME_BUSINESS_VIEW / NAME_COMMENT / NAME_PHYSICAL`。

- [ ] **Step 1: 改测试（先失败）**

`DataGraphFixtures.java`：

第 1 处，把：

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
```

替换为：

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
```

第 2 处，把：

```java
        return row;
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
```

替换为：

```java
        return row;
    }

    /** 一个对象的业务视图行（补全链写的 MODEL 行）。 */
    static ConnectorBusinessView objectView(String table, String displayName, String summary, String domain) {
        ConnectorBusinessView v = new ConnectorBusinessView();
        v.setConnectorId(CONNECTOR_ID);
        v.setKind(ConnectorBusinessView.KIND_OBJECT);
        v.setObjectName(table);
        v.setFieldName("");
        v.setDisplayName(displayName);
        v.setSummary(summary);
        v.setDomain(domain);
        v.setSource(ConnectorBusinessView.SOURCE_MODEL);
        v.setInputHash("HASH_SECRET");
        v.setModelCode("MODEL_SECRET");
        v.setPromptVersion("PV_SECRET");
        return v;
    }

    /** 一条关系的角色名。 */
    static ConnectorBusinessView relationView(String table, String column, String role) {
        ConnectorBusinessView v = objectView(table, role, null, null);
        v.setKind(ConnectorBusinessView.KIND_RELATION);
        v.setFieldName(column);
        return v;
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
```

`DataGraphProjectorTest.java`：

第 1 处，把：

```java
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.ColumnRef;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Field;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Relation;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableCard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
```

替换为：

```java
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.ColumnRef;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Field;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Relation;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SelfReference;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableCard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
```

第 2 处，把：

```java
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.json;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.schemas;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
```

替换为：

```java
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.json;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.objectView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.relationView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.schemas;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
```

第 3 处，把：

```java
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设计文档 §5.2–§5.5：关系判定、唯一键与基数、表名与注释、排序，以及「永不出网」。 */
class DataGraphProjectorTest {

    @Nested
```

替换为：

```java
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设计文档 §7：关系判定、唯一键与基数、业务文字与兜底、排序，以及「永不出网」。 */
class DataGraphProjectorTest {

    @Nested
```

第 4 处，把：

```java
            assertFalse(json(confirmed).contains("SECRET_VALUE"));
        }

        @Test
        @DisplayName("8. 未核对 / 判不出来：终点列单独构成唯一键才画虚线（主键或唯一键都算）")
        void 结构推断() {
```

替换为：

```java
            assertFalse(json(confirmed).contains("SECRET_VALUE"));
        }

        @Test
        @DisplayName("7′. 关系发现里模型那一遍推出的：未确认不画（终点是主键也不画）；核对通过或业务方确认后照常画")
        void 模型那一遍() {
            Map<String, Object> pass = Map.of("origin", "RELATION_PASS");
            assertTrue(system(join("t_item", "order_id", "t_order", "id", "UNDECIDABLE", pass)).getRelations().isEmpty());
            assertTrue(system(join("t_region", "parent_id", "t_region", "id", "NONE", pass)).getTables().stream()
                    .allMatch(t -> t.getSelfReferences().isEmpty()), "自关联同样不画");
            assertEquals("DATA", system(join("t_item", "order_id", "t_order", "id", "CONFIRMED", pass))
                    .getRelations().get(0).getConfirmedBy());
            assertEquals("BUSINESS", system(join("t_item", "order_id", "t_order", "id", "NONE",
                    Map.of("origin", "RELATION_PASS", "human_verdict", "RELATED"))).getRelations().get(0).getConfirmedBy());
            assertEquals("INFERRED", system(join("t_item", "order_id", "t_order", "id", "NONE",
                    Map.of("origin", "NAME_RULE"))).getRelations().get(0).getTier(), "外键、命名规则推出的照旧画虚线");
        }

        @Test
        @DisplayName("8. 未核对 / 判不出来：终点列单独构成唯一键才画虚线（主键或唯一键都算）")
        void 结构推断() {
```

第 5 处，把：

```java
        void 组合键() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_price", "价格", 7, List.of(pk("sku_id", "region_id")), "sku_id", "region_id", "price"));
            SystemGraph g = DataGraphProjector.system(connection(), rows,
                    List.of(join("t_item", "sku_id", "t_price", "sku_id")));
            assertTrue(g.getRelations().isEmpty());
        }
```

替换为：

```java
        void 组合键() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_price", "价格", 7, List.of(pk("sku_id", "region_id")), "sku_id", "region_id", "price"));
            SystemGraph g = project(rows,
                    List.of(join("t_item", "sku_id", "t_price", "sku_id")));
            assertTrue(g.getRelations().isEmpty());
        }
```

第 6 处，把：

```java
            ConnectorSchema seq = table("s_seq", "序列", 0, List.of(), "id");
            seq.setObjectType("SEQUENCE");
            rows.add(seq);
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of());
            assertTrue(g.getTables().stream().noneMatch(t -> t.getName().equals("s_seq")));
        }
```

替换为：

```java
            ConnectorSchema seq = table("s_seq", "序列", 0, List.of(), "id");
            seq.setObjectType("SEQUENCE");
            rows.add(seq);
            SystemGraph g = project(rows, List.of());
            assertTrue(g.getTables().stream().noneMatch(t -> t.getName().equals("s_seq")));
        }
```

第 7 处，把：

```java
        void 卡片行() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_code", "编码", 8, List.of(uk("uk_b", "a", "b"), uk("uk_a", "code")), "a", "b", "code|编码"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_order", "customer_code", "t_customer", "code")));
            assertEquals(List.of("id"), names(card(g, "t_order").getKeyColumns()));
```

替换为：

```java
        void 卡片行() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_code", "编码", 8, List.of(uk("uk_b", "a", "b"), uk("uk_a", "code")), "a", "b", "code|编码"));
            SystemGraph g = project(rows, List.of(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_order", "customer_code", "t_customer", "code")));
            assertEquals(List.of("id"), names(card(g, "t_order").getKeyColumns()));
```

第 8 处，把：

```java
        }

        @Test
        @DisplayName("线上的字：起点列注释；为空或与列名相同（忽略大小写）时不写")
        void 标签() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_ext", "id", "t_order", "id"),
                    join("t_item", "sku_id", "t_sku", "id", "CONFIRMED", Map.of()));
            assertEquals("所属订单", relation(g, "t_item.order_id→t_order.id").getLabel());
            assertNull(relation(g, "t_ext.id→t_order.id").getLabel());
            assertNull(relation(g, "t_item.sku_id→t_sku.id").getLabel());
        }
    }
```

替换为：

```java
        }

        @Test
        @DisplayName("没有业务视图时，关系角色名退回起点列注释；为空或与列名相同（忽略大小写）时不写")
        void 标签() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_ext", "id", "t_order", "id"),
                    join("t_item", "sku_id", "t_sku", "id", "CONFIRMED", Map.of()));
            assertEquals("所属订单", relation(g, "t_item.order_id→t_order.id").getRole());
            assertNull(relation(g, "t_ext.id→t_order.id").getRole());
            assertNull(relation(g, "t_item.sku_id→t_sku.id").getRole());
        }
    }
```

第 9 处，把：

```java
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_item", "order_id", "t_order", "id"),
                join("t_ext", "id", "t_order", "id"));
        SystemGraph a = DataGraphProjector.system(connection(), schemas(), joins);
        List<ConnectorSemantic> reversedJoins = new ArrayList<>(joins);
        Collections.reverse(reversedJoins);
        List<ConnectorSchema> reversedSchemas = new ArrayList<>(schemas());
        Collections.reverse(reversedSchemas);
        SystemGraph b = DataGraphProjector.system(connection(), reversedSchemas, reversedJoins);

        assertEquals(List.of("t_order", "t_customer", "t_item", "t_ext", "t_region", "t_log", "t_sku", "v_sales"),
                tableNames(a));
```

替换为：

```java
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_item", "order_id", "t_order", "id"),
                join("t_ext", "id", "t_order", "id"));
        SystemGraph a = project(schemas(), joins);
        List<ConnectorSemantic> reversedJoins = new ArrayList<>(joins);
        Collections.reverse(reversedJoins);
        List<ConnectorSchema> reversedSchemas = new ArrayList<>(schemas());
        Collections.reverse(reversedSchemas);
        SystemGraph b = project(reversedSchemas, reversedJoins);

        assertEquals(List.of("t_order", "t_customer", "t_item", "t_ext", "t_region", "t_log", "t_sku", "v_sales"),
                tableNames(a));
```

第 10 处，把：

```java
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_region", "parent_id", "t_region", "id"),
                join("t_ext", "id", "t_order", "id"));
        TableDetail order = DataGraphProjector.table(schemas(), joins, "t_order");
        assertEquals(List.of("id", "customer_id", "customer_code", "shop_code", "status"),
                order.getFields().stream().map(Field::getName).toList());
        assertEquals("PRIMARY", field(order, "id").getKey());
```

替换为：

```java
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_region", "parent_id", "t_region", "id"),
                join("t_ext", "id", "t_order", "id"));
        TableDetail order = detail(schemas(), joins, "t_order");
        assertEquals(List.of("id", "customer_id", "customer_code", "shop_code", "status"),
                order.getFields().stream().map(Field::getName).toList());
        assertEquals("PRIMARY", field(order, "id").getKey());
```

第 11 处，把：

```java
        assertNull(field(order, "shop_code").getComment());
        assertEquals(3, order.getRelations().size());

        TableDetail customer = DataGraphProjector.table(schemas(), joins, "t_customer");
        assertEquals("PRIMARY", field(customer, "id").getKey());
        assertEquals("UNIQUE", field(customer, "code").getKey());
        assertNull(field(customer, "region_id").getKey());
        assertEquals(1, customer.getRelations().size());

        TableDetail region = DataGraphProjector.table(schemas(), joins, "t_region");
        assertTrue(region.getRelations().isEmpty());
        assertEquals(1, region.getSelfReferences().size());
        assertTrue(field(region, "parent_id").isInRelation());

        assertNull(DataGraphProjector.table(schemas(), joins, "t_nope"));
    }

    @Nested
```

替换为：

```java
        assertNull(field(order, "shop_code").getComment());
        assertEquals(3, order.getRelations().size());

        TableDetail customer = detail(schemas(), joins, "t_customer");
        assertEquals("PRIMARY", field(customer, "id").getKey());
        assertEquals("UNIQUE", field(customer, "code").getKey());
        assertNull(field(customer, "region_id").getKey());
        assertEquals(1, customer.getRelations().size());

        TableDetail region = detail(schemas(), joins, "t_region");
        assertTrue(region.getRelations().isEmpty());
        assertEquals(1, region.getSelfReferences().size());
        assertTrue(field(region, "parent_id").isInRelation());

        assertNull(detail(schemas(), joins, "t_nope"));
    }

    @Nested
```

第 12 处，把：

```java
            List<ConnectorSchema> rows = List.of(
                    table("t_order", "订单表", 1, null, "id", "customer_id"),
                    table("t_customer", "客户表", 2, null, "id"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(
                    join("t_order", "customer_id", "t_customer", "id"),
                    join("t_order", "id", "t_customer", "id", "CONFIRMED", Map.of())));
            assertEquals(List.of("t_order.id→t_customer.id"), edges(g));
```

替换为：

```java
            List<ConnectorSchema> rows = List.of(
                    table("t_order", "订单表", 1, null, "id", "customer_id"),
                    table("t_customer", "客户表", 2, null, "id"));
            SystemGraph g = project(rows, List.of(
                    join("t_order", "customer_id", "t_customer", "id"),
                    join("t_order", "id", "t_customer", "id", "CONFIRMED", Map.of())));
            assertEquals(List.of("t_order.id→t_customer.id"), edges(g));
```

第 13 处，把：

```java
                    table("采购 单", "采购单", 1, List.of(pk("编号")), "编号", "客户 编号|客户编号"),
                    table("客户", "客户", 2, List.of(pk("编号")), "编号", "名称"));
            List<ConnectorSemantic> joins = List.of(join("采购 单", "客户 编号", "客户", "编号"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, joins);
            Relation r = g.getRelations().get(0);
            assertEquals("MANY_TO_ONE", r.getCardinality());
            assertEquals("INFERRED", r.getTier());
            assertEquals("客户编号", r.getLabel());
            assertTrue(r.getId().matches("[0-9a-f]{16}"), r.getId());
            assertEquals(1, DataGraphProjector.table(rows, joins, "采购 单").getRelations().size());
        }

        @Test
```

替换为：

```java
                    table("采购 单", "采购单", 1, List.of(pk("编号")), "编号", "客户 编号|客户编号"),
                    table("客户", "客户", 2, List.of(pk("编号")), "编号", "名称"));
            List<ConnectorSemantic> joins = List.of(join("采购 单", "客户 编号", "客户", "编号"));
            SystemGraph g = project(rows, joins);
            Relation r = g.getRelations().get(0);
            assertEquals("MANY_TO_ONE", r.getCardinality());
            assertEquals("INFERRED", r.getTier());
            assertEquals("客户编号", r.getRole());
            assertTrue(r.getId().matches("[0-9a-f]{16}"), r.getId());
            assertEquals(1, detail(rows, joins, "采购 单").getRelations().size());
        }

        @Test
```

第 14 处，把：

```java
        row.setEvidence("NAME");
        row.setTenantId("tenant-x");
        SystemGraph g = system(row);
        TableDetail d = DataGraphProjector.table(schemas(), List.of(row), "t_item");
        for (String out : List.of(json(g), json(d))) {
            for (String banned : List.of("\"gloss\"", "\"confidence\"", "\"basis\"", "\"note\"", "\"care_reason\"",
                    "\"verify_note\"", "\"discriminator_value\"", "\"evidence\"", "\"tenantId\"",
```

替换为：

```java
        row.setEvidence("NAME");
        row.setTenantId("tenant-x");
        SystemGraph g = system(row);
        TableDetail d = detail(schemas(), List.of(row), "t_item");
        for (String out : List.of(json(g), json(d))) {
            for (String banned : List.of("\"gloss\"", "\"confidence\"", "\"basis\"", "\"note\"", "\"care_reason\"",
                    "\"verify_note\"", "\"discriminator_value\"", "\"evidence\"", "\"tenantId\"",
```

第 15 处，把：

```java
    @DisplayName("出网对象能被项目的 Jackson（2.11，不认 record）序列化，布尔字段名不带 is")
    void 序列化() throws Exception {
        List<ConnectorSemantic> joins = List.of(join("t_item", "order_id", "t_order", "id"));
        JsonNode g = MAPPER.readTree(json(DataGraphProjector.system(connection(), schemas(), joins)));
        assertEquals("7", g.get("connectorId").asText());
        assertTrue(g.get("tables").get(0).has("related"));
        JsonNode d = MAPPER.readTree(json(DataGraphProjector.table(schemas(), joins, "t_item")));
        assertTrue(d.get("fields").get(0).has("inRelation"));
        assertTrue(d.get("fields").get(0).has("nullable"));
    }

    // ------------------------------------------------------------------ helpers

    private static SystemGraph system(ConnectorSemantic... joins) {
        return DataGraphProjector.system(connection(), schemas(), List.of(joins));
    }

    private static List<String> edges(SystemGraph g) {
```

替换为：

```java
    @DisplayName("出网对象能被项目的 Jackson（2.11，不认 record）序列化，布尔字段名不带 is")
    void 序列化() throws Exception {
        List<ConnectorSemantic> joins = List.of(join("t_item", "order_id", "t_order", "id"));
        JsonNode g = MAPPER.readTree(json(project(schemas(), joins)));
        assertEquals("7", g.get("connectorId").asText());
        assertTrue(g.get("tables").get(0).has("related"));
        JsonNode d = MAPPER.readTree(json(detail(schemas(), joins, "t_item")));
        assertTrue(d.get("fields").get(0).has("inRelation"));
        assertTrue(d.get("fields").get(0).has("nullable"));
    }

    @Nested
    @DisplayName("v3：业务视图与兜底（§6.3、§7）")
    class BusinessView {

        private SystemGraph withViews(List<ConnectorSemantic> joins, com.jimeng.persistence.entity.ConnectorBusinessView... views) {
            return DataGraphProjector.system(connection(), schemas(), joins, List.of(views), "READY");
        }

        @Test
        @DisplayName("★ 标题、说明、领域取业务视图，来源标 BUSINESS_VIEW；客户原注释留给技术信息区")
        void 业务视图优先() {
            TableCard order = card(withViews(List.of(),
                    objectView("t_order", "销售订单", "一条记录是一张销售订单。", "销售")), "t_order");
            assertEquals("销售订单", order.getDisplayName());
            assertEquals("BUSINESS_VIEW", order.getNameSource());
            assertEquals("一条记录是一张销售订单。", order.getSummary());
            assertEquals("销售", order.getDomain());
            assertEquals("订单表", order.getComment());
        }

        @Test
        @DisplayName("没有业务名：退回像名称的表注释（COMMENT）；说明句、空注释、夹着代码或禁用词的注释都退回表名（PHYSICAL）")
        void 标题兜底() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_code", "t_order 的编码", 9, List.of(pk("id")), "id"));
            rows.add(table("t_est", "估算表", 10, List.of(pk("id")), "id"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(),
                    List.of(objectView("t_customer", null, null, "主数据")), null);
            assertEquals("COMMENT", card(g, "t_order").getNameSource());
            assertEquals("订单表", card(g, "t_order").getDisplayName());
            assertNull(card(g, "t_order").getSummary());
            assertNull(card(g, "t_order").getDomain());
            assertEquals("COMMENT", card(g, "t_customer").getNameSource(), "只有领域、没有业务名的行不算业务名");
            assertEquals("主数据", card(g, "t_customer").getDomain());
            assertEquals("PHYSICAL", card(g, "t_ext").getNameSource());
            assertNull(card(g, "t_ext").getDisplayName());
            assertEquals("PHYSICAL", card(g, "t_region").getNameSource());
            assertEquals("PHYSICAL", card(g, "t_code").getNameSource(), "注释夹着表名，不拿来当业务名");
            assertNull(card(g, "t_code").getDisplayName());
            assertEquals("PHYSICAL", card(g, "t_est").getNameSource(), "注释带禁用词，同样不用");
        }

        @Test
        @DisplayName("★ 关系角色名：业务视图优先；没有就退回起点列注释，注释夹着代码或带禁用词时不用")
        void 角色名() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_pay", "付款", 9, List.of(pk("id")), "id", "order_id|关联 t_order.id"));
            rows.add(table("t_refund", "退款", 10, List.of(pk("id")), "id", "order_id|估算的原订单"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(
                            join("t_item", "order_id", "t_order", "id"),
                            join("t_order", "customer_code", "t_customer", "code"),
                            join("t_pay", "order_id", "t_order", "id"),
                            join("t_refund", "order_id", "t_order", "id")),
                    List.of(relationView("t_order", "customer_code", "下单客户")), null);
            assertEquals("下单客户", relation(g, "t_order.customer_code→t_customer.code").getRole());
            assertEquals("所属订单", relation(g, "t_item.order_id→t_order.id").getRole());
            assertNull(relation(g, "t_pay.order_id→t_order.id").getRole(), "注释夹着代码");
            assertNull(relation(g, "t_refund.order_id→t_order.id").getRole(), "注释带禁用词");
        }

        @Test
        @DisplayName("自关联带上可信度、来源与角色名（v2 审查 #4）")
        void 自关联() {
            SelfReference confirmed = card(withViews(
                    List.of(join("t_region", "parent_id", "t_region", "id", "CONFIRMED", Map.of())),
                    relationView("t_region", "parent_id", "上级地区")), "t_region").getSelfReferences().get(0);
            assertEquals("CONFIRMED", confirmed.getTier());
            assertEquals("DATA", confirmed.getConfirmedBy());
            assertEquals("上级地区", confirmed.getRole());
            SelfReference inferred = card(system(join("t_region", "parent_id", "t_region", "id")), "t_region")
                    .getSelfReferences().get(0);
            assertEquals("INFERRED", inferred.getTier());
            assertNull(inferred.getConfirmedBy());
            assertEquals("上级地区", inferred.getRole(), "没有业务视图时退回列注释");
        }

        @Test
        @DisplayName("1′. 起点和终点是同一张表的同一列：丢弃（v2 审查 #3）")
        void 同表同列() {
            SystemGraph g = system(join("t_order", "id", "t_order", "id", "CONFIRMED", Map.of()));
            assertTrue(card(g, "t_order").getSelfReferences().isEmpty());
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("快照达到 200 个对象：truncated（v2 审查 #2）；整理状态原样带出")
        void 截断与状态() {
            List<ConnectorSchema> rows = new ArrayList<>();
            for (int i = 0; i < 199; i++) {
                rows.add(table(String.format("t_%03d", i), "表", i, List.of(pk("id")), "id"));
            }
            assertFalse(DataGraphProjector.system(connection(), rows, List.of(), List.of(), null).isTruncated());
            rows.add(table("t_199", "表", 199, List.of(pk("id")), "id"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(), List.of(), "RUNNING");
            assertTrue(g.isTruncated());
            assertEquals("RUNNING", g.getViewStatus());
        }

        @Test
        @DisplayName("单表详情同样带业务名、说明、领域")
        void 单表详情() {
            TableDetail d = DataGraphProjector.table(schemas(), List.of(),
                    List.of(objectView("t_item", "订单明细", "一条记录是订单里的一行商品。", "销售")), "t_item");
            assertEquals("订单明细", d.getDisplayName());
            assertEquals("BUSINESS_VIEW", d.getNameSource());
            assertEquals("一条记录是订单里的一行商品。", d.getSummary());
            assertEquals("销售", d.getDomain());
        }

        @Test
        @DisplayName("★ 业务视图的输入指纹、模型、提示词版本、来源都不出网")
        void 内部字段不出网() {
            List<ConnectorSemantic> joins = List.of(join("t_item", "order_id", "t_order", "id"));
            SystemGraph g = withViews(joins, objectView("t_order", "销售订单", "说明", "销售"),
                    relationView("t_item", "order_id", "所属订单"));
            TableDetail d = DataGraphProjector.table(schemas(), joins,
                    List.of(objectView("t_order", "销售订单", "说明", "销售")), "t_order");
            for (String out : List.of(json(g), json(d))) {
                for (String banned : List.of("HASH_SECRET", "MODEL_SECRET", "PV_SECRET", "\"inputHash\"",
                        "\"modelCode\"", "\"promptVersion\"", "\"source\"")) {
                    assertFalse(out.contains(banned), banned + " 出现在 " + out);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SystemGraph system(ConnectorSemantic... joins) {
        return project(schemas(), List.of(joins));
    }

    private static SystemGraph project(List<ConnectorSchema> rows, List<ConnectorSemantic> joins) {
        return DataGraphProjector.system(connection(), rows, joins, List.of(), null);
    }

    private static TableDetail detail(List<ConnectorSchema> rows, List<ConnectorSemantic> joins, String name) {
        return DataGraphProjector.table(rows, joins, List.of(), name);
    }

    private static List<String> edges(SystemGraph g) {
```

`DataGraphServiceTest.java`：

第 1 处，把：

```java
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
```

替换为：

```java
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
```

第 2 处，把：

```java
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
```

替换为：

```java
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.objectView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
```

第 3 处，把：

```java
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private DataGraphService service;

    @BeforeEach
```

替换为：

```java
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorBusinessViewMapper viewMapper;
    private ConnectorEnrichmentStateMapper stateMapper;
    private DataGraphService service;

    @BeforeEach
```

第 4 处，把：

```java
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper);
    }

    @Test
```

替换为：

```java
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorBusinessView.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorEnrichmentState.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        viewMapper = mock(ConnectorBusinessViewMapper.class);
        stateMapper = mock(ConnectorEnrichmentStateMapper.class);
        service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper, viewMapper, stateMapper);
    }

    @Test
```

第 5 处，把：

```java
        assertEquals("ERP 系统", systems.get(0).getDisplayName());
        assertEquals("READY", systems.get(0).getSemanticStatus());
        assertEquals(2, systems.get(0).getTableCount());
    }

    @Test
```

替换为：

```java
        assertEquals("ERP 系统", systems.get(0).getDisplayName());
        assertEquals("READY", systems.get(0).getSemanticStatus());
        assertEquals(2, systems.get(0).getTableCount());
        assertFalse(systems.get(0).isTruncated());
        assertNull(systems.get(0).getViewStatus(), "补全链从没跑过");
    }

    @Test
    @DisplayName("系统列表：快照达到 200 个对象（含非表对象）为 truncated；带上业务文字的整理状态")
    void 截断与整理状态() {
        when(connectionMapper.selectList(any())).thenReturn(List.of(connection()));
        List<ConnectorSchema> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 199; i++) {
            rows.add(schemaRow(7L, "BASE TABLE"));
        }
        rows.add(schemaRow(7L, "SEQUENCE"));
        when(schemaMapper.selectList(any())).thenReturn(rows);
        ConnectorEnrichmentState state = new ConnectorEnrichmentState();
        state.setConnectorId(7L);
        state.setLastStatus("READY");
        when(stateMapper.selectList(any())).thenReturn(List.of(state));

        SystemSummary s = service.systems().get(0);

        assertEquals(199, s.getTableCount());
        assertTrue(s.isTruncated());
        assertEquals("READY", s.getViewStatus());
    }

    @Test
    @DisplayName("整理状态：认领未过期为 RUNNING；过期了看上次结果；没有状态行为 null")
    void 整理状态() {
        ConnectorEnrichmentState running = new ConnectorEnrichmentState();
        running.setClaimAt(new Date(System.currentTimeMillis() - 60_000));
        running.setLastStatus("READY");
        assertEquals("RUNNING", DataGraphService.viewStatus(running));

        ConnectorEnrichmentState stale = new ConnectorEnrichmentState();
        stale.setClaimAt(new Date(System.currentTimeMillis() - 31 * 60_000));
        stale.setLastStatus("FAILED");
        assertEquals("FAILED", DataGraphService.viewStatus(stale));

        assertNull(DataGraphService.viewStatus(null));
    }

    @Test
    @DisplayName("系统图：业务视图交给投影，标题取业务名")
    void 系统图带业务名() {
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(table("t_order", "订单表", 1, List.of(pk("id")), "id")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        when(viewMapper.selectList(any())).thenReturn(List.of(objectView("t_order", "销售订单", "说明", "销售")));

        SystemGraph g = service.system("7");

        assertEquals("销售订单", g.getTables().get(0).getDisplayName());
        assertEquals("BUSINESS_VIEW", g.getTables().get(0).getNameSource());
        assertEquals("销售订单", service.table("7", "t_order").getDisplayName());
    }

    @Test
```

第 6 处，把：

```java
        assertEquals("系统不存在", bad.getRespMsg());
        when(connectionMapper.selectById(99L)).thenReturn(null);
        assertEquals("系统不存在", assertThrows(ServiceException.class, () -> service.system("99")).getRespMsg());
        verifyNoInteractions(schemaMapper, semanticMapper);
    }

    @Test
```

替换为：

```java
        assertEquals("系统不存在", bad.getRespMsg());
        when(connectionMapper.selectById(99L)).thenReturn(null);
        assertEquals("系统不存在", assertThrows(ServiceException.class, () -> service.system("99")).getRespMsg());
        verifyNoInteractions(schemaMapper, semanticMapper, viewMapper, stateMapper);
    }

    @Test
```

第 7 处，把：

```java
    }

    @Test
    @DisplayName("★ 星图的租户隔离完全依赖这三张源表在租户白名单里：少一张，别的租户的表结构就会漏出来")
    void 三张源表都按租户过滤() {
        JimengTenantLineHandler handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
        for (String table : List.of("connection", "connector_schema", "connector_semantic")) {
            assertFalse(handler.ignoreTable(table), table);
        }
    }
```

替换为：

```java
    }

    @Test
    @DisplayName("★ 星图的租户隔离完全依赖这五张源表在租户白名单里：少一张，别的租户的表结构或业务名称就会漏出来")
    void 源表都按租户过滤() {
        JimengTenantLineHandler handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
        for (String table : List.of("connection", "connector_schema", "connector_semantic",
                "connector_business_view", "connector_enrichment_state")) {
            assertFalse(handler.ignoreTable(table), table);
        }
    }
```

删掉只量投影的性能测试，换成量服务层的：

```bash
cd $DS && git rm -q modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorPerformanceTest.java
```

`DataGraphPerformanceTest.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.objectView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.relationView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 性能验收（设计文档 §12，v2 审查 #8）：量的是服务层的 {@code /systems/{id}}——查询结果合并业务视图、投影、再序列化成 JSON，
 * 而不只是投影本身。一条连接 200 张表（每张 30 列）、200 条 JOIN 行、400 行业务视图：预热后连续 50 次，P95 < 300 ms。
 * 数据库往返是按 connector_id 的四条查询，不在这里量。
 */
class DataGraphPerformanceTest {

    @Test
    @DisplayName("200 张表、200 条关系、400 行业务视图：服务层 + 序列化，P95 < 300 ms")
    void 两百张表() throws Exception {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(Connection.class, ConnectorSchema.class, ConnectorSemantic.class,
                ConnectorBusinessView.class, ConnectorEnrichmentState.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
        List<ConnectorSchema> rows = new ArrayList<>();
        List<ConnectorSemantic> joins = new ArrayList<>();
        List<ConnectorBusinessView> views = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String[] columns = new String[30];
            columns[0] = "id|主键";
            columns[1] = "parent_id|上级";
            for (int c = 2; c < columns.length; c++) {
                columns[c] = "col_" + c + "|第 " + c + " 列";
            }
            rows.add(table(name(i), "表" + i, i, List.of(pk("id")), columns));
            views.add(objectView(name(i), "对象" + i, "一条记录是对象" + i + "。", "领域" + (i % 8)));
            if (i > 0) {
                joins.add(join(name(i), "parent_id", name((i - 1) / 4), "id"));
                views.add(relationView(name(i), "parent_id", "上级对象"));
            }
        }
        joins.add(join(name(150), "col_2", name(10), "id", "CONFIRMED", Map.of()));

        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        ConnectorSchemaMapper schemaMapper = mock(ConnectorSchemaMapper.class);
        ConnectorSemanticMapper semanticMapper = mock(ConnectorSemanticMapper.class);
        ConnectorBusinessViewMapper viewMapper = mock(ConnectorBusinessViewMapper.class);
        ConnectorEnrichmentStateMapper stateMapper = mock(ConnectorEnrichmentStateMapper.class);
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(rows);
        when(semanticMapper.selectList(any())).thenReturn(joins);
        when(viewMapper.selectList(any())).thenReturn(views);
        DataGraphService service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper, viewMapper,
                stateMapper);

        for (int i = 0; i < 5; i++) {
            CommonUtil.getObjectMapper().writeValueAsString(service.system("7"));
        }
        long[] nanos = new long[50];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            SystemGraph g = service.system("7");
            CommonUtil.getObjectMapper().writeValueAsString(g);
            nanos[i] = System.nanoTime() - start;
            assertEquals(200, g.getRelations().size());
            assertEquals("对象1", g.getTables().get(1).getDisplayName());
        }
        Arrays.sort(nanos);
        long p95Millis = nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000;
        assertTrue(p95Millis < 300, "P95 = " + p95Millis + " ms");
        System.out.println("数据星图 /systems/{id} 200 表（服务层 + 序列化）P95 = " + p95Millis + " ms");
    }

    private static String name(int i) {
        return String.format("t_%03d", i);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='DataGraph*Test*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报 `system`/`table` 的参数个数不对、找不到 `getNameSource`、`getRole`、`isTruncated`、`objectView` 等。

- [ ] **Step 3: 出网对象、投影、读服务**

`DataGraphViews.java`（整份覆盖）：

```java
package com.jimeng.dataserver.ai.connector.graph;

import lombok.Value;

import java.util.List;

/**
 * 数据星图的出网对象（设计文档 §7.2）。
 *
 * <p>项目实际用的 jackson-databind 是 2.11，不认 record，所以一律用 Lombok {@code @Value}。
 * 这里只放给人看的东西：语义层的原文说明 / 依据 / 置信度 / 判别值、业务视图的输入指纹与模型信息、租户、凭据<b>永不出网</b>。
 */
public final class DataGraphViews {

    private DataGraphViews() {
    }

    /**
     * {@code /systems} 的一项。{@code semanticStatus}、{@code viewStatus} 只给前端判断空状态和提示，不上屏。
     *
     * <p>{@code truncated}：结构快照达到 200 个对象的上限，按重要性排后面的表没进来。
     * {@code viewStatus}：业务文字的整理状态——{@code RUNNING}（补全链正在跑）/ {@code READY} / {@code FAILED}（上次结果）/
     * {@code null}（从没跑过）。
     */
    @Value
    public static class SystemSummary {
        String connectorId;
        String name;
        String displayName;
        String kind;
        String status;
        String semanticStatus;
        int tableCount;
        boolean truncated;
        String viewStatus;
    }

    /** {@code /systems/{connectorId}}：全部对象卡片 + 通过判定的关系（不含自关联）。两个状态字段同 {@link SystemSummary}。 */
    @Value
    public static class SystemGraph {
        String connectorId;
        String name;
        String displayName;
        String semanticStatus;
        boolean truncated;
        String viewStatus;
        List<TableCard> tables;
        List<Relation> relations;
    }

    @Value
    public static class ColumnRef {
        String name;
        String comment;
    }

    /** 自关联（上下级之类）。{@code role} 是业务视图起的角色名，缺了退回干净的列注释，再缺为 {@code null}。 */
    @Value
    public static class SelfReference {
        String fromColumn;
        String toColumn;
        String tier;
        String confirmedBy;
        String role;
    }

    /**
     * 一个业务对象的卡片。
     *
     * <p>{@code displayName}：业务视图的业务名优先，缺了退回像名称的表注释，再缺为 {@code null}（前端显示表名）；
     * {@code nameSource} 说它来自哪一档：{@code BUSINESS_VIEW} / {@code COMMENT} / {@code PHYSICAL}。
     * {@code summary}、{@code domain} 缺了为 {@code null}（前端显示「未分类」）。
     * {@code keyColumns}、{@code relationColumns}、{@code comment} 只给折叠的技术信息区用。
     */
    @Value
    public static class TableCard {
        String name;
        String displayName;
        String nameSource;
        String summary;
        String domain;
        String comment;
        String objectType;
        boolean related;
        List<SelfReference> selfReferences;
        List<ColumnRef> keyColumns;
        List<ColumnRef> relationColumns;
        int fieldCount;
    }

    /** 一条关系。{@code role}：业务视图的角色名，缺了退回干净的起点列注释，再缺为 {@code null}。 */
    @Value
    public static class Relation {
        String id;
        String fromTable;
        String fromColumn;
        String toTable;
        String toColumn;
        String cardinality;
        String tier;
        String confirmedBy;
        String role;
        String discriminatorColumn;
    }

    @Value
    public static class Field {
        String name;
        String type;
        boolean nullable;
        String comment;
        String key;
        boolean inRelation;
    }

    @Value
    public static class TableDetail {
        String name;
        String displayName;
        String nameSource;
        String summary;
        String domain;
        String comment;
        String objectType;
        List<SelfReference> selfReferences;
        List<Field> fields;
        List<Relation> relations;
    }
}
```

`DataGraphProjector.java`（整份覆盖）：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.businessview.BusinessTextRules;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer.NamedKey;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.ColumnRef;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Field;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Relation;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SelfReference;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableCard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.RelationCandidates;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据星图的全部规则（设计文档 §7）：把一条连接的结构快照、语义层 JOIN 行和业务视图投影成给人看的对象关系图。
 *
 * <p>纯函数：不访问数据库、不依赖 Spring，同样的输入永远得到同样的输出（顺序也一样）。
 * 它不产生任何新结论，只做三件事——按固定顺序筛关系、给对象和关系配上业务文字（缺了按 §6.3 兜底）、
 * 把结构元数据留给折叠的技术信息区。
 */
public final class DataGraphProjector {

    public static final String TIER_CONFIRMED = "CONFIRMED";
    public static final String TIER_INFERRED = "INFERRED";
    public static final String BY_DATA = "DATA";
    public static final String BY_BUSINESS = "BUSINESS";
    public static final String MANY_TO_ONE = "MANY_TO_ONE";
    public static final String ONE_TO_ONE = "ONE_TO_ONE";
    public static final String KEY_PRIMARY = "PRIMARY";
    public static final String KEY_UNIQUE = "UNIQUE";
    /** 对象标题来自哪一档（§6.3）：业务视图 → 像名称的表注释 → 表名。 */
    public static final String NAME_BUSINESS_VIEW = "BUSINESS_VIEW";
    public static final String NAME_COMMENT = "COMMENT";
    public static final String NAME_PHYSICAL = "PHYSICAL";

    /** 表注释「像名称」的长度上限（字符数），超过就只当说明、不当标题。 */
    static final int NAME_MAX_CHARS = 16;
    private static final String SENTENCE_MARKS = "，。；,;.\n\r";

    // detail_json 里的结构键。写入方是 SemanticJoinValidator.JoinVerdict#structuralPatch；
    // 各读取方（推导、工具执行器）都各自声明常量，这里同样。
    private static final String KEY_JOIN_KIND = "join_kind";
    private static final String KEY_DISCRIMINATOR_COLUMN = "discriminator_column";
    /** 关系是谁提出来的（{@code RelationCandidates.ORIGIN_*}），写入方是关系发现。 */
    private static final String KEY_ORIGIN = "origin";

    private static final Comparator<Relation> RELATION_ORDER = Comparator
            .comparing(Relation::getFromTable)
            .thenComparing(Relation::getFromColumn)
            .thenComparing(Relation::getToTable)
            .thenComparing(Relation::getToColumn);

    private DataGraphProjector() {
    }

    /** {@code TABLE} / {@code BASE TABLE} → {@code TABLE}，{@code VIEW} → {@code VIEW}，其余（含 null）不进星图。 */
    public static String objectType(String raw) {
        if (raw == null) {
            return null;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        if ("TABLE".equals(type) || "BASE TABLE".equals(type)) {
            return "TABLE";
        }
        return "VIEW".equals(type) ? "VIEW" : null;
    }

    /** 注释「像一个名称」：不超过 {@value #NAME_MAX_CHARS} 个字符，且不含句子标点和换行。 */
    public static boolean looksLikeName(String comment) {
        if (comment == null || comment.isEmpty() || comment.length() > NAME_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < comment.length(); i++) {
            if (SENTENCE_MARKS.indexOf(comment.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 结构快照是否达到了对象数上限（上限只有一个来源：{@link ConnectorSchemaService#MAX_OBJECTS}）。 */
    public static boolean truncated(int snapshotObjects) {
        return snapshotObjects >= ConnectorSchemaService.MAX_OBJECTS;
    }

    /**
     * @param schemas    这条连接的全部快照行（含 TABLE / VIEW 之外的对象：它们也占快照的名额）
     * @param views      这条连接的业务视图行
     * @param viewStatus 业务文字的整理状态，原样带出（{@link DataGraphViews.SystemSummary}）
     */
    public static SystemGraph system(Connection connection, List<ConnectorSchema> schemas,
                                     List<ConnectorSemantic> joins, List<ConnectorBusinessView> views,
                                     String viewStatus) {
        Projection p = project(schemas, joins, views);
        List<TableCard> cards = new ArrayList<>(p.tables().size());
        for (Table t : p.tables().values()) {
            cards.add(card(t, p));
        }
        return new SystemGraph(String.valueOf(connection.getId()), connection.getName(),
                connection.getDisplayName(), connection.getSemanticStatus(),
                truncated(schemas == null ? 0 : schemas.size()), viewStatus, List.copyOf(cards), p.relations());
    }

    /** @return 表不在快照里（或不是 TABLE / VIEW）时为 {@code null} */
    public static TableDetail table(List<ConnectorSchema> schemas, List<ConnectorSemantic> joins,
                                    List<ConnectorBusinessView> views, String name) {
        Projection p = project(schemas, joins, views);
        Table t = name == null ? null : p.tables().get(name);
        if (t == null) {
            return null;
        }
        List<Relation> incident = p.relations().stream()
                .filter(r -> r.getFromTable().equals(name) || r.getToTable().equals(name))
                .toList();
        List<SelfReference> selfRefs = p.selfReferences().getOrDefault(name, List.of());
        Set<String> involved = new LinkedHashSet<>();
        for (Relation r : incident) {
            if (r.getFromTable().equals(name)) {
                involved.add(r.getFromColumn());
            }
            if (r.getToTable().equals(name)) {
                involved.add(r.getToColumn());
            }
        }
        for (SelfReference s : selfRefs) {
            involved.add(s.getFromColumn());
            involved.add(s.getToColumn());
        }
        Set<String> primary = primaryColumns(t);
        List<Field> fields = new ArrayList<>(t.fields().size());
        for (FieldDetail f : t.fields().values()) {
            String key = primary.contains(f.name()) ? KEY_PRIMARY
                    : singleColumnUnique(t, f.name()) ? KEY_UNIQUE : null;
            fields.add(new Field(f.name(), f.type(), f.nullable(), text(f.comment()), key,
                    involved.contains(f.name())));
        }
        return new TableDetail(t.name(), t.displayName(), t.nameSource(), t.summary(), t.domain(), t.comment(),
                t.objectType(), selfRefs, List.copyOf(fields), incident);
    }

    // ------------------------------------------------------------------ 投影

    private static Projection project(List<ConnectorSchema> schemas, List<ConnectorSemantic> joins,
                                      List<ConnectorBusinessView> views) {
        Map<String, ConnectorBusinessView> byKey = new HashMap<>();
        for (ConnectorBusinessView v : views == null ? List.<ConnectorBusinessView>of() : views) {
            if (v != null && v.getKind() != null) {
                byKey.putIfAbsent(viewKey(v.getKind(), v.getObjectName(), v.getFieldName()), v);
            }
        }
        Words words = new Words(byKey, identifiers(schemas));
        Map<String, Table> tables = tables(schemas, words);
        Map<String, List<SelfReference>> selfRefs = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();
        for (ConnectorSemantic row : joins == null ? List.<ConnectorSemantic>of() : joins) {
            Judged j = judge(row, tables);
            if (j == null) {
                continue;
            }
            Table from = tables.get(j.fromTable());
            if (j.fromTable().equals(j.toTable())) {
                selfRefs.computeIfAbsent(j.fromTable(), k -> new ArrayList<>())
                        .add(new SelfReference(j.fromColumn(), j.toColumn(), j.tier(), j.confirmedBy(),
                                words.role(from, j.fromColumn())));
            } else {
                relations.add(relation(j, tables, words));
            }
        }
        relations.sort(RELATION_ORDER);
        Map<String, List<SelfReference>> sortedSelf = new LinkedHashMap<>();
        selfRefs.forEach((table, refs) -> {
            refs.sort(Comparator.comparing(SelfReference::getFromColumn));
            sortedSelf.put(table, List.copyOf(refs));
        });
        return new Projection(tables, List.copyOf(relations), sortedSelf);
    }

    /** 只收 TABLE / VIEW；按 {@code importance_rank} 升序（空值排最后）再按表名，保证输出顺序确定。 */
    private static Map<String, Table> tables(List<ConnectorSchema> schemas, Words words) {
        List<ConnectorSchema> rows = (schemas == null ? List.<ConnectorSchema>of() : schemas).stream()
                .filter(r -> r != null && r.getObjectName() != null && objectType(r.getObjectType()) != null)
                .sorted(Comparator.comparing(ConnectorSchema::getImportanceRank,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(rows);
        Map<String, Table> out = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            String comment = RowEstimateNote.strip(r.getObjectComment());
            ConnectorBusinessView view = words.object(r.getObjectName());
            String displayName;
            String nameSource;
            if (view != null && text(view.getDisplayName()) != null) {
                displayName = text(view.getDisplayName());
                nameSource = NAME_BUSINESS_VIEW;
            } else if (looksLikeName(comment) && BusinessTextRules.fitsFallback(comment, words.identifiers())) {
                displayName = comment;
                nameSource = NAME_COMMENT;
            } else {
                displayName = null;
                nameSource = NAME_PHYSICAL;
            }
            out.put(r.getObjectName(), new Table(r.getObjectName(), objectType(r.getObjectType()), comment,
                    displayName, nameSource, view == null ? null : text(view.getSummary()),
                    view == null ? null : text(view.getDomain()),
                    fields.getOrDefault(r.getObjectName(), Map.of()),
                    SemanticTableRenderer.namedUniqueKeys(r)));
        }
        return out;
    }

    /**
     * §7.1 的判定表，按顺序、先命中者为准。{@code confidence}、{@code source}、{@code evidence} 不参与。
     *
     * <p>★ 业务方的任何一次确认（{@code annotateJoin} → {@code upsertHuman}）都会把 {@code status} 写成 CONFIRMED、
     * {@code verified} 写成 NONE，否认只记在 {@code human_verdict=UNRELATED}——所以绝不能用 {@code status} 判「已确认」。
     *
     * @return {@code null} = 不画
     */
    private static Judged judge(ConnectorSemantic row, Map<String, Table> tables) {
        if (row == null) {
            return null;
        }
        Map<String, Object> detail = detail(row.getDetailJson());
        String fromTable = row.getObjectName();
        String fromColumn = row.getFieldName();
        String toTable = text(detail.get(ConnectorSemanticService.KEY_TO_OBJECT));
        String toColumn = text(detail.get(ConnectorSemanticService.KEY_TO_COLUMN));
        Table from = fromTable == null ? null : tables.get(fromTable);
        Table to = toTable == null ? null : tables.get(toTable);
        // 1. 两端的表和列都得在当前快照里
        if (from == null || to == null || fromColumn == null || toColumn == null
                || !from.fields().containsKey(fromColumn) || !to.fields().containsKey(toColumn)) {
            return null;
        }
        // 1′. 同一张表的同一列：抽样时这种行的包含率永远是 100%，画出来是一个指向自己的圈
        if (fromTable.equals(toTable) && fromColumn.equals(toColumn)) {
            return null;
        }
        // 2. 结构已变、待重判
        if (ConnectorSemanticService.ST_STALE.equals(row.getStatus())) {
            return null;
        }
        Object verdict = detail.get(ConnectorSemanticService.KEY_HUMAN_VERDICT);
        // 3. 业务方说没关系
        if (ConnectorSemanticService.HV_UNRELATED.equals(verdict)) {
            return null;
        }
        boolean polymorphic = SemanticJoinValidator.KIND_POLYMORPHIC.equals(text(detail.get(KEY_JOIN_KIND)));
        String discriminator = polymorphic ? text(detail.get(KEY_DISCRIMINATOR_COLUMN)) : null;
        // 4. 业务方确认
        if (ConnectorSemanticService.HV_RELATED.equals(verdict)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_CONFIRMED, BY_BUSINESS, discriminator);
        }
        String verified = row.getVerified();
        // 5. 采样核对通过（包含率 ≥ 0.9）
        if (ConnectorSemanticService.V_CONFIRMED.equals(verified)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_CONFIRMED, BY_DATA, discriminator);
        }
        // 6. 数据否定（< 0.5）或只部分成立（0.5–0.9）
        if (ConnectorSemanticService.V_REJECTED.equals(verified) || ConnectorSemanticService.V_WEAK.equals(verified)) {
            return null;
        }
        // 7. 多态关系只在判别列取特定值时成立，未确认前画成无条件的线会误导
        if (polymorphic) {
            return null;
        }
        // 7′. 关系发现里模型那一遍推出的：只给 Agent 当线索。真实库试跑里它把「币种 → 公司代码」这类错线也报了高把握，
        //     终点又常是主键——不挡的话第 8 行会把它们画成虚线。核对通过（第 5 行）或业务方确认（第 4 行）之后照常画。
        if (RelationCandidates.ORIGIN_RELATION_PASS.equals(text(detail.get(KEY_ORIGIN)))) {
            return null;
        }
        // 8. 结构上成立：终点列单独构成终点表的一个唯一键
        if (singleColumnUnique(to, toColumn)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_INFERRED, null, null);
        }
        // 9. 其余不画
        return null;
    }

    private static Relation relation(Judged j, Map<String, Table> tables, Words words) {
        Table from = tables.get(j.fromTable());
        Table to = tables.get(j.toTable());
        return new Relation(relationId(j.fromTable(), j.fromColumn(), j.toTable(), j.toColumn()),
                j.fromTable(), j.fromColumn(), j.toTable(), j.toColumn(),
                cardinality(from, j.fromColumn(), to, j.toColumn()), j.tier(), j.confirmedBy(),
                words.role(from, j.fromColumn()), j.discriminatorColumn());
    }

    /** 终点列单独唯一：起点列也单独唯一为一对一，否则多对一；终点列不唯一（只会出现在已确认关系上）为 {@code null}。 */
    private static String cardinality(Table from, String fromColumn, Table to, String toColumn) {
        if (!singleColumnUnique(to, toColumn)) {
            return null;
        }
        return singleColumnUnique(from, fromColumn) ? ONE_TO_ONE : MANY_TO_ONE;
    }

    /** 唯一键未知（旧快照没有 {@code extra.unique_keys}）按「不是」处理；只是组合键的一部分也不算。 */
    private static boolean singleColumnUnique(Table t, String column) {
        if (t.keys() == null) {
            return false;
        }
        for (NamedKey k : t.keys()) {
            if (k.columns().size() == 1 && k.columns().get(0).equals(column)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> primaryColumns(Table t) {
        Set<String> out = new LinkedHashSet<>();
        if (t.keys() != null) {
            for (NamedKey k : t.keys()) {
                if (k.primary()) {
                    out.addAll(k.columns());
                }
            }
        }
        return out;
    }

    /** 主键列；没有主键时取列数最少的唯一键（并列取键名最小）；都没有为空。 */
    private static List<String> keyColumns(Table t) {
        if (t.keys() == null || t.keys().isEmpty()) {
            return List.of();
        }
        for (NamedKey k : t.keys()) {
            if (k.primary()) {
                return k.columns();
            }
        }
        return t.keys().stream()
                .min(Comparator.comparingInt((NamedKey k) -> k.columns().size()).thenComparing(NamedKey::name))
                .map(NamedKey::columns)
                .orElse(List.of());
    }

    private static TableCard card(Table t, Projection p) {
        TreeSet<String> relationColumns = new TreeSet<>();
        boolean related = false;
        for (Relation r : p.relations()) {
            if (r.getFromTable().equals(t.name())) {
                relationColumns.add(r.getFromColumn());
                related = true;
            }
            if (r.getToTable().equals(t.name())) {
                relationColumns.add(r.getToColumn());
                related = true;
            }
        }
        return new TableCard(t.name(), t.displayName(), t.nameSource(), t.summary(), t.domain(), t.comment(),
                t.objectType(), related,
                p.selfReferences().getOrDefault(t.name(), List.of()),
                keyColumns(t).stream().map(c -> columnRef(t, c)).toList(),
                relationColumns.stream().map(c -> columnRef(t, c)).toList(),
                t.fields().size());
    }

    private static ColumnRef columnRef(Table t, String column) {
        FieldDetail f = t.fields().get(column);
        return new ColumnRef(column, f == null ? null : text(f.comment()));
    }

    /** 四段做长度前缀编码后取 SHA-256 的前 16 位十六进制：表名、列名里的任何字符都不会让两条关系撞成同一个 id。 */
    private static String relationId(String fromTable, String fromColumn, String toTable, String toColumn) {
        StringBuilder source = new StringBuilder();
        for (String part : List.of(fromTable, fromColumn, toTable, toColumn)) {
            source.append(part.length()).append(':').append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 这个系统的物理标识符：兜底用的客户注释里夹着它们就不拿来当业务文字（§6.3）。 */
    private static Set<String> identifiers(List<ConnectorSchema> schemas) {
        List<ConnectorSchema> rows = schemas == null ? List.of() : schemas.stream()
                .filter(r -> r != null && r.getObjectName() != null).toList();
        List<String> columns = new ArrayList<>();
        SemanticRowAssembler.parseFields(rows).values().forEach(cols -> columns.addAll(cols.keySet()));
        return BusinessTextRules.identifiers(rows.stream().map(ConnectorSchema::getObjectName).toList(), columns);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            // 单行 detail_json 坏了只让这一行「没有对端」、被丢弃，不影响整张图。
            return Map.of();
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String viewKey(String kind, String object, String field) {
        return kind + '\u0001' + SemanticRowAssembler.fold(object) + '\u0001'
                + SemanticRowAssembler.fold(field == null ? "" : field);
    }

    /** 业务视图的查法与兜底。 */
    private record Words(Map<String, ConnectorBusinessView> views, Set<String> identifiers) {

        ConnectorBusinessView object(String table) {
            return views.get(viewKey(ConnectorBusinessView.KIND_OBJECT, table, ""));
        }

        /**
         * 关系角色名：业务视图的优先；缺了退回起点列的客户注释（不能等于列名，还得过 {@link BusinessTextRules#fitsFallback}：
         * 不夹代码、不带禁用词），再缺为 null。
         */
        String role(Table from, String column) {
            ConnectorBusinessView v = views.get(viewKey(ConnectorBusinessView.KIND_RELATION, from.name(), column));
            if (v != null && text(v.getDisplayName()) != null) {
                return text(v.getDisplayName());
            }
            FieldDetail f = from.fields().get(column);
            String comment = f == null ? null : text(f.comment());
            if (comment == null || comment.equalsIgnoreCase(column) || !BusinessTextRules.fitsFallback(comment, identifiers)) {
                return null;
            }
            return comment;
        }
    }

    private record Table(String name, String objectType, String comment, String displayName, String nameSource,
                         String summary, String domain, Map<String, FieldDetail> fields, List<NamedKey> keys) {
    }

    private record Judged(String fromTable, String fromColumn, String toTable, String toColumn,
                          String tier, String confirmedBy, String discriminatorColumn) {
    }

    private record Projection(Map<String, Table> tables, List<Relation> relations,
                              Map<String, List<SelfReference>> selfReferences) {
    }
}
```

`DataGraphService.java`（整份覆盖）：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据星图的读取：每次请求从 {@code connection} + {@code connector_schema} + {@code connector_semantic(JOIN)}
 * + 业务视图现算（设计文档 §4）。不排队、不调模型：业务文字由补全链在后台写好，这里只读。
 *
 * <p>五张表都在 {@code TENANT_AWARE_TABLES} 里，查询在调用方租户下进行：别的租户的连接在这里就是查不到。
 */
@Service
@RequiredArgsConstructor
public class DataGraphService {

    /** 补全链的认领多久算过期（与 {@code SemanticEnrichmentService} 一致）：过期了就不再说「正在整理」。 */
    static final Duration CLAIM_STALE = Duration.ofMinutes(30);
    public static final String VIEW_RUNNING = "RUNNING";

    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorBusinessViewMapper viewMapper;
    private final ConnectorEnrichmentStateMapper stateMapper;

    /** 只列出结构快照里至少有一个 TABLE / VIEW 的连接，按连接 id 升序。 */
    public List<SystemSummary> systems() {
        List<Connection> connections = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .orderByAsc(Connection::getId));
        Map<Long, Integer> tableCounts = new HashMap<>();
        Map<Long, Integer> snapshotCounts = new HashMap<>();
        for (ConnectorSchema row : schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .select(ConnectorSchema::getConnectorId, ConnectorSchema::getObjectType))) {
            if (row.getConnectorId() == null) {
                continue;
            }
            snapshotCounts.merge(row.getConnectorId(), 1, Integer::sum);
            if (DataGraphProjector.objectType(row.getObjectType()) != null) {
                tableCounts.merge(row.getConnectorId(), 1, Integer::sum);
            }
        }
        Map<Long, ConnectorEnrichmentState> states = new HashMap<>();
        for (ConnectorEnrichmentState s : stateMapper.selectList(new LambdaQueryWrapper<ConnectorEnrichmentState>())) {
            states.put(s.getConnectorId(), s);
        }
        List<SystemSummary> out = new ArrayList<>();
        for (Connection c : connections) {
            int tables = tableCounts.getOrDefault(c.getId(), 0);
            if (tables > 0) {
                out.add(new SystemSummary(String.valueOf(c.getId()), c.getName(), c.getDisplayName(), c.getKind(),
                        c.getStatus(), c.getSemanticStatus(), tables,
                        DataGraphProjector.truncated(snapshotCounts.getOrDefault(c.getId(), 0)),
                        viewStatus(states.get(c.getId()))));
            }
        }
        return out;
    }

    public SystemGraph system(String connectorId) {
        Connection connection = requireConnection(connectorId);
        Long id = connection.getId();
        return DataGraphProjector.system(connection, schemas(id), joins(id), views(id), viewStatus(state(id)));
    }

    public TableDetail table(String connectorId, String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "表名不能为空");
        }
        Connection connection = requireConnection(connectorId);
        Long id = connection.getId();
        TableDetail detail = DataGraphProjector.table(schemas(id), joins(id), views(id), name.trim());
        if (detail == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "表不存在");
        }
        return detail;
    }

    /**
     * 业务文字的整理状态：认领还没过期 = {@code RUNNING}；否则是上次结果 {@code READY} / {@code FAILED}；从没跑过为 {@code null}。
     */
    static String viewStatus(ConnectorEnrichmentState state) {
        if (state == null) {
            return null;
        }
        Date claim = state.getClaimAt();
        if (claim != null && claim.getTime() > System.currentTimeMillis() - CLAIM_STALE.toMillis()) {
            return VIEW_RUNNING;
        }
        return state.getLastStatus();
    }

    private Connection requireConnection(String connectorId) {
        Long id;
        try {
            id = Long.valueOf(connectorId == null ? "" : connectorId.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "系统不存在");
        }
        Connection connection = connectionMapper.selectById(id);
        if (connection == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "系统不存在");
        }
        return connection;
    }

    private List<ConnectorSchema> schemas(Long connectorId) {
        return schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId));
    }

    private List<ConnectorSemantic> joins(Long connectorId) {
        return semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .eq(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_JOIN));
    }

    private List<ConnectorBusinessView> views(Long connectorId) {
        return viewMapper.selectList(new LambdaQueryWrapper<ConnectorBusinessView>()
                .eq(ConnectorBusinessView::getConnectorId, connectorId));
    }

    private ConnectorEnrichmentState state(Long connectorId) {
        return stateMapper.selectOne(new LambdaQueryWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getConnectorId, connectorId)
                .last("limit 1"));
    }
}
```

- [ ] **Step 4: 快照上限只留一个来源**

`ConnectorSchemaService.java`：

第 1 处，把：

```java
     * 目录<b>列出</b>的对象（最多 500）比这里<b>描述</b>的多。一张表的行数估算跨过一个数量级，就可能从第 200 名
     * 掉到第 201 名——它只是这次没被描述，不是被删了。漂移判定因此按四种处境走
     * （{@link ConnectorSemanticService.Presence}）：只有「确定不在了」才算 REMOVED。
     */
    private static final int MAX_OBJECTS = 200;

    /**
     * 「这张表有没有行」整批探测的挂钟预算（毫秒）。见 {@link #probeRowPresenceQuietly}。
```

替换为：

```java
     * 目录<b>列出</b>的对象（最多 500）比这里<b>描述</b>的多。一张表的行数估算跨过一个数量级，就可能从第 200 名
     * 掉到第 201 名——它只是这次没被描述，不是被删了。漂移判定因此按四种处境走
     * （{@link ConnectorSemanticService.Presence}）：只有「确定不在了」才算 REMOVED。
     *
     * <p>{@code public}：语义层（{@code ConnectorSemanticDeriveService.SCHEMA_SNAPSHOT_CAP}）和数据星图都要说出「快照截断了」，
     * 这个数只在这里写一次。
     */
    public static final int MAX_OBJECTS = 200;

    /**
     * 「这张表有没有行」整批探测的挂钟预算（毫秒）。见 {@link #probeRowPresenceQuietly}。
```

`ConnectorSemanticDeriveService.java`：

第 1 处，把：

```java
    public static final String SEM_NOT_APPLICABLE = "NOT_APPLICABLE";

    /**
     * {@code ConnectorSchemaService.MAX_OBJECTS} 的镜像（那边是 private）。
     *
     * <p>为什么要在这里再写一遍这个数：快照本身就是<b>按对象名字母序截到前 200 个</b>的，字母序靠后的表
     * 根本没有进过 connector_schema，于是也不会有语义、漂移检测对它们永远沉默。这件事在语义层这一侧
     * 必须说出来——「沉默」会被管理台读成「都覆盖了」。两边的值要一起改。
     */
    static final int SCHEMA_SNAPSHOT_CAP = 200;

    // GLOSS_MAX / NAME_MAX / DETAIL_TEXT_MAX / DETAIL_LIST_MAX / CARDINALITIES 连同 toRows、parseFields、renderObject 与那批小工具
    // 已原样迁到 SemanticRowAssembler（静态导入），本类只委托：语义层生成 agent 的逐表提交要走同一套过滤，两份实现迟早分叉。
```

替换为：

```java
    public static final String SEM_NOT_APPLICABLE = "NOT_APPLICABLE";

    /**
     * 就是 {@link ConnectorSchemaService#MAX_OBJECTS}（引用它，不再各写一份）。
     *
     * <p>为什么语义层这一侧要用到这个数：快照本身就是<b>按重要性截到前 200 个</b>的，排在后面的表
     * 根本没有进过 connector_schema，于是也不会有语义、漂移检测对它们永远沉默。这件事在语义层这一侧
     * 必须说出来——「沉默」会被管理台读成「都覆盖了」。
     */
    static final int SCHEMA_SNAPSHOT_CAP = ConnectorSchemaService.MAX_OBJECTS;

    // GLOSS_MAX / NAME_MAX / DETAIL_TEXT_MAX / DETAIL_LIST_MAX / CARDINALITIES 连同 toRows、parseFields、renderObject 与那批小工具
    // 已原样迁到 SemanticRowAssembler（静态导入），本类只委托：语义层生成 agent 的逐表提交要走同一套过滤，两份实现迟早分叉。
```

- [ ] **Step 5: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='DataGraph*Test*,ConnectorSemanticDeriveServiceTest*,ConnectorSemanticSelfHealingTest*' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep 'P95'; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 每个文件 `failures=0`；DataGraphProjectorTest 34、DataGraphServiceTest 9、DataGraphPerformanceTest 1 个用例；打印的 P95 远小于 300 ms（验证时 10 ms）。

- [ ] **Step 6: 跑 data-server 全量测试**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && mvn -o -q -pl modules/data-server test > /tmp/v3-task10-full.log 2>&1; \
  cd modules/data-server/target/surefire-reports && echo "cases=$(cat TEST-*.xml | grep -c '<testcase') failures=$(cat TEST-*.xml | grep -c '<failure\|<error')"
```
Expected: `failures=0`（验证时是 2285 个用例）。

- [ ] **Step 7: 提交**

```bash
cd $DS && git add -A modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector && \
git commit -q -F - <<'MSG'
feat(graph): 星图投影合并业务视图——业务名、说明、领域、角色名；截断与整理状态

标题：业务名 → 像名称且不夹代码的表注释 → 表名，nameSource 说来自哪一档；
角色名：业务视图 → 干净的起点列注释。同表同列丢弃（审查 #3），自关联带来源（#4），
truncated 与 viewStatus（#2），快照上限只留 ConnectorSchemaService.MAX_OBJECTS 一处；
性能测试改量服务层 + 序列化（#8）。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---
### Task 11: 端到端夹具检查改为 v3（jm-agent-front，先写检查）

前端没有单测框架，`e2e/data-graph-check.mjs` 就是前端的测试：自己起一个临时 Vite，在浏览器里拦截全部 `/data/` 请求返回夹具，不碰真实数据库、网关和模型。先把它整份改成 v3 的样子，后面 Task 12–15 每做完一块，就有一批检查由红变绿。

它覆盖 spec §8（页面、画布、右侧面板、状态文案）、§9（成员按模块进入）、附录 E（句子模板逐字比对），以及 v2 审查 #1 #6 #7 #9 #10 #11。带 ★ 的是最容易回退的几条：默认视图扫描不到表名和字段名（读屏标签、悬停提示一起扫）、切换对象后字段搜索词清空、从详情返回后停在上次的页签、切换系统后没有残留的选中、没有模块的成员直接敲地址看到「无权访问」。

**Files:**
- Modify: `e2e/data-graph-check.mjs`（整份重写）

**Interfaces:**
- Consumes：后端 Task 10 的接口形状（夹具按它写）：系统列表多了 `truncated`、`viewStatus`；对象卡片多了 `nameSource`、`summary`、`domain`；关系的 `label` 改名为 `role`；自关联带 `tier`、`confirmedBy`、`role`。后端 Task 1 的模块码 `DATA_GRAPH_MODULE`（夹具里 `/admin/me/permissions` 按它给成员授权）。
- Produces：页面上要有的 `data-testid` 与文案。Task 13–15 的代码都已经带着，照抄即可，不用对着这里补。

- [ ] **Step 1: 整份覆盖检查脚本**

`e2e/data-graph-check.mjs`：

```js
// 数据星图端到端检查（data-service 设计文档 §8、§9、附录 E）：自起临时 Vite，浏览器里拦截全部 /data/ 请求返回夹具，
// 不访问真实数据库、网关或模型。运行：npm run test:data-graph（需先 cd e2e && npm run setup）。
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser, reporter, shot } from './lib.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(here, '..');

const apiResponse = (data) => ({ success: true, respCode: '200', respMsg: 'ok', data });
const col = (name, comment = null) => ({ name, comment });

// ---------------------------------------------------------------- 夹具

const SYSTEMS = [
  { connectorId: '7', name: 'erp', displayName: 'ERP 系统', kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '9', truncated: false, viewStatus: 'READY' },
  { connectorId: '8', name: 'crm', displayName: null, kind: 'MYSQL', status: 'DISABLED', semanticStatus: null, tableCount: '2', truncated: false, viewStatus: null },
  { connectorId: '9', name: 'big', displayName: '大系统', kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '200', truncated: true, viewStatus: 'READY' },
  { connectorId: '10', name: 'running', displayName: null, kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'RUNNING', tableCount: '1', truncated: false, viewStatus: null },
  { connectorId: '11', name: 'failed', displayName: null, kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'FAILED', tableCount: '1', truncated: false, viewStatus: null },
  { connectorId: '12', name: 'ready-empty', displayName: null, kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '1', truncated: false, viewStatus: 'READY' },
  { connectorId: '13', name: 'legacy', displayName: '旧系统', kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '3', truncated: false, viewStatus: 'RUNNING' },
  { connectorId: '14', name: 'legacy-failed', displayName: '旧系统二', kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '3', truncated: false, viewStatus: 'FAILED' },
];

const card = (name, view, related, keyColumns, relationColumns, extra = {}) => ({
  name,
  displayName: view.displayName ?? null,
  nameSource: view.nameSource ?? 'BUSINESS_VIEW',
  summary: view.summary ?? null,
  domain: view.domain ?? null,
  comment: view.comment ?? null,
  objectType: 'TABLE',
  related,
  selfReferences: [],
  keyColumns,
  relationColumns,
  fieldCount: String(keyColumns.length + relationColumns.length + 3),
  ...extra,
});

const rel = (id, fromTable, fromColumn, toTable, toColumn, cardinality, tier, confirmedBy, role, discriminatorColumn = null) => ({
  id, fromTable, fromColumn, toTable, toColumn, cardinality, tier, confirmedBy, role, discriminatorColumn,
});

// ERP：9 个对象（7 个上画布、2 个暂未发现关联），10 条关系合成 8 根线。
const erpGraph = {
  connectorId: '7',
  name: 'erp',
  displayName: 'ERP 系统',
  semanticStatus: 'READY',
  truncated: false,
  viewStatus: 'READY',
  tables: [
    card('t_vendor', { displayName: '供应商', summary: '一条记录是一个供应商，记着名称、联系方式和结算方式。', domain: '主数据', comment: '供应商表' }, true, [col('id')], [col('customer_id', '对应客户'), col('id')]),
    card('t_customer', { displayName: '客户', summary: '一条记录是一个客户。', domain: '主数据' }, true, [col('id')], [col('id'), col('vendor_id', '对应供应商')]),
    card('t_account', { displayName: '会计科目', summary: '一条记录是一个会计科目，科目之间有上下级。', domain: '财务' }, true, [col('id')], [col('id')], {
      selfReferences: [{ fromColumn: 'parent_id', toColumn: 'id', tier: 'INFERRED', confirmedBy: null, role: '上级科目' }],
    }),
    card('t_po_head', { displayName: '采购订单', summary: '一条记录是一张采购订单，记着供应商和下单日期。', domain: '采购' }, true, [col('id')], [col('id'), col('vendor_id')]),
    card('t_po_item', { displayName: '采购订单明细', summary: '一条记录是采购订单里的一行商品，记着数量和金额。', domain: '采购' }, true, [col('id')], [col('head_id', '所属订单'), col('vendor_id')]),
    card('t_voucher_line', { displayName: '凭证行', summary: '一条记录是一张凭证里的一行分录，记着科目和金额。', domain: '财务' }, true, [col('id')], [col('account_id'), col('gl_account_id'), col('recon_account_id'), col('vendor_id')]),
    card('t_ext', { displayName: '采购订单附加信息', summary: '一条记录是一张采购订单的附加信息。', domain: '采购' }, true, [col('id')], [col('id')]),
    card('t_region', { displayName: '地区', summary: '一条记录是一个地区。', domain: null }, false, [col('id')], [], {
      selfReferences: [{ fromColumn: 'parent_id', toColumn: 'id', tier: 'INFERRED', confirmedBy: null, role: '上级地区' }],
    }),
    card('t_log', { displayName: '操作日志', summary: '一条记录是一次系统操作。', domain: null }, false, [], []),
  ],
  relations: [
    rel('e000000000000001', 't_customer', 'vendor_id', 't_vendor', 'id', 'MANY_TO_ONE', 'INFERRED', null, '对应供应商'),
    rel('e000000000000002', 't_ext', 'id', 't_po_head', 'id', 'ONE_TO_ONE', 'INFERRED', null, null),
    rel('e000000000000003', 't_po_head', 'vendor_id', 't_vendor', 'id', 'MANY_TO_ONE', 'CONFIRMED', 'DATA', '供应商'),
    rel('e000000000000004', 't_po_item', 'head_id', 't_po_head', 'id', 'MANY_TO_ONE', 'CONFIRMED', 'BUSINESS', '所属订单'),
    rel('e000000000000005', 't_po_item', 'vendor_id', 't_vendor', 'id', 'MANY_TO_ONE', 'INFERRED', null, '供货方'),
    rel('e000000000000006', 't_vendor', 'customer_id', 't_customer', 'id', 'MANY_TO_ONE', 'INFERRED', null, '对应客户'),
    rel('e000000000000007', 't_voucher_line', 'account_id', 't_account', 'id', 'MANY_TO_ONE', 'INFERRED', null, '会计科目'),
    rel('e000000000000008', 't_voucher_line', 'gl_account_id', 't_account', 'id', 'MANY_TO_ONE', 'INFERRED', null, '总账科目'),
    rel('e000000000000009', 't_voucher_line', 'recon_account_id', 't_account', 'id', 'MANY_TO_ONE', 'CONFIRMED', 'DATA', '统驭科目'),
    rel('e00000000000000a', 't_voucher_line', 'vendor_id', 't_vendor', 'id', null, 'CONFIRMED', 'BUSINESS', null, 'partner_type'),
  ],
};

// 旧系统：业务名称还没整理完，标题按表注释、表名兜底。
const legacyGraph = (connectorId, viewStatus) => ({
  connectorId,
  name: connectorId === '13' ? 'legacy' : 'legacy-failed',
  displayName: connectorId === '13' ? '旧系统' : '旧系统二',
  semanticStatus: 'READY',
  truncated: false,
  viewStatus,
  tables: [
    card('l_order', { displayName: '订单', nameSource: 'COMMENT', comment: '订单' }, true, [col('id')], [col('id'), col('user_id')]),
    card('l_item', { displayName: null, nameSource: 'PHYSICAL', comment: '订单里的一行，按 order_id 关联到订单' }, true, [col('id')], [col('order_id')]),
    card('l_user', { displayName: '用户', summary: '一条记录是一个用户。', domain: '主数据' }, true, [col('id')], [col('id')]),
  ],
  relations: [
    rel('l000000000000001', 'l_item', 'order_id', 'l_order', 'id', 'MANY_TO_ONE', 'INFERRED', null, null),
    rel('l000000000000002', 'l_order', 'user_id', 'l_user', 'id', 'MANY_TO_ONE', 'INFERRED', null, '下单人'),
  ],
});

const lonely = (connectorId, name, semanticStatus) => ({
  connectorId,
  name,
  displayName: null,
  semanticStatus,
  truncated: false,
  viewStatus: null,
  tables: [card(`${name}_t1`, { displayName: null, nameSource: 'PHYSICAL' }, false, [col('id')], [])],
  relations: [],
});

// 200 个对象、200 条关系：每个对象挂到 (i-1)/4 号对象上形成扇入树，再补一条跨枝的线。
const DOMAINS = ['采购', '销售', '财务', '库存', '主数据', '生产', '人事', '项目'];
const bigTables = Array.from({ length: 200 }, (_, i) => {
  const name = `table_${String(i).padStart(3, '0')}`;
  const outgoing = i > 0 ? [col('parent_id', '上级')] : [];
  return card(name, { displayName: `对象${i}`, summary: `一条记录是对象${i}。`, domain: DOMAINS[i % 8] }, true, [col('id')], [...outgoing, col('id')]);
});
const bigRelations = bigTables.slice(1).map((table, index) =>
  rel(`b${String(index).padStart(15, '0')}`, table.name, 'parent_id', bigTables[Math.floor(index / 4)].name, 'id', 'MANY_TO_ONE',
    index % 3 === 0 ? 'CONFIRMED' : 'INFERRED', index % 3 === 0 ? 'DATA' : null, '上级对象'),
);
bigRelations.push({ ...bigRelations[0], id: 'bextra0000000000', fromTable: 'table_150', toTable: 'table_010' });
const bigGraph = { connectorId: '9', name: 'big', displayName: '大系统', semanticStatus: 'READY', truncated: true, viewStatus: 'READY', tables: bigTables, relations: bigRelations };

const GRAPHS = {
  7: erpGraph,
  8: { ...lonely('8', 'crm', null), tables: [card('crm_a', { nameSource: 'PHYSICAL' }, false, [col('id')], []), card('crm_b', { nameSource: 'PHYSICAL' }, false, [col('id')], [])] },
  9: bigGraph,
  10: lonely('10', 'running', 'RUNNING'),
  11: lonely('11', 'failed', 'FAILED'),
  12: lonely('12', 'ready-empty', 'READY'),
  13: legacyGraph('13', 'RUNNING'),
  14: legacyGraph('14', 'FAILED'),
};

// 单表详情的字段：键列、关系列，再补一些普通列；凭证行和采购订单明细各 30 列以上，才会出现字段搜索框。
const EXTRA_FIELDS = { t_voucher_line: 36, t_po_item: 32 };
function detailFields(table) {
  const names = [...new Set([...table.keyColumns, ...table.relationColumns, ...table.selfReferences.map((s) => col(s.fromColumn))].map((c) => c.name))];
  const comments = new Map([...table.keyColumns, ...table.relationColumns].map((c) => [c.name, c.comment]));
  const extra = Array.from({ length: EXTRA_FIELDS[table.name] ?? 3 }, (_, i) => `field_${String(i + 1).padStart(2, '0')}`);
  return [...names, 'name', 'amount', ...extra].map((name, index) => ({
    name,
    type: 'bigint',
    nullable: index > 0,
    comment: comments.get(name) ?? null,
    key: name === 'id' ? 'PRIMARY' : null,
    inRelation: table.relationColumns.some((r) => r.name === name),
  }));
}

function tableDetail(graph, name) {
  const table = graph.tables.find((t) => t.name === name);
  if (!table) return null;
  return {
    name: table.name,
    displayName: table.displayName,
    nameSource: table.nameSource,
    summary: table.summary,
    domain: table.domain,
    comment: table.comment,
    objectType: 'TABLE',
    selfReferences: table.selfReferences,
    fields: detailFields(table),
    relations: graph.relations.filter((r) => r.fromTable === name || r.toTable === name),
  };
}

/** 一个系统的物理标识符（小写）：全部表名 + 长度 ≥ 4 的列名，与后端 BusinessTextRules 同一口径。 */
function identifiersOf(graph) {
  const out = new Set(graph.tables.map((t) => t.name.toLowerCase()));
  for (const table of graph.tables) {
    for (const field of detailFields(table)) if (field.name.length >= 4) out.add(field.name.toLowerCase());
  }
  for (const r of graph.relations) {
    for (const c of [r.fromColumn, r.toColumn, r.discriminatorColumn]) if (c && c.length >= 4) out.add(c.toLowerCase());
  }
  return out;
}

/** 按完整单词、不分大小写找物理标识符。 */
const codesIn = (text, identifiers) =>
  [...text.matchAll(/[A-Za-z0-9_$]+/g)].map((m) => m[0]).filter((token) => identifiers.has(token.toLowerCase()));

// ---------------------------------------------------------------- 临时 Vite

async function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      server.close(() => resolvePort(port));
    });
  });
}

async function waitForServer(url) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // Vite 还在启动。
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 150));
  }
  throw new Error(`Vite 启动超时: ${url}`);
}

async function startVite() {
  if (process.env.E2E_BASE_URL) return { baseUrl: process.env.E2E_BASE_URL, child: null };
  const port = await freePort();
  const child = spawn('npm', ['run', 'dev', '--', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
    cwd: projectRoot,
    env: { ...process.env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await waitForServer(baseUrl);
    return { baseUrl, child };
  } catch (error) {
    child.kill('SIGTERM');
    throw error;
  }
}

// ---------------------------------------------------------------- 浏览器侧

const FORBIDDEN = ['包含', 'READY', 'RUNNING', 'FAILED', 'INSPECTOR', 'UNKNOWN', '%', 'AI', '置信度', '语义层'];
const SUPER = { superAdmin: true, userType: 'SUPER_ADMIN', modules: [], agentIds: [], knowledgeBaseIds: [] };
const MEMBER_WITH_MODULE = { superAdmin: false, userType: 'MEMBER', modules: ['DATA_GRAPH_MODULE'], agentIds: [], knowledgeBaseIds: [] };
const MEMBER_WITHOUT_MODULE = { superAdmin: false, userType: 'MEMBER', modules: ['AGENT_MODULE'], agentIds: [], knowledgeBaseIds: [] };

async function nodePositions(page) {
  return page.$$eval('.react-flow__node', (nodes) =>
    Object.fromEntries(nodes.map((node) => [node.getAttribute('data-id'), node.style.transform])),
  );
}

// 等画布画完：卡片数、连线数到位，视口（自动缩放 / 居中）连续两次读数不变。等不到不抛，返回 false，由调用方记一条失败。
async function waitForGraph(page, cards, edges) {
  const drawn = await page
    .waitForFunction(
      ([c, e]) =>
        document.querySelectorAll('[data-testid="dg-card"]').length === c &&
        document.querySelectorAll('.react-flow__edge').length === e,
      [cards, edges],
      { timeout: 15_000 },
    )
    .then(() => true)
    .catch(() => false);
  if (!drawn) return false;
  let last = '';
  for (let i = 0; i < 30; i += 1) {
    const now = await page.$eval('.react-flow__viewport', (el) => el.style.transform).catch(() => '');
    if (now && now === last) return true;
    last = now;
    await page.waitForTimeout(100);
  }
  return true;
}

/** 打开数据星图页；页面没出来（比如被权限拦下）不抛，返回 false。 */
async function openSystem(page, baseUrl, system) {
  await page.goto(`${baseUrl}/console/data-graph${system ? `?system=${system}` : ''}`, { waitUntil: 'domcontentloaded' });
  return page
    .locator('[data-testid="data-graph-page"]')
    .waitFor({ state: 'visible', timeout: 15_000 })
    .then(() => true)
    .catch(() => false);
}

/** 某张卡片是否整张落在画布可见区域里。 */
const insideCanvas = (page, name) =>
  page.evaluate((table) => {
    const canvas = document.querySelector('[data-testid="data-graph-canvas"]')?.getBoundingClientRect();
    const box = document.querySelector(`[data-testid="dg-card"][data-table="${table}"]`)?.getBoundingClientRect();
    if (!canvas || !box) return false;
    return box.left >= canvas.left && box.right <= canvas.right && box.top >= canvas.top && box.bottom <= canvas.bottom;
  }, name);

const { baseUrl, child } = await startVite();
const r = reporter('data-graph');
const { browser, page } = await launchBrowser();
const pageErrors = [];
const unknownApis = new Set();
page.on('pageerror', (error) => pageErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() === 'error') pageErrors.push(message.text());
});

// 路由夹具的可变状态：换身份、换系统列表、让某个接口失败，都只改这里。
const state = { perm: SUPER, systems: SYSTEMS, failSystems: false, failGraph: null };

try {
  const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(
    JSON.stringify({ id: '1', tenant_id: 'tenant-fixture', exp: Math.floor(Date.now() / 1000) + 86_400 }),
  ).toString('base64url');
  const token = `${header}.${payload}.fixture`;
  const user = { id: '1', username: 'graph-admin', displayName: '星图管理员', tenantId: 'tenant-fixture', status: 'ACTIVE', userType: 'SUPER_ADMIN' };

  await page.route(
    (url) => url.pathname.startsWith('/data/'),
    (route) => {
      const path = new URL(route.request().url()).pathname;
      const json = (data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(apiResponse(data)) });
      const fail = () => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false, respCode: '500', respMsg: '服务暂时不可用', data: null }) });
      if (path.endsWith('/admin/auth/login')) return json({ token, user });
      if (path.endsWith('/admin/auth/me')) return json(user);
      if (path.endsWith('/admin/me/permissions')) return json(state.perm);
      if (path === '/data/admin/data-graph/systems') return state.failSystems ? fail() : json(state.systems);
      const tableMatch = path.match(/^\/data\/admin\/data-graph\/systems\/([^/]+)\/tables$/);
      if (tableMatch) {
        const name = new URL(route.request().url()).searchParams.get('name');
        return json(tableDetail(GRAPHS[tableMatch[1]], name));
      }
      const systemMatch = path.match(/^\/data\/admin\/data-graph\/systems\/([^/]+)$/);
      if (systemMatch) return state.failGraph === systemMatch[1] ? fail() : json(GRAPHS[systemMatch[1]]);
      unknownApis.add(path);
      return json(null);
    },
  );

  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', 'graph-admin');
  await page.fill('#password', 'fixture-password');
  await page.locator('.login-submit').click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15_000 });

  // ======================================================== 1. 默认打开第一个系统（ERP）
  const errorsAtStart = pageErrors.length;
  await openSystem(page, baseUrl, null);
  r.ok('画布画完：7 张卡片、8 根线', await waitForGraph(page, 7, 8));
  const erpIds = identifiersOf(erpGraph);
  r.ok('加载后控制台无报错', pageErrors.length === errorsAtStart, pageErrors.slice(errorsAtStart).join(' | '));
  const headerText = await page.locator('.data-graph-header').innerText();
  r.ok(
    '页头：标题与说明',
    headerText.includes('数据星图') &&
      headerText.includes('看看业务系统里有哪些业务对象、它们之间怎样关联。内容由平台根据接入的系统自动整理，并随系统更新自动同步。'),
    headerText,
  );
  const switcher = await page.locator('.data-graph-systems').innerText();
  r.ok('系统切换：「连接显示名 · N 个对象」，停用的带标签', switcher.includes('ERP 系统 · 9 个对象') && switcher.includes('crm · 2 个对象') && switcher.includes('已停用'), switcher.replace(/\s+/g, ' '));
  r.ok('画布只放有关联的对象（7 个）', (await page.locator('[data-testid="dg-card"]').count()) === 7);
  r.ok('同一方向的多条关系合并成一根线：10 条关系画 8 根线', (await page.locator('.react-flow__edge').count()) === 8);
  r.ok(
    'A→B 与 B→A 是两根线（客户、供应商互相引用）',
    (await page.locator('[data-testid="dg-edge-label"][data-edge="t_customer→t_vendor"]').count()) === 1 &&
      (await page.locator('[data-testid="dg-edge-label"][data-edge="t_vendor→t_customer"]').count()) === 1,
  );
  r.ok(
    '任一条已核对就画实线：实线 4 根、虚线 4 根',
    (await page.locator('.react-flow__edge-path.dg-edge.is-confirmed').count()) === 4 &&
      (await page.locator('.react-flow__edge-path.dg-edge.is-inferred').count()) === 4,
  );
  const multi = page.locator('[data-testid="dg-edge-label"][data-edge="t_voucher_line→t_account"]');
  const multiTitle = (await multi.getAttribute('title').catch(() => '')) ?? '';
  r.ok('多条关系的线写「3 种关联」', ((await multi.innerText().catch(() => '')) ?? '').includes('3 种关联'));
  r.ok('悬停列出这根线上的全部角色', ['会计科目', '总账科目', '统驭科目'].every((role) => multiTitle.includes(role)), multiTitle);
  r.ok('单条关系的线写角色名', ((await page.locator('[data-testid="dg-edge-label"][data-edge="t_po_item→t_po_head"]').innerText().catch(() => '')) ?? '') === '所属订单');
  r.ok('线带箭头（指向被引用的对象）', (await page.locator('.react-flow__edge-path[marker-end]').count()) === 8);
  const vendorCard = page.locator('[data-testid="dg-card"][data-table="t_vendor"]');
  r.ok(
    '卡片：业务名、一句说明、领域色条，不列字段',
    (await vendorCard.locator('.dg-card__title').innerText().catch(() => '')) === '供应商' &&
      ((await vendorCard.locator('.dg-card__summary').innerText().catch(() => '')) ?? '').startsWith('一条记录是一个供应商') &&
      (await vendorCard.locator('.dg-card__domain').count()) === 1 &&
      (await page.locator('.dg-card__rows').count()) === 0,
  );
  const sizes = await page.$$eval('[data-testid="dg-card"]', (cards) => [
    ...new Set(cards.map((c) => `${Math.round(c.getBoundingClientRect().width)}x${Math.round(c.getBoundingClientRect().height)}`)),
  ]);
  r.ok('所有卡片尺寸相同', sizes.length === 1, sizes.join(','));
  r.ok('有自关联的对象带「内部关联」徽标', (await page.locator('[data-testid="dg-card"][data-table="t_account"] .dg-badge').innerText().catch(() => '')) === '内部关联');
  const summary = (await page.locator('.data-graph-summary').innerText()).replace(/\s+/g, ' ');
  r.ok('概览：对象 9、已核对的关联 4、待核对的关联 6、暂未发现关联的对象 2', /对象 9/.test(summary) && /已核对的关联 4/.test(summary) && /待核对的关联 6/.test(summary) && /暂未发现关联的对象 2/.test(summary), summary);
  r.ok('业务名称都有了、没截断：不显示提示', (await page.locator('[data-testid="dg-hint-naming"]').count()) === 0 && (await page.locator('[data-testid="dg-hint-truncated"]').count()) === 0);
  const pageText = await page.locator('[data-testid="data-graph-page"]').innerText();
  const codes = codesIn(pageText, erpIds);
  r.ok('★ 默认视图扫描不到任何表名、字段名', codes.length === 0, codes.join(','));
  // 读屏软件念的标签、悬停提示也算默认视图：React Flow 默认给线的读屏标签是「Edge from 表名 to 表名」。
  const spoken = await page.$$eval('[data-testid="data-graph-page"] [aria-label], [data-testid="data-graph-page"] [title]', (els) =>
    els.map((el) => `${el.getAttribute('aria-label') ?? ''}\n${el.getAttribute('title') ?? ''}`).join('\n'),
  );
  const spokenCodes = codesIn(spoken, erpIds);
  r.ok('读屏标签、悬停提示里也没有表名、字段名', spokenCodes.length === 0, [...new Set(spokenCodes)].join(','));
  const lineSpoken = await page.locator('.react-flow__edge[data-id="t_po_item→t_po_head"]').getAttribute('aria-label').catch(() => '');
  r.ok('读屏念一根线：念关系句子', lineSpoken === '每条「采购订单明细」对应一个「采购订单」（作为所属订单）', lineSpoken ?? '');
  const leaked = FORBIDDEN.filter((word) => pageText.includes(word));
  r.ok('界面文案不含运维 / AI 用语', leaked.length === 0, leaked.join(','));
  r.ok('图例写明实线与虚线', pageText.includes('已核对：数据核对通过或业务方确认') && pageText.includes('待核对：按表结构推断，尚未核对'));
  const groups = await page.locator('[data-testid="dg-relation-list"] .dg-group').count();
  const voucherGroup = page.locator('[data-testid="dg-relation-list"] .dg-group', { hasText: '「凭证行」与「会计科目」' });
  r.ok('关联清单按对象两两分组', groups === 7 && (await voucherGroup.locator('li').count()) === 3, `groups=${groups}`);
  r.ok('多对一句子（带角色名）', pageText.includes('每条「采购订单明细」对应一个「采购订单」（作为所属订单）'));
  const voucherLines = ((await voucherGroup.innerText().catch(() => '')) ?? '').split('\n').map((line) => line.trim());
  r.ok('角色名与终点业务名相同时不写「作为」', voucherLines.includes('每条「凭证行」对应一个「会计科目」'), voucherLines.join(' | '));
  r.ok('一对一句子', pageText.includes('「采购订单附加信息」与「采购订单」一一对应'));
  r.ok('已确认的多态关系句末加「只对部分类型成立」', pageText.includes('「凭证行」与「供应商」有关联（只对部分类型成立）'));
  r.ok('来源说明', pageText.includes('业务方确认') && pageText.includes('数据核对通过') && pageText.includes('按表结构推断，尚未核对'));
  const domainButtons = await page.locator('[data-testid="dg-domains"] button').allInnerTexts();
  r.ok('领域筛选：「全部」加各领域，没有领域的归「未分类」排最后', JSON.stringify(domainButtons.map((t) => t.trim())) === JSON.stringify(['全部', '主数据', '财务', '采购', '未分类']), domainButtons.join('|'));
  r.ok(
    '小图整图显示，没有小地图和提示',
    (await page.locator('.react-flow__minimap').count()) === 0 && (await page.locator('[data-testid="dg-explore-hint"]').count()) === 0,
  );
  let allInside = true;
  for (const table of erpGraph.tables.filter((t) => t.related)) allInside = allInside && (await insideCanvas(page, table.name));
  r.ok('小图 7 张卡片都在画布视野里', allInside);
  await page.screenshot({ path: shot('data-graph-v3.png'), fullPage: true });

  // ======================================================== 2. 布局确定、领域筛选不动布局
  const first = await nodePositions(page);
  let stable = true;
  for (let i = 0; i < 2; i += 1) {
    await openSystem(page, baseUrl, '7');
    await waitForGraph(page, 7, 8);
    stable = stable && JSON.stringify(await nodePositions(page)) === JSON.stringify(first);
  }
  r.ok('同一份数据刷新 3 次，卡片坐标完全一致', stable);
  const financeButton = page.locator('[data-testid="dg-domains"] button', { hasText: '财务' });
  if (await financeButton.count()) await financeButton.click();
  const dimmedByDomain = await page.$$eval('[data-testid="dg-card"].is-dimmed', (cards) => cards.map((c) => c.getAttribute('data-table')).sort());
  r.ok(
    '★ 选「财务」：其他领域的对象变暗、坐标不变',
    JSON.stringify(dimmedByDomain) === JSON.stringify(['t_customer', 't_ext', 't_po_head', 't_po_item', 't_vendor']) &&
      JSON.stringify(await nodePositions(page)) === JSON.stringify(first),
    dimmedByDomain.join(','),
  );
  const allButton = page.locator('[data-testid="dg-domains"] button', { hasText: '全部' });
  if (await allButton.count()) await allButton.click();
  r.ok('选回「全部」：没有变暗的对象', (await page.locator('[data-testid="dg-card"].is-dimmed').count()) === 0);

  // ======================================================== 3. 点卡片：不报错、坐标不动、右侧出详情
  const errorsBefore = pageErrors.length;
  let clickStable = true;
  let detailShown = true;
  for (const name of ['t_vendor', 't_customer', 't_po_item', 't_ext', 't_voucher_line']) {
    await page.locator(`[data-testid="dg-card"][data-table="${name}"]`).click();
    detailShown = detailShown && (await page.locator('[data-testid="dg-detail"]').waitFor({ state: 'visible', timeout: 5_000 }).then(() => true).catch(() => false));
    clickStable = clickStable && JSON.stringify(await nodePositions(page)) === JSON.stringify(first);
  }
  r.ok('点卡片后右侧出现对象详情', detailShown);
  r.ok('点卡片不改变任何卡片坐标', clickStable);
  r.ok('点卡片期间控制台无报错', pageErrors.length === errorsBefore, pageErrors.slice(errorsBefore).join(' | '));
  r.ok('选中后非相邻的对象变暗', (await page.locator('.dg-card.is-dimmed').count()) >= 1);

  // 当前选中「凭证行」
  const detail = page.locator('[data-testid="dg-detail"]');
  await detail.locator('.dg-detail__title', { hasText: '凭证行' }).waitFor({ state: 'visible', timeout: 5_000 }).catch(() => null);
  const headings = await detail.locator('.dg-detail__section h4, .dg-tech > summary').allInnerTexts();
  r.ok('详情依次是「它是什么」「和谁有关」「技术信息」', JSON.stringify(headings.map((h) => h.trim())) === JSON.stringify(['它是什么', '和谁有关', '技术信息']), headings.join('|'));
  const detailText = await detail.innerText().catch(() => '');
  r.ok('它是什么：业务名、领域、一句说明', detailText.includes('凭证行') && detailText.includes('财务') && detailText.includes('一条记录是一张凭证里的一行分录'));
  r.ok('和谁有关：按相关对象分组', (await detail.locator('.dg-group').count()) === 2 && detailText.includes('每条「凭证行」对应一个「会计科目」（作为总账科目）'));
  const detailCodes = codesIn(await page.locator('[data-testid="data-graph-page"]').innerText(), erpIds);
  r.ok('★ 选中对象后，默认展开的内容里也没有表名、字段名（技术信息折叠）', detailCodes.length === 0, detailCodes.join(','));
  const techSummary = detail.locator('.dg-tech > summary');
  if (await techSummary.count()) await techSummary.click();
  const techText = await detail.locator('.dg-tech').innerText().catch(() => '');
  r.ok('展开技术信息：看得到表名、字段对应与核对状态', techText.includes('t_voucher_line') && techText.includes('t_voucher_line.recon_account_id → t_account.id · 数据核对通过'), techText.slice(0, 200));
  const fieldSearch = detail.locator('.dg-tech input');
  if (await fieldSearch.count()) await fieldSearch.fill('recon');
  r.ok('字段搜索能筛字段', (await detail.locator('.dg-fields li').count()) === 1);

  // 切换到另一个对象：详情状态整个重置（v2 审查 #1）
  await page.locator('[data-testid="dg-card"][data-table="t_po_item"]').click();
  await detail.locator('.dg-detail__title', { hasText: '采购订单明细' }).waitFor({ state: 'visible', timeout: 5_000 }).catch(() => null);
  const techOpen = await detail.locator('.dg-tech').evaluate((el) => el.open).catch(() => null);
  if (techOpen === false) await detail.locator('.dg-tech > summary').click();
  r.ok('★ 切换对象后字段搜索词清空', (await detail.locator('.dg-tech input').inputValue().catch(() => 'x')) === '');

  // ======================================================== 4. 右侧页签：暂未发现关联的对象；返回时记住页签（#9）
  await page.locator('.dg-detail__back').click();
  await page.getByRole('tab', { name: /暂未发现关联的对象/ }).click().catch(() => null);
  const isolatedText = await page.locator('[data-testid="dg-isolated-list"]').innerText().catch(() => '');
  r.ok('暂未发现关联的对象列出地区与操作日志，地区带「内部关联」', isolatedText.includes('地区') && isolatedText.includes('操作日志') && isolatedText.includes('内部关联'));
  await page.locator('[data-testid="dg-isolated-list"] button', { hasText: '地区' }).click().catch(() => null);
  const selfLine = page.locator('.dg-detail .dg-sentences li', { hasText: '内部有关联' });
  const selfShown = await selfLine.waitFor({ state: 'visible', timeout: 5_000 }).then(() => true).catch(() => false);
  r.ok('自关联句子带角色名', selfShown && (await selfLine.innerText()).includes('「地区」内部有关联（上级地区）'));
  r.ok('没有领域的对象在详情里写「未分类」', ((await detail.innerText().catch(() => '')) ?? '').includes('未分类'));
  await page.locator('.dg-detail__back').click().catch(() => null);
  const activeTab = await page.locator('.dg-side .ant-tabs-tab-active').innerText().catch(() => '');
  r.ok('★ 从详情返回后停在上次的页签', activeTab.includes('暂未发现关联的对象'), activeTab);

  // 搜索：按业务名找，下拉里不出现表名
  await page.locator('.data-graph-search input').fill('明细');
  const option = page.locator('.dg-search-option', { hasText: '采购订单明细' }).first();
  await option.waitFor({ state: 'visible', timeout: 5_000 });
  const optionCodes = codesIn(await option.innerText(), erpIds);
  await option.click();
  const searchDetail = await detail.innerText().catch(() => '');
  r.ok('搜索选中后右侧是该对象详情；下拉里没有表名', searchDetail.includes('采购订单明细') && optionCodes.length === 0, optionCodes.join(','));

  // ======================================================== 5. 切换系统：选中状态同步清掉（#11）；兜底标题与整理提示
  await page.locator('.data-graph-systems').getByText('旧系统 · 3 个对象').click({ timeout: 5_000 }).catch(() => page.goto(`${baseUrl}/console/data-graph?system=13`));
  r.ok('旧系统画完：3 张卡片、2 根线', await waitForGraph(page, 3, 2));
  r.ok('★ 切换系统后没有残留的选中与变暗', (await page.locator('[data-testid="dg-detail"]').count()) === 0 && (await page.locator('.dg-card.is-dimmed').count()) === 0);
  r.ok(
    '兜底标题：像名称的表注释，其次表名',
    (await page.locator('[data-testid="dg-card"][data-table="l_order"] .dg-card__title').innerText()) === '订单' &&
      (await page.locator('[data-testid="dg-card"][data-table="l_item"] .dg-card__title').innerText()) === 'l_item',
  );
  r.ok('有对象还没拿到业务名、补全链在跑：提示「业务名称整理中」', ((await page.locator('[data-testid="dg-hint-naming"]').innerText().catch(() => '')) ?? '').includes('业务名称整理中'));
  await openSystem(page, baseUrl, '14');
  await waitForGraph(page, 3, 2);
  r.ok('补全链上次失败：安静地用兜底，不挂提示', (await page.locator('[data-testid="dg-hint-naming"]').count()) === 0);

  // ======================================================== 6. 空状态（画布区与页签用同一句话，#10）
  const emptyStates = [
    ['8', '这个系统的业务对象还在整理中，完成后会自动出现。'],
    ['10', '正在整理，完成后刷新页面即可看到。'],
    ['11', '整理没有成功，请联系企业管理员。'],
    ['12', '暂未发现可以确认的关联。'],
  ];
  for (const [system, text] of emptyStates) {
    await openSystem(page, baseUrl, system);
    const shown = await page.locator('[data-testid="dg-empty"]').waitFor({ state: 'visible', timeout: 10_000 }).then(() => true).catch(() => false);
    const canvasText = shown ? await page.locator('[data-testid="dg-empty"]').innerText() : '';
    const tabText = await page.locator('[data-testid="dg-relations-empty"]').innerText().catch(() => '');
    r.ok(`空状态（系统 ${system}）：画布区与页签同一句话`, canvasText.includes(text) && tabText.includes(text), `${canvasText} / ${tabText}`);
  }
  await openSystem(page, baseUrl, '8');
  await page.locator('[data-testid="dg-empty"]').waitFor({ state: 'visible', timeout: 10_000 });
  r.ok('企业超管在空状态看得到「去数据连接」', (await page.locator('[data-testid="dg-empty"] a', { hasText: '去「数据连接」' }).count()) === 1);

  // ======================================================== 7. 大图：截断提示、小地图、先显示关联最多的对象、键盘聚焦跟随（#6）
  const started = Date.now();
  await openSystem(page, baseUrl, '9');
  const bigDrawn = await waitForGraph(page, 200, 200);
  const elapsed = Date.now() - started;
  r.ok('200 个对象的大图能渲染', bigDrawn, `首屏 ${elapsed} ms`);
  r.ok('快照截断：提示「只整理了按重要性排前 200 个对象」', ((await page.locator('[data-testid="dg-hint-truncated"]').innerText().catch(() => '')) ?? '').includes('这个系统表很多，只整理了按重要性排前 200 个对象'));
  r.ok('大图不强行缩成一屏：有小地图', await page.locator('.react-flow__minimap').isVisible());
  const chromeTips = await page.$$eval('.react-flow__controls button, .react-flow__minimap svg > title', (els) =>
    els.map((el) => el.getAttribute('title') ?? el.textContent),
  );
  r.ok('画布按钮、小地图的提示是中文', JSON.stringify(chromeTips) === JSON.stringify(['放大', '缩小', '显示全图', '小地图']), chromeTips.join('|'));
  const hint = await page.locator('[data-testid="dg-explore-hint"]').innerText().catch(() => '');
  r.ok('大图提示先显示关联最多的对象附近', hint.includes('「对象10」附近'), hint);
  const hubBox = await page.locator('[data-testid="dg-card"][data-table="table_010"]').boundingBox();
  r.ok('关联最多的对象在视野里、卡片够大能读', Boolean(hubBox) && hubBox.width >= 200 && (await insideCanvas(page, 'table_010')), JSON.stringify(hubBox));
  const far = 'table_199';
  const farBefore = await insideCanvas(page, far);
  const scale = () => page.$eval('.react-flow__viewport', (el) => el.style.transform.replace(/.*scale\(([^)]+)\).*/, '$1'));
  const scaleBefore = await scale();
  await page.locator(`[data-testid="dg-card"][data-table="${far}"]`).focus();
  // 等视口停稳再判断：平移若带动画，中途会先缩小再放大，停在半路读会误判。
  let lastViewport = '';
  for (let i = 0; i < 30; i += 1) {
    const now = await page.$eval('.react-flow__viewport', (el) => el.style.transform);
    if (now === lastViewport) break;
    lastViewport = now;
    await page.waitForTimeout(100);
  }
  r.ok(
    '★ 键盘聚焦到视野外的卡片：画布平移过去，缩放不变',
    !farBefore && (await insideCanvas(page, far)) && (await scale()) === scaleBefore,
    `之前在视野里=${farBefore} 缩放 ${scaleBefore} → ${await scale()}`,
  );
  await page.screenshot({ path: shot('data-graph-v3-big.png'), fullPage: true });

  // ======================================================== 8. 成员：有模块能看、看不到管理链接；没模块看不到入口、直链被拦
  state.perm = MEMBER_WITH_MODULE;
  await openSystem(page, baseUrl, '8');
  const memberSees = await page.locator('[data-testid="dg-empty"]').waitFor({ state: 'visible', timeout: 10_000 }).then(() => true).catch(() => false);
  r.ok('有「数据星图」模块的成员能打开页面', memberSees);
  r.ok('成员在空状态看不到「去数据连接」', memberSees && (await page.locator('[data-testid="dg-empty"] a').count()) === 0);
  r.ok('有模块的成员侧栏有「数据星图」入口', (await page.locator('.atlas-nav-item', { hasText: '数据星图' }).count()) === 1);
  state.perm = MEMBER_WITHOUT_MODULE;
  await page.goto(`${baseUrl}/console/data-graph`, { waitUntil: 'domcontentloaded' });
  const denied = await page.getByText('无权访问').waitFor({ state: 'visible', timeout: 10_000 }).then(() => true).catch(() => false);
  r.ok('★ 没有模块的成员直接敲地址：「无权访问」', denied);
  r.ok('没有模块的成员侧栏没有「数据星图」入口', (await page.locator('.atlas-nav-item', { hasText: '数据星图' }).count()) === 0);

  // ======================================================== 9. 没有任何系统、接口失败
  state.perm = SUPER;
  state.systems = [];
  await openSystem(page, baseUrl, null);
  const noSystems = page.locator('[data-testid="dg-no-systems"]');
  const noSystemsShown = await noSystems.waitFor({ state: 'visible', timeout: 10_000 }).then(() => true).catch(() => false);
  r.ok('没有任何系统：「还没有可以查看的业务系统。」，超管带「去数据连接」', noSystemsShown && (await noSystems.innerText()).includes('还没有可以查看的业务系统。') && (await noSystems.locator('a').count()) === 1);
  state.perm = MEMBER_WITH_MODULE;
  await openSystem(page, baseUrl, null);
  const memberNoSystems = await noSystems.waitFor({ state: 'visible', timeout: 10_000 }).then(() => true).catch(() => false);
  r.ok('没有任何系统：成员不带管理链接', memberNoSystems && (await noSystems.locator('a').count()) === 0);
  state.perm = SUPER;
  state.systems = SYSTEMS;
  state.failSystems = true;
  await openSystem(page, baseUrl, null);
  const systemsError = await page.getByText('业务系统列表没有加载出来').waitFor({ state: 'visible', timeout: 15_000 }).then(() => true).catch(() => false);
  r.ok('接口失败：系统列表加载失败有提示和重试', systemsError && (await page.getByRole('button', { name: /重试/ }).count()) >= 1);
  state.failSystems = false;
  state.failGraph = '7';
  await openSystem(page, baseUrl, '7');
  const graphError = await page.getByText('这个系统的关联没有加载出来').waitFor({ state: 'visible', timeout: 15_000 }).then(() => true).catch(() => false);
  r.ok('接口失败：某个系统的关联加载失败有提示和重试', graphError && (await page.getByRole('button', { name: /重试/ }).count()) >= 1);
  state.failGraph = null;
  r.ok('全程无未预期的接口', unknownApis.size === 0, [...unknownApis].join(','));
} finally {
  await browser.close();
  if (child) child.kill('SIGTERM');
}

const ok = r.summary();
process.exit(ok ? 0 : 1);
```

- [ ] **Step 2: 在 v2 的代码上跑，确认是红的**

```bash
cd $FE && source ~/.nvm/nvm.sh && nvm use 20 >/dev/null && npm run test:data-graph; echo exit=$?
```
Expected：先打出两行 `FAIL  画布画完：7 张卡片、8 根线` 和 `FAIL  加载后控制台无报错  — Error: Not possible to find intersection inside of the rectangle …`（v2 的 dagre 布局拿到 v3 夹具里同一方向的多条关系就抛错，整页崩溃），然后以 `TimeoutError: locator.innerText: Timeout 30000ms exceeded`（等不到 `.data-graph-header`）退出，`exit=1`。这就是预期的红；Task 13 换掉布局之后它才能往下跑。

- [ ] **Step 3: 提交**

```bash
cd $FE && git add e2e/data-graph-check.mjs && git commit -m "test(data-graph): 端到端夹具检查改为 v3——对象地图、业务文字、领域筛选、权限与状态文案

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 数据层改为 v3（类型、句子模板、领域配色、依赖锁定版本）

只改数据和文案模板，不改画面，所以这一步的关卡是 typecheck 与 lint；它产出的类型和模板由 Task 13–14 的画面消费，e2e 在 Task 13 之后才开始转绿。

- 类型跟着后端 Task 10 的接口走（`write_numbers_as_strings` 照旧由 `api.ts` 转数字，不用改）。
- 句子模板逐字照 spec 附录 E 写，e2e 按原文逐字断言：多对一 `每条「A」对应一个「B」（作为角色名）`，角色名与 B 的业务名相同时不写「作为」；一对一 `「A」与「B」一一对应`；多态关系句末加 `（只对部分类型成立）`；自关联 `「A」内部有关联（角色名）`，没有角色名时去掉括号。句子里只出现业务名和角色名。
- 领域配色：固定 8 色，按领域第一次出现的顺序分配，「未分类」固定灰色、排最后（spec §8.5）。
- `@xyflow/react`、`@dagrejs/dagre` 去掉 `^`，锁死 `12.12.0`、`3.1.1`（v2 审查 #12）。装好的版本本来就是这两个，`package-lock.json` 只有根包声明那两行跟着变。

**Files:**
- Modify: `package.json`
- Modify: `package-lock.json`
- Modify: `src/features/data-graph/types.ts`
- Create: `src/features/data-graph/domains.ts`
- Modify: `src/features/data-graph/text.ts`（整份重写）
- Modify: `src/features/data-graph/flow.ts`（一行：`label` → `role`）

**Interfaces:**
- Consumes：后端 Task 10 的接口字段名（见 Task 11 的 Consumes）。
- Produces（Task 13–14 用）：
  - `types.ts`：`ViewStatus = 'RUNNING' | 'READY' | 'FAILED' | null`；`NameSource = 'BUSINESS_VIEW' | 'COMMENT' | 'PHYSICAL'`；`SystemSummaryWire` / `SystemGraphWire` 加 `truncated: boolean`、`viewStatus: ViewStatus`；`TableCardWire` / `TableDetail` 加 `nameSource`、`summary`、`domain`；`SelfReference` 加 `tier`、`confirmedBy`、`role`；`Relation.label` 改名 `Relation.role`。
  - `text.ts`：`UNCLASSIFIED = '未分类'`、`tableTitle(table)`、`domainLabel(domain)`、`relationSentence(relation, titleOf)`、`selfReferenceSentence(title, ref)`、`relationSource(relation)`、`technicalLine(relation)`、`roleLabel(relation, titleOf)`、`edgeLabel(relations): string | null`、`emptyRelationsText(status)`、`NO_SYSTEMS_TEXT`。
  - `domains.ts`：`DOMAIN_PALETTE`、`UNCLASSIFIED_COLOR`、`interface DomainOption`、`domainOptions(tables): DomainOption[]`、`colorOf(options, domain): string`。

- [ ] **Step 1: 锁定依赖版本**

`package.json`：

第 1 处，把：

```json
  },
  "dependencies": {
    "@ant-design/icons": "^5.5.1",
    "@dagrejs/dagre": "^3.1.1",
    "@monaco-editor/react": "^4.6.0",
    "@tanstack/react-query": "^5.59.0",
    "@xyflow/react": "^12.12.0",
    "antd": "^5.21.0",
    "axios": "^1.7.7",
    "dayjs": "^1.11.13",
```

替换为：

```json
  },
  "dependencies": {
    "@ant-design/icons": "^5.5.1",
    "@dagrejs/dagre": "3.1.1",
    "@monaco-editor/react": "^4.6.0",
    "@tanstack/react-query": "^5.59.0",
    "@xyflow/react": "12.12.0",
    "antd": "^5.21.0",
    "axios": "^1.7.7",
    "dayjs": "^1.11.13",
```

`package-lock.json`：

第 1 处，把：

```json
      "version": "0.1.0",
      "dependencies": {
        "@ant-design/icons": "^5.5.1",
        "@dagrejs/dagre": "^3.1.1",
        "@monaco-editor/react": "^4.6.0",
        "@tanstack/react-query": "^5.59.0",
        "@xyflow/react": "^12.12.0",
        "antd": "^5.21.0",
        "axios": "^1.7.7",
        "dayjs": "^1.11.13",
```

替换为：

```json
      "version": "0.1.0",
      "dependencies": {
        "@ant-design/icons": "^5.5.1",
        "@dagrejs/dagre": "3.1.1",
        "@monaco-editor/react": "^4.6.0",
        "@tanstack/react-query": "^5.59.0",
        "@xyflow/react": "12.12.0",
        "antd": "^5.21.0",
        "axios": "^1.7.7",
        "dayjs": "^1.11.13",
```

```bash
cd $FE && npm ls @xyflow/react @dagrejs/dagre
```
Expected：`@xyflow/react@12.12.0` 与 `@dagrejs/dagre@3.1.1`，没有 `invalid` 字样。

- [ ] **Step 2: 类型跟上后端**

`src/features/data-graph/types.ts`：

第 1 处，把：

```ts
// 数据星图接口的形状（data-service 设计文档 §5.1）。
// spring.jackson.write_numbers_as_strings=true：数字到前端都是字符串，api.ts 负责转成 number。

export type SemanticStatus = 'READY' | 'RUNNING' | 'FAILED' | null;
export type RelationTier = 'CONFIRMED' | 'INFERRED';
export type RelationCardinality = 'MANY_TO_ONE' | 'ONE_TO_ONE' | null;
export type ConfirmedBy = 'DATA' | 'BUSINESS' | null;
export type ColumnKey = 'PRIMARY' | 'UNIQUE' | null;

type NumericWire = number | string;
```

替换为：

```ts
// 数据星图接口的形状（data-service 设计文档 §7.2）。
// spring.jackson.write_numbers_as_strings=true：数字到前端都是字符串，api.ts 负责转成 number。

export type SemanticStatus = 'READY' | 'RUNNING' | 'FAILED' | null;
/** 业务文字的整理状态：补全链正在跑 / 上次成功 / 上次失败 / 从没跑过。只用来决定要不要挂提示，不上屏。 */
export type ViewStatus = 'RUNNING' | 'READY' | 'FAILED' | null;
export type RelationTier = 'CONFIRMED' | 'INFERRED';
export type RelationCardinality = 'MANY_TO_ONE' | 'ONE_TO_ONE' | null;
export type ConfirmedBy = 'DATA' | 'BUSINESS' | null;
export type ColumnKey = 'PRIMARY' | 'UNIQUE' | null;
/** 对象标题来自哪一档：业务视图 → 像名称的表注释 → 表名。 */
export type NameSource = 'BUSINESS_VIEW' | 'COMMENT' | 'PHYSICAL';

type NumericWire = number | string;
```

第 2 处，把：

```ts
  status: string | null;
  semanticStatus: SemanticStatus;
  tableCount: NumericWire;
}

export interface SystemSummary extends Omit<SystemSummaryWire, 'tableCount'> {
```

替换为：

```ts
  status: string | null;
  semanticStatus: SemanticStatus;
  tableCount: NumericWire;
  truncated: boolean;
  viewStatus: ViewStatus;
}

export interface SystemSummary extends Omit<SystemSummaryWire, 'tableCount'> {
```

第 3 处，把：

```ts
export interface SelfReference {
  fromColumn: string;
  toColumn: string;
}

export interface TableCardWire {
  name: string;
  displayName: string | null;
  comment: string | null;
  objectType: 'TABLE' | 'VIEW';
  related: boolean;
```

替换为：

```ts
export interface SelfReference {
  fromColumn: string;
  toColumn: string;
  tier: RelationTier;
  confirmedBy: ConfirmedBy;
  role: string | null;
}

export interface TableCardWire {
  name: string;
  displayName: string | null;
  nameSource: NameSource;
  summary: string | null;
  domain: string | null;
  comment: string | null;
  objectType: 'TABLE' | 'VIEW';
  related: boolean;
```

第 4 处，把：

```ts
  cardinality: RelationCardinality;
  tier: RelationTier;
  confirmedBy: ConfirmedBy;
  label: string | null;
  discriminatorColumn: string | null;
}
```

替换为：

```ts
  cardinality: RelationCardinality;
  tier: RelationTier;
  confirmedBy: ConfirmedBy;
  /** 关系角色名（业务视图，缺了退回干净的列注释）；都没有为 null。 */
  role: string | null;
  discriminatorColumn: string | null;
}
```

第 5 处，把：

```ts
  name: string;
  displayName: string | null;
  semanticStatus: SemanticStatus;
  tables: TableCardWire[];
  relations: Relation[];
}
```

替换为：

```ts
  name: string;
  displayName: string | null;
  semanticStatus: SemanticStatus;
  truncated: boolean;
  viewStatus: ViewStatus;
  tables: TableCardWire[];
  relations: Relation[];
}
```

第 6 处，把：

```ts
export interface TableDetail {
  name: string;
  displayName: string | null;
  comment: string | null;
  objectType: 'TABLE' | 'VIEW';
  selfReferences: SelfReference[];
```

替换为：

```ts
export interface TableDetail {
  name: string;
  displayName: string | null;
  nameSource: NameSource;
  summary: string | null;
  domain: string | null;
  comment: string | null;
  objectType: 'TABLE' | 'VIEW';
  selfReferences: SelfReference[];
```

`src/features/data-graph/flow.ts`：

第 1 处，把：

```ts
    focusable: false,
    data: {
      tier: relation.tier,
      label: relation.label,
      fromMark:
        relation.cardinality === 'MANY_TO_ONE'
          ? 'N'
```

替换为：

```ts
    focusable: false,
    data: {
      tier: relation.tier,
      label: relation.role,
      fromMark:
        relation.cardinality === 'MANY_TO_ONE'
          ? 'N'
```

- [ ] **Step 3: 句子模板与领域配色**

`src/features/data-graph/text.ts`（整份覆盖）：

```ts
import type { Relation, SelfReference, SemanticStatus } from './types';

interface Named {
  name: string;
  displayName: string | null;
}

/** 没有领域的对象归到这一类（设计文档 §6.3）。 */
export const UNCLASSIFIED = '未分类';

/** 标题：业务名 → 像名称的表注释（后端已按这个顺序给出 displayName）→ 表名。 */
export const tableTitle = (table: Named): string => table.displayName ?? table.name;

export const domainLabel = (domain: string | null): string => domain ?? UNCLASSIFIED;

/**
 * 关系说成一句话（设计文档附录 E）。句子里只用业务名和角色名，不出现表名、列名。
 * 角色名和终点对象的业务名一样时不重复写「作为」。
 */
export function relationSentence(relation: Relation, titleOf: (table: string) => string): string {
  const from = titleOf(relation.fromTable);
  const to = titleOf(relation.toTable);
  let sentence: string;
  if (relation.cardinality === 'MANY_TO_ONE') {
    sentence =
      relation.role && relation.role !== to
        ? `每条「${from}」对应一个「${to}」（作为${relation.role}）`
        : `每条「${from}」对应一个「${to}」`;
  } else if (relation.cardinality === 'ONE_TO_ONE') {
    sentence = `「${from}」与「${to}」一一对应`;
  } else {
    sentence = `「${from}」与「${to}」有关联`;
  }
  return relation.discriminatorColumn ? `${sentence}（只对部分类型成立）` : sentence;
}

/** 自关联：「{A}」内部有关联（{角色}）；没有角色名时去掉括号。 */
export const selfReferenceSentence = (title: string, ref: SelfReference): string =>
  ref.role ? `「${title}」内部有关联（${ref.role}）` : `「${title}」内部有关联`;

/** 这条关系是怎么来的（沿用 v2 的说法）。 */
export function relationSource(relation: { tier: Relation['tier']; confirmedBy: Relation['confirmedBy'] }): string {
  if (relation.tier === 'INFERRED') return '按表结构推断，尚未核对';
  return relation.confirmedBy === 'BUSINESS' ? '业务方确认' : '数据核对通过';
}

/** 技术信息区的一行：字段对应 + 核对状态。 */
export const technicalLine = (relation: Relation): string =>
  `${relation.fromTable}.${relation.fromColumn} → ${relation.toTable}.${relation.toColumn} · ${relationSource(relation)}`;

/** 一条关系在线上、悬停提示里的叫法：角色名；没有角色名时说它对应哪个对象。 */
export const roleLabel = (relation: Relation, titleOf: (table: string) => string): string =>
  relation.role ?? `对应「${titleOf(relation.toTable)}」`;

/** 一根线上写的字：只有一条关系时写它的角色名（没有就不写）；多条时写「N 种关联」。 */
export const edgeLabel = (relations: Relation[]): string | null =>
  relations.length > 1 ? `${relations.length} 种关联` : (relations[0]?.role ?? null);

/**
 * 还没有关联时画布区和「关联清单」页签共用的一句话（设计文档 §8.4，v2 审查 #10）。
 * 语义层的状态只用来挑这句话，原文不上屏。
 */
export function emptyRelationsText(status: SemanticStatus): string {
  if (status === null) return '这个系统的业务对象还在整理中，完成后会自动出现。';
  if (status === 'RUNNING') return '正在整理，完成后刷新页面即可看到。';
  if (status === 'FAILED') return '整理没有成功，请联系企业管理员。';
  return '暂未发现可以确认的关联。';
}

export const NO_SYSTEMS_TEXT = '还没有可以查看的业务系统。';
```

`src/features/data-graph/domains.ts`：

```ts
import { UNCLASSIFIED, domainLabel } from './text';
import type { TableCard } from './types';

/**
 * 领域颜色：固定 8 色，按领域第一次出现的顺序分配（设计文档 §8.5）。都是深色画布上的色条和圆点，
 * 与卡片底色 #0a1b2e / #0d2438 的对比度都在 3:1 以上（WCAG 非文字对比）。「未分类」固定用灰色。
 */
export const DOMAIN_PALETTE = [
  '#38bdf8',
  '#a78bfa',
  '#34d399',
  '#fbbf24',
  '#f472b6',
  '#fb923c',
  '#2dd4bf',
  '#a3e635',
] as const;

export const UNCLASSIFIED_COLOR = '#7c8ea3';

export interface DomainOption {
  /** 显示的领域名；没有领域的是「未分类」。 */
  label: string;
  color: string;
}

/**
 * 这个系统的领域清单：按对象顺序（后端已按重要性排好）第一次出现的顺序排，「未分类」放最后。
 * 超过 8 个领域时颜色循环使用（后端最多给 8 个，这里只是兜底）。
 */
export function domainOptions(tables: TableCard[]): DomainOption[] {
  const seen: string[] = [];
  let unclassified = false;
  for (const table of tables) {
    if (table.domain === null) {
      unclassified = true;
    } else if (!seen.includes(table.domain)) {
      seen.push(table.domain);
    }
  }
  const options = seen.map((label, index) => ({
    label,
    color: DOMAIN_PALETTE[index % DOMAIN_PALETTE.length],
  }));
  return unclassified ? [...options, { label: UNCLASSIFIED, color: UNCLASSIFIED_COLOR }] : options;
}

export const colorOf = (options: DomainOption[], domain: string | null): string =>
  options.find((option) => option.label === domainLabel(domain))?.color ?? UNCLASSIFIED_COLOR;
```

- [ ] **Step 4: typecheck 与 lint**

```bash
cd $FE && source ~/.nvm/nvm.sh && nvm use 20 >/dev/null && npm run typecheck && npm run lint; echo exit=$?
```
Expected：`exit=0`，eslint 没有任何输出。`npm run test:data-graph` 此时仍和 Task 11 Step 2 一样中途退出（画布还是 v2 的），不用跑。

- [ ] **Step 5: 提交**

```bash
cd $FE && git add package.json package-lock.json src/features/data-graph/types.ts src/features/data-graph/domains.ts \
  src/features/data-graph/text.ts src/features/data-graph/flow.ts && \
  git commit -m "feat(data-graph): 数据层改为 v3——业务名、说明、领域、角色名、整理状态；句子模板与领域配色；依赖锁定精确版本

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 画布改为对象地图（卡片、合线、聚焦跟随、读屏标签）

spec §8.2。卡片只写业务名、一句说明（最多两行）和领域色条，不列字段、不出表名，尺寸全部相同；同一方向的多条关系合成一根线，线上写角色名或「N 种关联」，悬停列出全部角色，任一条已核对就画实线。

三个实现上的坑，代码里都有注释：
- **dagre 3.1.1 遇到同一方向的平行边会抛错、整页崩溃**（Task 11 Step 2 看到的就是它）：每个方向只喂一条边给布局，另加网格兜底。
- **键盘聚焦跟随（v2 审查 #6）**：卡片拿到焦点、且不在视野里时，`setCenter(x, y, { zoom: getZoom(), duration: 0 })` 直接跳过去、缩放不变。用动画的话，e2e 判断「在视野里」时视口还在半路上。
- **读屏标签**：React Flow 默认给每根线的读屏标签是「Edge from 表名 to 表名」，默认视图不出现表名这条验收对读屏用户就不成立。改为念这根线上的关系句子（`relationSentence`）；缩放按钮、小地图的悬停提示用 `ariaLabelConfig` 改成中文。

**Files:**
- Modify: `src/features/data-graph/flow.ts`（整份重写）
- Modify: `src/features/data-graph/layout.ts`（整份重写）
- Create: `src/features/data-graph/components/ObjectCardNode.tsx`
- Modify: `src/features/data-graph/components/RelationEdge.tsx`（整份重写）
- Delete: `src/features/data-graph/components/TableCardNode.tsx`
- Modify: `src/features/data-graph/components/DataGraphCanvas.tsx`（整份重写）
- Modify: `src/pages/console/data-graph/DataGraphPage.tsx`
- Modify: `src/pages/console/data-graph/data-graph.css`

**Interfaces:**
- Consumes：Task 12 的 `types.ts`、`text.ts`（`tableTitle`、`domainLabel`、`edgeLabel`、`relationSentence`、`roleLabel`）、`domains.ts`（`DomainOption`、`colorOf`）。
- Produces（Task 14 用）：`DataGraphCanvas` 的 props 多了 `domains: DomainOption[]`、`domainFilter: string | null`（选中某个领域时其他领域的对象变暗，布局不动）、`titleOf: (table: string) => string`。本任务里页面先传 `domainFilter={null}`，Task 14 接上领域筛选。

- [ ] **Step 1: 布局与合线**

`src/features/data-graph/flow.ts`（整份覆盖）：

```ts
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import { colorOf, type DomainOption } from './domains';
import type { CardBox, EdgeGroup } from './layout';
import { domainLabel, edgeLabel, relationSentence, roleLabel, tableTitle } from './text';
import type { TableCard } from './types';

export interface ObjectNodeData extends Record<string, unknown> {
  name: string;
  title: string;
  summary: string | null;
  domain: string;
  domainColor: string;
  hasSelfReference: boolean;
  selected: boolean;
  dimmed: boolean;
  onSelect: (name: string) => void;
  onFocusCard: (name: string, visible: boolean) => void;
}

export type ObjectFlowNode = Node<ObjectNodeData, 'object'>;

export interface RelationEdgeData extends Record<string, unknown> {
  confirmed: boolean;
  label: string | null;
  /** 悬停时列出的全部角色，按关系顺序。 */
  roles: string[];
  dimmed: boolean;
}

export type RelationFlowEdge = Edge<RelationEdgeData, 'relation'>;

/** 搜索或点击清单时请求画布把某个对象挪到视野中央；seq 让「同一个对象再点一次」也能生效。 */
export interface FocusRequest {
  name: string;
  seq: number;
}

/**
 * 把接口数据和布局结果变成 React Flow 的节点与边。坐标只来自 boxes：选中、领域筛选只改样式，布局不动。
 *
 * <p>变暗有两个来源：选中了一个对象时，和它不相邻的对象、不连着它的线变暗；筛了一个领域时，别的领域的对象变暗，
 * 线只要有一端在这个领域里就不变暗。选中的对象不在画布上（暂未发现关联的对象）时不因选中而变暗。
 */
export function buildFlow(
  tables: TableCard[],
  groups: EdgeGroup[],
  boxes: Map<string, CardBox>,
  domains: DomainOption[],
  selected: string | null,
  domainFilter: string | null,
  titleOf: (table: string) => string,
  onSelect: (name: string) => void,
  onFocusCard: (name: string, visible: boolean) => void,
): { nodes: ObjectFlowNode[]; edges: RelationFlowEdge[] } {
  const active = selected && boxes.has(selected) ? selected : null;
  const neighbours = new Set<string>();
  if (active) {
    neighbours.add(active);
    groups.forEach((group) => {
      if (group.fromTable === active) neighbours.add(group.toTable);
      if (group.toTable === active) neighbours.add(group.fromTable);
    });
  }
  const outOfDomain = new Set<string>();
  const dimmed = new Set<string>();
  tables.forEach((table) => {
    if (domainFilter !== null && domainLabel(table.domain) !== domainFilter) outOfDomain.add(table.name);
    if ((active !== null && !neighbours.has(table.name)) || outOfDomain.has(table.name)) dimmed.add(table.name);
  });

  const nodes: ObjectFlowNode[] = [];
  tables.forEach((table) => {
    const box = boxes.get(table.name);
    if (!box) return;
    nodes.push({
      id: table.name,
      type: 'object',
      position: { x: box.x, y: box.y },
      width: box.width,
      height: box.height,
      draggable: false,
      selectable: false,
      data: {
        name: table.name,
        title: tableTitle(table),
        summary: table.summary,
        domain: domainLabel(table.domain),
        domainColor: colorOf(domains, table.domain),
        hasSelfReference: table.selfReferences.length > 0,
        selected: table.name === active,
        dimmed: dimmed.has(table.name),
        onSelect,
        onFocusCard,
      },
    });
  });
  const edges: RelationFlowEdge[] = groups.map((group) => ({
    id: group.id,
    type: 'relation',
    source: group.fromTable,
    target: group.toTable,
    selectable: false,
    focusable: false,
    // 读屏软件念的是关系句子；不给的话 React Flow 默认念「Edge from 表名 to 表名」。
    ariaLabel: group.relations.map((relation) => relationSentence(relation, titleOf)).join('；'),
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16, color: '#38bdf8' },
    data: {
      confirmed: group.relations.some((relation) => relation.tier === 'CONFIRMED'),
      label: edgeLabel(group.relations),
      roles: group.relations.map((relation) => roleLabel(relation, titleOf)),
      dimmed:
        (active !== null && group.fromTable !== active && group.toTable !== active) ||
        (outOfDomain.has(group.fromTable) && outOfDomain.has(group.toTable)),
    },
  }));
  return { nodes, edges };
}
```

`src/features/data-graph/layout.ts`（整份覆盖）：

```ts
import { Graph, layout } from '@dagrejs/dagre';
import type { Relation, TableCard } from './types';

// 与 data-graph.css 里 .dg-card 的宽高一致：布局按这个算，DOM 也按这个画。所有卡片一样大（设计文档 §8.2）。
export const CARD_WIDTH = 240;
export const CARD_HEIGHT = 96;

// 「整图一屏看得清」的判据：按一块 800×620 的名义画布算，整图缩放到能放下时比例不低于 0.55
// （卡片标题约 8px 以上）。只看数据不看窗口大小，所以同一份数据永远是同一种显示方式。
const NOMINAL_CANVAS_WIDTH = 800;
const NOMINAL_CANVAS_HEIGHT = 620;
const READABLE_ZOOM = 0.55;
/** 整图看不清时，打开就放大到关联最多的那个对象附近，用这个比例。 */
export const EXPLORE_ZOOM = 0.9;

/** dagre 算不出来时的兜底：按对象顺序排成固定列数的网格。 */
const FALLBACK_COLUMNS = 6;
const FALLBACK_GAP_X = 100;
const FALLBACK_GAP_Y = 40;

export interface CardBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 画布上的一根线：同一方向（起点对象 → 终点对象）上的全部关系。 */
export interface EdgeGroup {
  id: string;
  fromTable: string;
  toTable: string;
  relations: Relation[];
}

/**
 * 按「起点对象 → 终点对象」把关系合并成线（设计文档 §8.2）。A→B 和 B→A 是两根。
 * 顺序按每一组第一条关系在后端顺序里出现的位置，保证同一份数据永远是同一组线。
 */
export function groupRelations(relations: Relation[]): EdgeGroup[] {
  const groups = new Map<string, EdgeGroup>();
  for (const relation of relations) {
    const id = `${relation.fromTable}→${relation.toTable}`;
    const group = groups.get(id);
    if (group) {
      group.relations.push(relation);
    } else {
      groups.set(id, { id, fromTable: relation.fromTable, toTable: relation.toTable, relations: [relation] });
    }
  }
  return [...groups.values()];
}

/**
 * dagre 分层布局，rankdir=LR：引用方在左，被引用的主数据在右。返回卡片左上角坐标。
 *
 * <p>每个方向只喂一条边：dagre 3.1.1 遇到同一方向的平行边（multigraph）会抛
 * 「Not possible to find intersection inside of the rectangle」，整页跟着崩——一张凭证行表有五条指向科目表的关系是常态。
 * 节点和边按后端给的顺序喂入，同一份数据永远得到同一组坐标；dagre 仍然算不出来时按网格兜底，页面不崩。
 */
export function layoutTables(tables: TableCard[], groups: EdgeGroup[]): Map<string, CardBox> {
  try {
    const graph = new Graph();
    graph.setGraph({ rankdir: 'LR', nodesep: 36, ranksep: 120, marginx: 24, marginy: 24 });
    graph.setDefaultEdgeLabel(() => ({}));
    tables.forEach((table) => graph.setNode(table.name, { width: CARD_WIDTH, height: CARD_HEIGHT }));
    groups.forEach((group) => graph.setEdge(group.fromTable, group.toTable));
    layout(graph);
    const boxes = new Map<string, CardBox>();
    tables.forEach((table) => {
      const node = graph.node(table.name) as CardBox;
      boxes.set(table.name, {
        x: node.x - CARD_WIDTH / 2,
        y: node.y - CARD_HEIGHT / 2,
        width: CARD_WIDTH,
        height: CARD_HEIGHT,
      });
    });
    return boxes;
  } catch {
    return gridLayout(tables);
  }
}

function gridLayout(tables: TableCard[]): Map<string, CardBox> {
  const boxes = new Map<string, CardBox>();
  tables.forEach((table, index) => {
    boxes.set(table.name, {
      x: 24 + (index % FALLBACK_COLUMNS) * (CARD_WIDTH + FALLBACK_GAP_X),
      y: 24 + Math.floor(index / FALLBACK_COLUMNS) * (CARD_HEIGHT + FALLBACK_GAP_Y),
      width: CARD_WIDTH,
      height: CARD_HEIGHT,
    });
  });
  return boxes;
}

/** 整图能否在名义画布里以不低于 READABLE_ZOOM 的比例一屏放下。 */
export function fitsOnOneScreen(boxes: Map<string, CardBox>): boolean {
  let width = 0;
  let height = 0;
  boxes.forEach((box) => {
    width = Math.max(width, box.x + box.width);
    height = Math.max(height, box.y + box.height);
  });
  return (
    width * READABLE_ZOOM <= NOMINAL_CANVAS_WIDTH && height * READABLE_ZOOM <= NOMINAL_CANVAS_HEIGHT
  );
}

/** 关联最多的对象（按关系条数，起点、终点都算）；并列取后端顺序里靠前的。没有对象时为 null。 */
export function hubTable(tables: TableCard[], relations: Relation[]): string | null {
  const degree = new Map<string, number>();
  relations.forEach((relation) => {
    degree.set(relation.fromTable, (degree.get(relation.fromTable) ?? 0) + 1);
    degree.set(relation.toTable, (degree.get(relation.toTable) ?? 0) + 1);
  });
  let best: string | null = null;
  let bestDegree = -1;
  for (const table of tables) {
    const d = degree.get(table.name) ?? 0;
    if (d > bestDegree) {
      best = table.name;
      bestDegree = d;
    }
  }
  return best;
}
```

- [ ] **Step 2: 对象卡片与线**

`src/features/data-graph/components/ObjectCardNode.tsx`：

```tsx
import { memo, type CSSProperties, type FocusEvent, type KeyboardEvent } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { ObjectFlowNode } from '../flow';

// 对象卡片：业务名、一句说明（最多两行）、领域色条（设计文档 §8.2）。不列字段、不显示表名，所有卡片一样大。
// 左右各一个不可见的连接点：线从引用方的右边出发，落到被引用方的左边。
function ObjectCardNode({ data }: NodeProps<ObjectFlowNode>) {
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      data.onSelect(data.name);
    }
  };
  // 键盘聚焦到视野外的卡片时让画布平移过去（v2 审查 #6）。浏览器把焦点元素滚进视野时会去滚 React Flow 那个
  // overflow:hidden 的容器，和 React Flow 自己的平移打架（之后拖动、缩放都错位）；先把滚动复位，再按平移后的位置判断。
  const onFocus = (event: FocusEvent<HTMLDivElement>) => {
    const card = event.currentTarget;
    const pane = card.closest<HTMLElement>('.react-flow');
    if (pane) {
      pane.scrollTop = 0;
      pane.scrollLeft = 0;
    }
    const frame = (card.closest('.dg-canvas') ?? pane)?.getBoundingClientRect();
    const box = card.getBoundingClientRect();
    const visible =
      !frame ||
      (box.left >= frame.left && box.right <= frame.right && box.top >= frame.top && box.bottom <= frame.bottom);
    data.onFocusCard(data.name, visible);
  };
  const className = ['dg-card', data.selected ? 'is-selected' : '', data.dimmed ? 'is-dimmed' : '']
    .filter(Boolean)
    .join(' ');
  return (
    <div
      className={className}
      role="button"
      tabIndex={0}
      aria-pressed={data.selected}
      aria-label={data.title}
      onKeyDown={onKeyDown}
      onFocus={onFocus}
      data-testid="dg-card"
      data-table={data.name}
      style={{ '--dg-domain': data.domainColor } as CSSProperties}
    >
      <Handle type="target" position={Position.Left} isConnectable={false} className="dg-card__handle" />
      <span className="dg-card__domain" title={data.domain} aria-hidden />
      <div className="dg-card__body">
        <div className="dg-card__title-row">
          <span className="dg-card__title" title={data.title}>
            {data.title}
          </span>
          {data.hasSelfReference ? <span className="dg-badge">内部关联</span> : null}
        </div>
        {data.summary ? (
          <p className="dg-card__summary" title={data.summary}>
            {data.summary}
          </p>
        ) : null}
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} className="dg-card__handle" />
    </div>
  );
}

export default memo(ObjectCardNode);
```

`src/features/data-graph/components/RelationEdge.tsx`（整份覆盖）：

```tsx
import { memo, type CSSProperties } from 'react';
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import type { RelationFlowEdge } from '../flow';

const at = (x: number, y: number): CSSProperties => ({
  transform: `translate(-50%, -50%) translate(${x}px, ${y}px)`,
});

// 两个对象之间同一方向的全部关系合成一根线（设计文档 §8.2）：箭头指向被引用的对象；
// 任一条已核对画实线，否则画虚线；线上写角色名或「N 种关联」，悬停列出全部角色。
function RelationEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps<RelationFlowEdge>) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });
  const dimmed = data?.dimmed ?? false;
  const edgeClass = ['dg-edge', data?.confirmed ? 'is-confirmed' : 'is-inferred', dimmed ? 'is-dimmed' : '']
    .filter(Boolean)
    .join(' ');
  return (
    <>
      <BaseEdge id={id} path={path} className={edgeClass} markerEnd={markerEnd} />
      <EdgeLabelRenderer>
        {data?.label ? (
          <div
            className={`dg-edge-label nodrag nopan${dimmed ? ' is-dimmed' : ''}`}
            style={at(labelX, labelY)}
            title={data.roles.join('、')}
            data-testid="dg-edge-label"
            data-edge={id}
          >
            {data.label}
          </div>
        ) : null}
      </EdgeLabelRenderer>
    </>
  );
}

export default memo(RelationEdge);
```

```bash
cd $FE && git rm -q src/features/data-graph/components/TableCardNode.tsx
```

- [ ] **Step 3: 画布**

`src/features/data-graph/components/DataGraphCanvas.tsx`（整份覆盖）：

```tsx
import { useCallback, useEffect, useMemo } from 'react';
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import ObjectCardNode from './ObjectCardNode';
import RelationEdge from './RelationEdge';
import type { DomainOption } from '../domains';
import { buildFlow, type FocusRequest, type ObjectFlowNode, type RelationFlowEdge } from '../flow';
import {
  EXPLORE_ZOOM,
  fitsOnOneScreen,
  groupRelations,
  hubTable,
  layoutTables,
  type CardBox,
} from '../layout';
import type { SystemGraph } from '../types';

interface DataGraphCanvasProps {
  graph: SystemGraph;
  domains: DomainOption[];
  selected: string | null;
  domainFilter: string | null;
  focus: FocusRequest | null;
  titleOf: (table: string) => string;
  onSelect: (name: string | null) => void;
}

const nodeTypes = { object: ObjectCardNode };
const edgeTypes = { relation: RelationEdge };
// 画布按钮、小地图的悬停提示和读屏标签（React Flow 默认是英文）。
const ARIA_LABELS = {
  'controls.ariaLabel': '画布缩放',
  'controls.zoomIn.ariaLabel': '放大',
  'controls.zoomOut.ariaLabel': '缩小',
  'controls.fitView.ariaLabel': '显示全图',
  'minimap.ariaLabel': '小地图',
};

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const centerOf = (box: CardBox) => [box.x + box.width / 2, box.y + box.height / 2] as const;

function CanvasInner({ graph, domains, selected, domainFilter, focus, titleOf, onSelect }: DataGraphCanvasProps) {
  const related = useMemo(() => graph.tables.filter((table) => table.related), [graph.tables]);
  const groups = useMemo(() => groupRelations(graph.relations), [graph.relations]);
  // 布局只依赖对象和线；选中、领域筛选、搜索都不会触发重新布局。
  const boxes = useMemo(() => layoutTables(related, groups), [related, groups]);
  // 整图一屏看得清就整图显示；看不清就放大到关联最多的对象附近，配小地图（布局本身不变）。
  const overview = useMemo(() => fitsOnOneScreen(boxes), [boxes]);
  const hub = useMemo(() => hubTable(related, graph.relations), [related, graph.relations]);
  const hubTitle = hub ? titleOf(hub) : null;
  const { setCenter, getZoom } = useReactFlow();

  // 键盘聚焦跟随：直接跳过去、缩放不变。带动画的远距离平移会先缩小再放大（d3 的平滑缩放），一路 Tab 过去会晃得看不清。
  const onFocusCard = useCallback(
    (name: string, visible: boolean) => {
      const box = boxes.get(name);
      if (visible || !box) return;
      const [x, y] = centerOf(box);
      void setCenter(x, y, { zoom: getZoom(), duration: 0 });
    },
    [boxes, getZoom, setCenter],
  );
  const select = useCallback((name: string) => onSelect(name), [onSelect]);
  const { nodes, edges } = useMemo(
    () => buildFlow(related, groups, boxes, domains, selected, domainFilter, titleOf, select, onFocusCard),
    [related, groups, boxes, domains, selected, domainFilter, titleOf, select, onFocusCard],
  );

  const onInit = (instance: ReactFlowInstance<ObjectFlowNode, RelationFlowEdge>) => {
    if (overview || !hub) return;
    const box = boxes.get(hub);
    if (!box) return;
    const [x, y] = centerOf(box);
    void instance.setCenter(x, y, { zoom: EXPLORE_ZOOM, duration: 0 });
  };

  useEffect(() => {
    if (!focus) return;
    const box = boxes.get(focus.name);
    if (!box) return;
    const [x, y] = centerOf(box);
    void setCenter(x, y, { zoom: 1, duration: prefersReducedMotion() ? 0 : 300 });
  }, [boxes, focus, setCenter]);

  return (
    <>
      <ReactFlow<ObjectFlowNode, RelationFlowEdge>
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        nodesDraggable={false}
        nodesConnectable={false}
        nodesFocusable={false}
        edgesFocusable={false}
        elementsSelectable={false}
        onNodeClick={(_, node) => onSelect(node.id)}
        onPaneClick={() => onSelect(null)}
        onInit={onInit}
        fitView={overview}
        fitViewOptions={{ padding: 0.08, maxZoom: 1.1 }}
        minZoom={0.05}
        maxZoom={1.6}
        colorMode="dark"
        ariaLabelConfig={ARIA_LABELS}
      >
        <Background gap={32} size={1} color="rgba(56, 189, 248, 0.14)" bgColor="transparent" />
        <Controls position="top-right" showInteractive={false} />
        {overview ? null : (
          <MiniMap
            position="bottom-right"
            pannable
            zoomable
            nodeColor="#1d6f91"
            maskColor="rgba(6, 17, 31, 0.72)"
            bgColor="#081625"
          />
        )}
      </ReactFlow>
      {overview || !hubTitle ? null : (
        <div className="data-graph-hint" data-testid="dg-explore-hint">
          对象比较多，先显示「{hubTitle}」附近。拖动画布、滚轮缩放，或用搜索找对象。
        </div>
      )}
    </>
  );
}

export default function DataGraphCanvas(props: DataGraphCanvasProps) {
  return (
    <div className="dg-canvas" data-testid="data-graph-canvas">
      <ReactFlowProvider>
        <CanvasInner {...props} />
      </ReactFlowProvider>
    </div>
  );
}
```

- [ ] **Step 4: 页面接上新画布、样式换成对象卡片**

`src/pages/console/data-graph/DataGraphPage.tsx`：

第 1 处，把：

```tsx
import { dataGraphApi } from '@/features/data-graph/api';
import DataGraphCanvas from '@/features/data-graph/components/DataGraphCanvas';
import DataGraphSidePanel from '@/features/data-graph/components/DataGraphSidePanel';
import type { FocusRequest } from '@/features/data-graph/flow';
import { tableTitle } from '@/features/data-graph/text';
import type { SemanticStatus } from '@/features/data-graph/types';
```

替换为：

```tsx
import { dataGraphApi } from '@/features/data-graph/api';
import DataGraphCanvas from '@/features/data-graph/components/DataGraphCanvas';
import DataGraphSidePanel from '@/features/data-graph/components/DataGraphSidePanel';
import { domainOptions } from '@/features/data-graph/domains';
import type { FocusRequest } from '@/features/data-graph/flow';
import { tableTitle } from '@/features/data-graph/text';
import type { SemanticStatus } from '@/features/data-graph/types';
```

第 2 处，把：

```tsx
                      <DataGraphCanvas
                        key={graph.connectorId}
                        graph={graph}
                        selected={selected}
                        focus={focus}
                        onSelect={onSelect}
                      />
                    ) : (
```

替换为：

```tsx
                      <DataGraphCanvas
                        key={graph.connectorId}
                        graph={graph}
                        domains={domainOptions(graph.tables)}
                        selected={selected}
                        domainFilter={null}
                        focus={focus}
                        titleOf={titleOf}
                        onSelect={onSelect}
                      />
                    ) : (
```

`src/pages/console/data-graph/data-graph.css`：

第 1 处，把：

```css
/* 数据星图。配色沿用上一版（codex/enterprise-data-graph）的深色画布与青色发光；
   卡片尺寸必须与 features/data-graph/layout.ts 的常量一致（头 58 / 行 26 / 底 28 / 宽 240）。 */

.data-graph-page {
  --graph-navy: #06111f;
```

替换为：

```css
/* 数据星图。配色沿用上一版的深色画布与青色发光；
   卡片尺寸必须与 features/data-graph/layout.ts 的常量一致（宽 240 / 高 96，所有卡片一样大）。 */

.data-graph-page {
  --graph-navy: #06111f;
```

第 2 处，把：

```css
  opacity: 0.8;
}

/* ---------- 表卡片 ---------- */

.dg-card {
  display: flex;
  flex-direction: column;
  width: 240px;
  height: 100%;
  overflow: hidden;
  color: var(--graph-text);
  cursor: pointer;
```

替换为：

```css
  opacity: 0.8;
}

/* ---------- 对象卡片 ---------- */

.dg-card {
  position: relative;
  display: flex;
  width: 240px;
  height: 96px;
  overflow: hidden;
  color: var(--graph-text);
  cursor: pointer;
```

第 3 处，把：

```css
  opacity: 0.25;
}

.dg-card__head {
  display: grid;
  align-content: center;
  gap: 2px;
  height: 58px;
  padding: 0 12px;
  border-bottom: 1px solid var(--graph-border);
}

.dg-card__title-row {
```

替换为：

```css
  opacity: 0.25;
}

/* 领域色条：颜色来自 features/data-graph/domains.ts，按领域顺序分配 */
.dg-card__domain {
  flex: 0 0 4px;
  background: var(--dg-domain, #7c8ea3);
}

.dg-card__body {
  display: grid;
  flex: 1 1 auto;
  align-content: center;
  gap: 6px;
  min-width: 0;
  padding: 0 14px 0 12px;
}

.dg-card__title-row {
```

第 4 处，把：

```css
  text-overflow: ellipsis;
}

.dg-card__name,
.dg-card__note {
  overflow: hidden;
  font-size: 11px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.dg-card__name {
  color: #7dd3fc;
  font-family: var(--font-mono, ui-monospace, monospace);
}

.dg-card__note {
  color: var(--graph-muted);
}

.dg-card__rows {
  flex: 1 1 auto;
  margin: 0;
  padding: 0;
  list-style: none;
}

.dg-card__row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 26px;
  padding: 0 12px;
  font-size: 13px;
}

.dg-card__column {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  color: #cfe8f6;
  font-family: var(--font-mono, ui-monospace, monospace);
}

.dg-card__key {
  width: 6px;
  height: 6px;
  background: var(--graph-cyan);
  border-radius: 50%;
  box-shadow: 0 0 6px var(--graph-cyan);
}

.dg-card__comment {
  overflow: hidden;
  color: var(--graph-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.dg-card__foot {
  display: flex;
  align-items: center;
  height: 28px;
  padding: 0 12px;
  color: var(--graph-muted);
  font-size: 11px;
  border-top: 1px solid var(--graph-border);
}

.dg-card .dg-card__handle {
```

替换为：

```css
  text-overflow: ellipsis;
}

/* 一句说明最多两行 */
.dg-card__summary {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--graph-muted);
  font-size: 12px;
  line-height: 1.45;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.dg-card .dg-card__handle {
```

第 5 处，把：

```css
  opacity: 0.12;
}

.dg-edge-label,
.dg-edge-mark {
  position: absolute;
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.dg-edge-label {
  padding: 1px 8px;
  color: #bae6fd;
  font-size: 11px;
  white-space: nowrap;
  background: rgb(6 17 31 / 0.92);
  border: 1px solid #155e75;
  border-radius: 999px;
}

.dg-edge-mark {
  color: var(--graph-cyan);
  font-size: 11px;
  font-weight: 700;
}

.dg-edge-label.is-dimmed,
.dg-edge-mark.is-dimmed {
  opacity: 0.15;
}
```

替换为：

```css
  opacity: 0.12;
}

/* 线上的字：角色名或「N 种关联」；悬停出全部角色（title），所以要接得住指针 */
.dg-edge-label {
  position: absolute;
  padding: 1px 8px;
  color: #bae6fd;
  font-size: 11px;
  white-space: nowrap;
  cursor: default;
  pointer-events: all;
  background: rgb(6 17 31 / 0.92);
  border: 1px solid #155e75;
  border-radius: 999px;
  transition: opacity 0.2s ease;
}

.dg-edge-label.is-dimmed {
  opacity: 0.15;
}
```

- [ ] **Step 5: typecheck、lint、e2e**

```bash
cd $FE && source ~/.nvm/nvm.sh && nvm use 20 >/dev/null && npm run typecheck && npm run lint && npm run test:data-graph; echo exit=$?
```
Expected：typecheck、lint 通过；e2e 最后一行 `=== data-graph: 43/77 passed ===`，`exit=1`。画布这一块全绿：画完 7 张卡片 8 根线、10 条关系合成 8 根、A→B 与 B→A 是两根、实线 4 虚线 4、「3 种关联」与悬停列角色、箭头、卡片同尺寸不列字段、「内部关联」徽标、★ 默认视图扫描不到表名字段名（含读屏标签与悬停提示）、刷新坐标不变、点卡片不动、200 个对象的大图与小地图、画布按钮中文、★ 键盘聚焦跟随。没过的 34 条都是页面与右侧面板（页头、系统切换、概览、图例、关联清单、领域筛选、对象详情、空状态与两条提示、某个系统的关联加载失败，归 Task 14）和成员按模块进入（归 Task 15）。

- [ ] **Step 6: 提交**

```bash
cd $FE && git add -A src/features/data-graph src/pages/console/data-graph && \
  git commit -m "feat(data-graph): 画布改为对象地图——对象卡片、同向关系合成一根线、键盘聚焦跟随（审查 #6）、读屏标签念关系句子

dagre 3.1.1 遇到同一方向的平行边会抛错整页崩溃，改为每个方向只喂一条边，另加网格兜底。
键盘聚焦到视野外的卡片时直接跳过去、缩放不变。React Flow 默认的读屏标签「Edge from 表名 to 表名」会露表名，
改为念这根线上的关系句子；缩放按钮、小地图的悬停提示改成中文。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: 页面与右侧面板改为给人看

spec §8.1、§8.3、§8.4。页头说明、系统切换「连接显示名 · N 个对象」、概览（关联按条数计）与两条提示、领域筛选。「业务名称整理中」只在有对象还没拿到业务名、并且补全链正在跑或这个连接还从没跑过时挂（spec §6.3）；跑完了仍有对象没拿到业务名（两次都不合格、或补全链失败），就安静地用兜底，不挂提示。`truncated` 时提示只整理了按重要性排前 200 个对象。右侧面板未选中时是「关联清单」（按对象两两分组）与「暂未发现关联的对象」两个页签，记住上次停留的页签；选中后依次是「它是什么」「和谁有关」「技术信息（默认折叠）」，切换对象时详情状态全部重置。空状态画布区和页签用同一句话；「去数据连接」只对企业超管显示。

修掉的 v2 审查项：#1（字段搜索词残留：详情以对象名作 `key`，换对象即重建）、#9（返回停在上次的页签）、#10（两处空状态同一句话）、#11（选中、聚焦、领域筛选都按系统记，切换系统后自然作废，不会有一瞬间把上一个系统的选中套到新系统上）。

**Files:**
- Modify: `src/features/data-graph/text.ts`
- Modify: `src/features/data-graph/components/DataGraphSidePanel.tsx`（整份重写）
- Modify: `src/features/data-graph/components/TableDetailPanel.tsx`（整份重写）
- Modify: `src/pages/console/data-graph/DataGraphPage.tsx`（整份重写）
- Modify: `src/pages/console/data-graph/data-graph.css`

**Interfaces:**
- Consumes：Task 12 的类型与模板、`domains.ts`；Task 13 的 `DataGraphCanvas`（`domains`、`activeDomain`）。
- Produces：`text.ts` 新增 `interface SentenceGroup`、`groupByPair(relations, titleOf): SentenceGroup[]`（关联清单按对象两两分组）、`groupByCounterpart(...)`（对象详情「和谁有关」按相关对象分组）。

- [ ] **Step 1: 分组句子**

`src/features/data-graph/text.ts`：

第 1 处，把：

```ts
}

export const NO_SYSTEMS_TEXT = '还没有可以查看的业务系统。';
```

替换为：

```ts
}

export const NO_SYSTEMS_TEXT = '还没有可以查看的业务系统。';

export interface SentenceGroup {
  key: string;
  title: string;
  relations: Relation[];
}

/**
 * 关联清单：按对象两两分组，不分方向（客户、供应商互相引用的两条放在一组）。
 * 组的顺序按每组第一条关系在后端顺序里出现的位置；组名按第一条关系的起点、终点写。
 */
export function groupByPair(relations: Relation[], titleOf: (table: string) => string): SentenceGroup[] {
  const groups = new Map<string, SentenceGroup>();
  for (const relation of relations) {
    const key = [relation.fromTable, relation.toTable].sort().join('\u0000');
    const group = groups.get(key);
    if (group) {
      group.relations.push(relation);
    } else {
      groups.set(key, {
        key,
        title: `「${titleOf(relation.fromTable)}」与「${titleOf(relation.toTable)}」`,
        relations: [relation],
      });
    }
  }
  return [...groups.values()];
}

/** 单个对象的「和谁有关」：按另一端的对象分组，组名是那个对象的业务名。 */
export function groupByCounterpart(
  relations: Relation[],
  table: string,
  titleOf: (table: string) => string,
): SentenceGroup[] {
  const groups = new Map<string, SentenceGroup>();
  for (const relation of relations) {
    const other = relation.fromTable === table ? relation.toTable : relation.fromTable;
    const group = groups.get(other);
    if (group) {
      group.relations.push(relation);
    } else {
      groups.set(other, { key: other, title: titleOf(other), relations: [relation] });
    }
  }
  return [...groups.values()];
}
```

- [ ] **Step 2: 右侧面板与对象详情**

`src/features/data-graph/components/DataGraphSidePanel.tsx`（整份覆盖）：

```tsx
import { Tabs } from 'antd';
import TableDetailPanel from './TableDetailPanel';
import type { DomainOption } from '../domains';
import { groupByPair, relationSentence, relationSource, tableTitle } from '../text';
import type { SystemGraph } from '../types';

export type SidePanelTab = 'relations' | 'isolated';

interface DataGraphSidePanelProps {
  graph: SystemGraph;
  domains: DomainOption[];
  selected: string | null;
  tab: SidePanelTab;
  emptyText: string;
  titleOf: (table: string) => string;
  onTabChange: (tab: SidePanelTab) => void;
  onPick: (table: string) => void;
  onBack: () => void;
}

// 未选中对象：关联清单 / 暂未发现关联的对象，停在哪个页签由页面记着（v2 审查 #9）；
// 选中对象：该对象的详情（不在画布上的对象也从这里看）。
export default function DataGraphSidePanel({
  graph,
  domains,
  selected,
  tab,
  emptyText,
  titleOf,
  onTabChange,
  onPick,
  onBack,
}: DataGraphSidePanelProps) {
  const isolated = graph.tables.filter((table) => !table.related);
  return (
    <aside className="dg-side" aria-label="对象与关联" data-testid="dg-side">
      {selected ? (
        <TableDetailPanel
          key={selected}
          connectorId={graph.connectorId}
          tableName={selected}
          domains={domains}
          titleOf={titleOf}
          onBack={onBack}
        />
      ) : (
        <Tabs
          size="small"
          activeKey={tab}
          onChange={(key) => onTabChange(key as SidePanelTab)}
          items={[
            {
              key: 'relations',
              label: `关联清单（${graph.relations.length}）`,
              children: graph.relations.length ? (
                <div className="dg-groups" data-testid="dg-relation-list">
                  {groupByPair(graph.relations, titleOf).map((group) => (
                    <section key={group.key} className="dg-group">
                      <h5 className="dg-group__title">{group.title}</h5>
                      <ul className="dg-sentences">
                        {group.relations.map((relation) => (
                          <li key={relation.id}>
                            <button
                              type="button"
                              className="dg-link"
                              onClick={() => onPick(relation.fromTable)}
                            >
                              <span
                                className={`dg-swatch ${relation.tier === 'INFERRED' ? 'is-inferred' : 'is-confirmed'}`}
                                aria-hidden
                              />
                              <span>{relationSentence(relation, titleOf)}</span>
                            </button>
                            <span className="dg-source">{relationSource(relation)}</span>
                          </li>
                        ))}
                      </ul>
                    </section>
                  ))}
                </div>
              ) : (
                <p className="dg-muted" data-testid="dg-relations-empty">
                  {emptyText}
                </p>
              ),
            },
            {
              key: 'isolated',
              label: `暂未发现关联的对象（${isolated.length}）`,
              children: isolated.length ? (
                <ul className="dg-table-list" data-testid="dg-isolated-list">
                  {isolated.map((table) => (
                    <li key={table.name}>
                      <button type="button" className="dg-link" onClick={() => onPick(table.name)}>
                        <span className="dg-table-list__title">{tableTitle(table)}</span>
                        {table.selfReferences.length ? <span className="dg-badge">内部关联</span> : null}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="dg-muted">所有对象都已连进关系图。</p>
              ),
            },
          ]}
        />
      )}
    </aside>
  );
}
```

`src/features/data-graph/components/TableDetailPanel.tsx`（整份覆盖）：

```tsx
import { useMemo, useState } from 'react';
import { Alert, Input, Skeleton, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { dataGraphApi } from '../api';
import { colorOf, type DomainOption } from '../domains';
import {
  domainLabel,
  groupByCounterpart,
  relationSentence,
  relationSource,
  selfReferenceSentence,
  tableTitle,
  technicalLine,
} from '../text';

interface TableDetailPanelProps {
  connectorId: string;
  tableName: string;
  domains: DomainOption[];
  titleOf: (table: string) => string;
  onBack: () => void;
}

const FIELD_SEARCH_THRESHOLD = 30;

/**
 * 一个对象的详情（设计文档 §8.3）：它是什么 → 和谁有关 → 技术信息（默认折叠）。
 * 默认展开的两段只用业务名和角色名；表名、字段名、字段对应只在技术信息里。
 * 父组件按对象 key 这个组件：换一个对象，字段搜索词这类状态整个重置（v2 审查 #1）。
 */
export default function TableDetailPanel({
  connectorId,
  tableName,
  domains,
  titleOf,
  onBack,
}: TableDetailPanelProps) {
  const [fieldQuery, setFieldQuery] = useState('');
  const query = useQuery({
    queryKey: ['data-graph', 'table', connectorId, tableName],
    queryFn: () => dataGraphApi.table(connectorId, tableName),
  });
  const detail = query.data;
  const fields = useMemo(() => {
    const all = detail?.fields ?? [];
    const keyword = fieldQuery.trim().toLowerCase();
    if (!keyword) return all;
    return all.filter(
      (field) =>
        field.name.toLowerCase().includes(keyword) ||
        (field.comment ?? '').toLowerCase().includes(keyword),
    );
  }, [detail?.fields, fieldQuery]);

  if (query.isPending) {
    return <Skeleton active paragraph={{ rows: 8 }} />;
  }
  if (query.isError || !detail) {
    return (
      <Alert
        type="error"
        showIcon
        message="这个对象的详情没有加载出来"
        description={query.error instanceof Error ? query.error.message : undefined}
        action={
          <button type="button" className="dg-link" onClick={() => void query.refetch()}>
            重试
          </button>
        }
      />
    );
  }

  const title = tableTitle(detail);
  const groups = groupByCounterpart(detail.relations, detail.name, titleOf);
  return (
    <div className="dg-detail" data-testid="dg-detail">
      <button type="button" className="dg-link dg-detail__back" onClick={onBack}>
        ← 返回全部关联
      </button>

      <section className="dg-detail__section">
        <h4>它是什么</h4>
        <h3 className="dg-detail__title">{title}</h3>
        <span className="dg-detail__domain">
          <i style={{ background: colorOf(domains, detail.domain) }} aria-hidden />
          {domainLabel(detail.domain)}
        </span>
        {detail.summary ? (
          <p className="dg-detail__summary">{detail.summary}</p>
        ) : (
          <p className="dg-muted">暂无说明</p>
        )}
      </section>

      <section className="dg-detail__section">
        <h4>和谁有关</h4>
        {detail.selfReferences.length ? (
          <ul className="dg-sentences">
            {detail.selfReferences.map((ref) => (
              <li key={`self:${ref.fromColumn}`} className="is-self">
                <span>{selfReferenceSentence(title, ref)}</span>
                <span className="dg-source">{relationSource(ref)}</span>
              </li>
            ))}
          </ul>
        ) : null}
        {groups.map((group) => (
          <section key={group.key} className="dg-group">
            <h5 className="dg-group__title">{group.title}</h5>
            <ul className="dg-sentences">
              {group.relations.map((relation) => (
                <li key={relation.id}>
                  <span
                    className={`dg-swatch ${relation.tier === 'INFERRED' ? 'is-inferred' : 'is-confirmed'}`}
                    aria-hidden
                  />
                  <span>{relationSentence(relation, titleOf)}</span>
                  <span className="dg-source">{relationSource(relation)}</span>
                </li>
              ))}
            </ul>
          </section>
        ))}
        {!groups.length && !detail.selfReferences.length ? (
          <p className="dg-muted">暂未发现与其他对象的关联</p>
        ) : null}
      </section>

      <details className="dg-tech">
        <summary>技术信息</summary>
        <div className="dg-tech__body">
          <p className="dg-tech__row">
            表名 <code>{detail.name}</code>
            {detail.objectType === 'VIEW' ? <Tag>视图</Tag> : null}
          </p>
          {detail.comment && detail.comment !== title ? (
            <p className="dg-tech__row">表注释：{detail.comment}</p>
          ) : null}
          {detail.relations.length || detail.selfReferences.length ? (
            <ul className="dg-tech__mapping">
              {detail.selfReferences.map((ref) => (
                <li key={`self:${ref.fromColumn}`}>
                  {`${detail.name}.${ref.fromColumn} → ${detail.name}.${ref.toColumn} · ${relationSource(ref)}`}
                </li>
              ))}
              {detail.relations.map((relation) => (
                <li key={relation.id}>{technicalLine(relation)}</li>
              ))}
            </ul>
          ) : null}
          <h5 className="dg-group__title">字段（{detail.fields.length}）</h5>
          {detail.fields.length > FIELD_SEARCH_THRESHOLD ? (
            <Input
              allowClear
              size="small"
              placeholder="搜索字段"
              value={fieldQuery}
              onChange={(event) => setFieldQuery(event.target.value)}
            />
          ) : null}
          <ul className="dg-fields">
            {fields.map((field) => (
              <li key={field.name} className={field.inRelation ? 'is-related' : undefined}>
                <span className="dg-fields__name">{field.name}</span>
                {field.key === 'PRIMARY' ? <Tag color="cyan">主键</Tag> : null}
                {field.key === 'UNIQUE' ? <Tag color="blue">唯一</Tag> : null}
                {field.type ? <span className="dg-fields__type">{field.type}</span> : null}
                {field.comment ? <span className="dg-fields__comment">{field.comment}</span> : null}
              </li>
            ))}
          </ul>
        </div>
      </details>
    </div>
  );
}
```

- [ ] **Step 3: 页面**

`src/pages/console/data-graph/DataGraphPage.tsx`（整份覆盖）：

```tsx
import { useCallback, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Alert,
  AutoComplete,
  Button,
  ConfigProvider,
  Empty,
  Segmented,
  Skeleton,
  Tag,
  theme,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { authApi } from '@/features/auth/api';
import { dataGraphApi } from '@/features/data-graph/api';
import DataGraphCanvas from '@/features/data-graph/components/DataGraphCanvas';
import DataGraphSidePanel, {
  type SidePanelTab,
} from '@/features/data-graph/components/DataGraphSidePanel';
import { domainOptions } from '@/features/data-graph/domains';
import type { FocusRequest } from '@/features/data-graph/flow';
import {
  NO_SYSTEMS_TEXT,
  domainLabel,
  emptyRelationsText,
  tableTitle,
} from '@/features/data-graph/text';
import type { SystemGraph } from '@/features/data-graph/types';
import { useAuthStore } from '@/stores/authStore';
import './data-graph.css';

// 深色工作区：画布、搜索框、右侧面板里的 antd 组件都用暗色算法渲染，和画布同一套底色。
const DARK_THEME = {
  algorithm: theme.darkAlgorithm,
  token: { colorPrimary: '#22d3ee', colorBgContainer: '#0a1b2e' },
};

/**
 * 「业务名称整理中」只在两种时候出现（设计文档 §6.3）：有对象还没拿到业务名，并且补全链正在跑、或这个连接还从没跑过。
 * 跑完了仍有对象没拿到业务名（校验两次不过、或者失败了），就安静地用兜底，不挂提示。
 */
const namingInProgress = (graph: SystemGraph): boolean =>
  graph.relations.length > 0 &&
  graph.tables.some((table) => table.nameSource !== 'BUSINESS_VIEW') &&
  (graph.viewStatus === 'RUNNING' || graph.viewStatus === null);

/** 按系统记下来的页面状态：切换系统后自然作废，不会有一瞬间把上一个系统的选中套到新系统上（v2 审查 #11）。 */
interface Scoped<T> {
  system: string | null;
  value: T;
}

export default function DataGraphPage() {
  const [params, setParams] = useSearchParams();
  const token = useAuthStore((s) => s.token);
  const [selection, setSelection] = useState<Scoped<string | null>>({ system: null, value: null });
  const [focusState, setFocusState] = useState<Scoped<FocusRequest | null>>({ system: null, value: null });
  const [domainState, setDomainState] = useState<Scoped<string | null>>({ system: null, value: null });
  const [search, setSearch] = useState('');
  // 记住上次停留的页签：从对象详情返回时回到它，而不是总回到第一个（v2 审查 #9）。
  const [tab, setTab] = useState<SidePanelTab>('relations');

  // 与侧栏、ModuleRoute 共用同一份权限缓存。拿不到时按「不是超管」处理：管理页面的链接宁可不给。
  const { data: permission } = useQuery({
    queryKey: ['me', 'permissions'],
    queryFn: authApi.mePermissions,
    enabled: !!token,
    staleTime: 60_000,
  });
  const isSuperAdmin = permission?.superAdmin === true;

  const systemsQuery = useQuery({
    queryKey: ['data-graph', 'systems'],
    queryFn: dataGraphApi.systems,
  });
  const systems = useMemo(() => systemsQuery.data ?? [], [systemsQuery.data]);
  const requested = params.get('system');
  const current = systems.find((system) => system.connectorId === requested) ?? systems[0] ?? null;
  const currentId = current?.connectorId ?? null;

  const graphQuery = useQuery({
    queryKey: ['data-graph', 'system', currentId],
    queryFn: () => dataGraphApi.system(currentId ?? ''),
    enabled: currentId !== null,
  });
  const graph = graphQuery.data;

  const selected = selection.system === currentId ? selection.value : null;
  const focus = focusState.system === currentId ? focusState.value : null;
  const domainFilter = domainState.system === currentId ? domainState.value : null;

  const onSelect = useCallback(
    (name: string | null) => setSelection({ system: currentId, value: name }),
    [currentId],
  );
  const pick = useCallback(
    (name: string) => {
      setSelection({ system: currentId, value: name });
      setFocusState((previous) => ({
        system: currentId,
        value: { name, seq: (previous.value?.seq ?? 0) + 1 },
      }));
    },
    [currentId],
  );

  const titles = useMemo(
    () => new Map((graph?.tables ?? []).map((table) => [table.name, tableTitle(table)])),
    [graph?.tables],
  );
  const titleOf = useCallback((name: string) => titles.get(name) ?? name, [titles]);
  const domains = useMemo(() => domainOptions(graph?.tables ?? []), [graph?.tables]);

  const counts = useMemo(
    () => ({
      objects: graph?.tables.length ?? 0,
      confirmed: graph?.relations.filter((relation) => relation.tier === 'CONFIRMED').length ?? 0,
      inferred: graph?.relations.filter((relation) => relation.tier === 'INFERRED').length ?? 0,
      isolated: graph?.tables.filter((table) => !table.related).length ?? 0,
    }),
    [graph],
  );

  // 按业务名、说明找，也认表名（懂技术的人会直接敲表名）；下拉里只显示业务名和领域。
  const searchOptions = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!graph || !keyword) return [];
    return graph.tables
      .filter(
        (table) =>
          tableTitle(table).toLowerCase().includes(keyword) ||
          (table.summary ?? '').toLowerCase().includes(keyword) ||
          table.name.toLowerCase().includes(keyword),
      )
      .slice(0, 20)
      .map((table) => ({
        value: table.name,
        label: (
          <div className="dg-search-option">
            <strong>{tableTitle(table)}</strong>
            <span>{domainLabel(table.domain)}</span>
          </div>
        ),
      }));
  }, [graph, search]);

  const semanticLink = currentId
    ? `/console/connectors/${currentId}/semantic`
    : '/console/connectors';
  const emptyText = emptyRelationsText(graph?.semanticStatus ?? null);

  return (
    <main className="data-graph-page" data-testid="data-graph-page">
      <header className="data-graph-header">
        <h2 className="data-graph-header__title">数据星图</h2>
        <p className="data-graph-header__lead">
          看看业务系统里有哪些业务对象、它们之间怎样关联。内容由平台根据接入的系统自动整理，并随系统更新自动同步。
        </p>
      </header>

      {systemsQuery.isPending ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : systemsQuery.isError ? (
        <Alert
          type="error"
          showIcon
          message="业务系统列表没有加载出来"
          description={systemsQuery.error instanceof Error ? systemsQuery.error.message : undefined}
          action={
            <Button icon={<ReloadOutlined />} onClick={() => void systemsQuery.refetch()}>
              重试
            </Button>
          }
        />
      ) : !current ? (
        <section className="data-graph-blank" data-testid="dg-no-systems">
          <Empty
            description={
              <span>
                {NO_SYSTEMS_TEXT}
                {isSuperAdmin ? <Link to="/console/connectors">去「数据连接」</Link> : null}
              </span>
            }
          />
        </section>
      ) : (
        <>
          {systems.length > 1 ? (
            <Segmented
              className="data-graph-systems"
              value={current.connectorId}
              onChange={(value) => {
                setSearch('');
                setParams({ system: String(value) });
              }}
              options={systems.map((system) => ({
                value: system.connectorId,
                label: (
                  <span>
                    {system.displayName || system.name} · {system.tableCount} 个对象
                    {system.status === 'DISABLED' ? (
                      <Tag className="data-graph-systems__tag">已停用</Tag>
                    ) : null}
                  </span>
                ),
              }))}
            />
          ) : null}

          {graphQuery.isPending ? (
            <Skeleton active paragraph={{ rows: 10 }} />
          ) : graphQuery.isError || !graph ? (
            <Alert
              type="error"
              showIcon
              message="这个系统的关联没有加载出来"
              description={graphQuery.error instanceof Error ? graphQuery.error.message : undefined}
              action={
                <Button icon={<ReloadOutlined />} onClick={() => void graphQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : (
            <>
              <section className="data-graph-summary" aria-label="概览">
                <div>
                  <span>对象</span>
                  <strong>{counts.objects}</strong>
                </div>
                <div>
                  <span>已核对的关联</span>
                  <strong>{counts.confirmed}</strong>
                </div>
                <div>
                  <span>待核对的关联</span>
                  <strong>{counts.inferred}</strong>
                </div>
                <div>
                  <span>暂未发现关联的对象</span>
                  <strong>{counts.isolated}</strong>
                </div>
              </section>
              {namingInProgress(graph) || graph.truncated ? (
                <div className="data-graph-notes">
                  {namingInProgress(graph) ? (
                    <p className="data-graph-note" data-testid="dg-hint-naming">
                      业务名称整理中。
                    </p>
                  ) : null}
                  {graph.truncated ? (
                    <p className="data-graph-note" data-testid="dg-hint-truncated">
                      这个系统表很多，只整理了按重要性排前 200 个对象。
                    </p>
                  ) : null}
                </div>
              ) : null}
              {graph.relations.length > 0 && domains.length > 1 ? (
                <div className="data-graph-domains" role="group" aria-label="按领域查看" data-testid="dg-domains">
                  <button
                    type="button"
                    className={domainFilter === null ? 'is-active' : undefined}
                    aria-pressed={domainFilter === null}
                    onClick={() => setDomainState({ system: currentId, value: null })}
                  >
                    全部
                  </button>
                  {domains.map((domain) => (
                    <button
                      key={domain.label}
                      type="button"
                      className={domainFilter === domain.label ? 'is-active' : undefined}
                      aria-pressed={domainFilter === domain.label}
                      onClick={() => setDomainState({ system: currentId, value: domain.label })}
                    >
                      <i style={{ background: domain.color }} aria-hidden />
                      {domain.label}
                    </button>
                  ))}
                </div>
              ) : null}

              <ConfigProvider theme={DARK_THEME}>
                <section className="data-graph-workspace">
                  <div className="data-graph-main">
                    <div className="data-graph-toolbar">
                      <AutoComplete
                        className="data-graph-search"
                        value={search}
                        options={searchOptions}
                        onSearch={setSearch}
                        onChange={setSearch}
                        onSelect={(value) => {
                          setSearch('');
                          pick(String(value));
                        }}
                        placeholder="搜索对象（业务名或表名）"
                        notFoundContent={search.trim() ? '没有匹配的对象' : null}
                      />
                    </div>
                    {graph.relations.length > 0 ? (
                      <DataGraphCanvas
                        key={graph.connectorId}
                        graph={graph}
                        domains={domains}
                        selected={selected}
                        domainFilter={domainFilter}
                        focus={focus}
                        titleOf={titleOf}
                        onSelect={onSelect}
                      />
                    ) : (
                      <div className="data-graph-empty-canvas" data-testid="dg-empty">
                        <p>{emptyText}</p>
                        {isSuperAdmin && graph.semanticStatus !== 'READY' ? (
                          <Link to={semanticLink}>去「数据连接」</Link>
                        ) : null}
                      </div>
                    )}
                    <div className="data-graph-legend" aria-label="图例">
                      <span>
                        <i className="dg-swatch is-confirmed" aria-hidden />
                        已核对：数据核对通过或业务方确认
                      </span>
                      <span>
                        <i className="dg-swatch is-inferred" aria-hidden />
                        待核对：按表结构推断，尚未核对
                      </span>
                    </div>
                  </div>
                  <DataGraphSidePanel
                    graph={graph}
                    domains={domains}
                    selected={selected}
                    tab={tab}
                    emptyText={emptyText}
                    titleOf={titleOf}
                    onTabChange={setTab}
                    onPick={pick}
                    onBack={() => onSelect(null)}
                  />
                </section>
              </ConfigProvider>
            </>
          )}
        </>
      )}
    </main>
  );
}
```

`src/pages/console/data-graph/data-graph.css`：

第 1 处，把：

```css
  font-variant-numeric: tabular-nums;
}

.data-graph-note {
  margin: -4px 0 0;
  color: #0369a1;
  font-size: 12px;
}

.data-graph-blank {
  padding: 48px 24px;
  background: #fff;
```

替换为：

```css
  font-variant-numeric: tabular-nums;
}

.data-graph-notes {
  display: grid;
  gap: 2px;
  margin-top: -4px;
}

.data-graph-note {
  margin: 0;
  color: #0369a1;
  font-size: 12px;
}

/* ---------- 领域筛选：选中一个领域，别的领域的对象变暗，布局不动 ---------- */

.data-graph-domains {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.data-graph-domains button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 12px;
  color: #334155;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  background: #fff;
  border: 1px solid #d5dee7;
  border-radius: 999px;
}

.data-graph-domains button:hover {
  border-color: #94a3b8;
}

.data-graph-domains button:focus-visible {
  outline: 2px solid #0891b2;
  outline-offset: 2px;
}

.data-graph-domains button.is-active {
  color: #0b1b2b;
  font-weight: 600;
  background: #e0f2fe;
  border-color: #0891b2;
}

/* 圆点只是辅助，领域靠文字辨认；描一圈深色边，浅底上的浅色点也看得出来 */
.data-graph-domains i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border: 1px solid rgb(15 23 42 / 0.35);
  border-radius: 50%;
}

.data-graph-blank {
  padding: 48px 24px;
  background: #fff;
```

第 2 处，把：

```css

.dg-search-option span {
  color: #94a3b8;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}
```

替换为：

```css

.dg-search-option span {
  color: #94a3b8;
  font-size: 12px;
}
```

第 3 处，把：

```css
  color: #f0f9ff;
}

.dg-table-list__name {
  color: #7dd3fc;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
}

.dg-detail {
```

替换为：

```css
  color: #f0f9ff;
}

/* 关联清单、「和谁有关」：按对象分组 */
.dg-groups {
  display: grid;
  gap: 14px;
}

.dg-group {
  display: grid;
  gap: 8px;
}

.dg-group__title {
  margin: 0;
  color: #bfe3f5;
  font-size: 12px;
  font-weight: 600;
}

.dg-detail {
```

第 4 处，把：

```css
  font-size: 17px;
}

.dg-detail__name {
  color: #7dd3fc;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.dg-detail__comment {
  margin: 0;
  color: var(--graph-muted);
  font-size: 13px;
}

.dg-detail__section {
```

替换为：

```css
  font-size: 17px;
}

.dg-detail__domain {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--graph-text);
  font-size: 12px;
}

.dg-detail__domain i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dg-detail__summary {
  margin: 0;
  color: var(--graph-text);
  font-size: 13px;
  line-height: 1.6;
}

/* 技术信息：默认折叠，表名、字段、字段对应都只在这里 */
.dg-tech {
  padding-top: 12px;
  border-top: 1px solid var(--graph-border);
}

.dg-tech > summary {
  color: #bfe3f5;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.dg-tech > summary:focus-visible {
  outline: 2px solid var(--graph-blue);
  outline-offset: 2px;
}

.dg-tech__body {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.dg-tech__row {
  margin: 0;
  color: var(--graph-muted);
  font-size: 12px;
}

.dg-tech__row code,
.dg-tech__mapping {
  color: #cfe8f6;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.dg-tech__mapping {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
  word-break: break-all;
}

.dg-detail__section {
```

- [ ] **Step 4: typecheck、lint、e2e**

```bash
cd $FE && source ~/.nvm/nvm.sh && nvm use 20 >/dev/null && npm run typecheck && npm run lint && npm run test:data-graph; echo exit=$?
```
Expected：typecheck、lint 通过；e2e 最后一行 `=== data-graph: 72/77 passed ===`，`exit=1`。没过的 5 条全是成员按模块进入，归 Task 15：「有「数据星图」模块的成员能打开页面」「成员在空状态看不到「去数据连接」」「有模块的成员侧栏有「数据星图」入口」「★ 没有模块的成员直接敲地址：「无权访问」」「没有任何系统：成员不带管理链接」。

- [ ] **Step 5: 提交**

```bash
cd $FE && git add src/features/data-graph src/pages/console/data-graph && \
  git commit -m "feat(data-graph): 页面与右侧面板改为给人看——概览与提示、领域筛选、按对象分组的关联、它是什么 / 和谁有关 / 技术信息；审查 #1 #9 #10 #11

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: 入口改为按「数据星图」模块授权

spec §9。导航项把 `superAdminOnly` 换成 `module: 'DATA_GRAPH_MODULE'`，路由从 `SuperAdminRoute` 换成 `ModuleRoute`（没有模块时显示「无权访问」）。前端这一层只是纵深防御，真正的关卡是后端 Task 1 的 `assertCurrentModule`。仓库 CLAUDE.md 里的模块清单同步改。

**Files:**
- Modify: `src/components/atlas/workbenchNav.ts`
- Modify: `src/router/index.tsx`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes：后端 Task 1 的模块码 `DATA_GRAPH_MODULE`（`/admin/me/permissions` 的 `modules` 里出现它）；现有的 `ModuleRoute`（`src/router/ModuleRoute.tsx`，按模块码放行，超管恒放行）。

- [ ] **Step 1: 导航与路由**

`src/components/atlas/workbenchNav.ts`：

第 1 处，把：

```ts
    Icon: DatabaseIcon,
    superAdminOnly: true,
  },
  {
    key: 'data-graph',
    label: '数据星图',
    path: '/console/data-graph',
    Icon: DataGraphIcon,
    superAdminOnly: true,
  },
  // 写操作审批：待办性质的超管入口，和「数据连接」同源但不是管连接。
  {
```

替换为：

```ts
    Icon: DatabaseIcon,
    superAdminOnly: true,
  },
  // 数据星图给业务人员和产品看：按模块授权，企业超管在角色里勾上「数据星图」即可（超管自己始终可见）。
  {
    key: 'data-graph',
    label: '数据星图',
    path: '/console/data-graph',
    Icon: DataGraphIcon,
    module: 'DATA_GRAPH_MODULE',
  },
  // 写操作审批：待办性质的超管入口，和「数据连接」同源但不是管连接。
  {
```

`src/router/index.tsx`：

第 1 处，把：

```tsx
        <Route
          path="data-graph"
          element={
            <SuperAdminRoute>
              <DataGraphPage />
            </SuperAdminRoute>
          }
        />
        <Route
```

替换为：

```tsx
        <Route
          path="data-graph"
          element={
            <ModuleRoute module="DATA_GRAPH_MODULE">
              <DataGraphPage />
            </ModuleRoute>
          }
        />
        <Route
```

`CLAUDE.md`：

第 1 处，把：

```markdown
`useSSE` (`src/features/chat-admin/hooks/`) assembles these into **ordered `MessageSegment[]`** (`text` | `tool`) so the UI renders narration → tool call → answer in true interleaved order. Segments are persisted on the message so a page refresh restores the tool-call process (falls back to plain `content` when absent). Text deltas are batched via `requestAnimationFrame`.

### Module-level permissions
`MePermissions` (`GET /admin/me/permissions`) drives access. `ModuleRoute` (`src/router/ModuleRoute.tsx`) gates each console route by module key (`AGENT_MODULE`, `KB_MODULE`, `CHAT_MODULE` — that is the full list; `PLUGIN_MODULE` went away with the plugin subsystem); super-admins bypass. Routes that aren't module-gated (`skills`, `connections`, `traces`) either need only a session or do their own check — `/console/connections` is super-admin-only and gates itself on `perm.superAdmin` rather than a module key. It shares the `['me','permissions']` react-query cache with the sidebar and is **fail-open** (allows on fetch error — backend is the real gate). This is defense-in-depth, not the security boundary.

### The plugin subsystem is gone
`src/features/plugin/`, the plugin pages/routes/nav, `MePermissions.pluginIds`, the global-search `PluginHit` and the agent↔plugin binding were all removed when the backend dropped plugins. Skills replaced them: bind via **`/console/agents/:id` → 技能绑定** (`SkillBindPanel`, backed by `/admin/agent/agents/{id}/skills`). Anything still mentioning plugins is stale — the trace views keep `PLUGIN_TRIGGER` labels **on purpose**, only to render historical trace rows.
```

替换为：

```markdown
`useSSE` (`src/features/chat-admin/hooks/`) assembles these into **ordered `MessageSegment[]`** (`text` | `tool`) so the UI renders narration → tool call → answer in true interleaved order. Segments are persisted on the message so a page refresh restores the tool-call process (falls back to plain `content` when absent). Text deltas are batched via `requestAnimationFrame`.

### Module-level permissions
`MePermissions` (`GET /admin/me/permissions`) drives access. `ModuleRoute` (`src/router/ModuleRoute.tsx`) gates each console route by module key (`AGENT_MODULE`, `KB_MODULE`, `CHAT_MODULE`, `DATA_GRAPH_MODULE` — that is the full list; `PLUGIN_MODULE` went away with the plugin subsystem); super-admins bypass. `DATA_GRAPH_MODULE` is the one module the backend also checks by module (the data graph has no per-instance grants), so for it the 4001 「没有数据星图的访问权限」 is the real gate. Routes that aren't module-gated (`skills`, `connections`, `traces`) either need only a session or do their own check — `/console/connections` is super-admin-only and gates itself on `perm.superAdmin` rather than a module key. It shares the `['me','permissions']` react-query cache with the sidebar and is **fail-open** (allows on fetch error — backend is the real gate). This is defense-in-depth, not the security boundary.

### The plugin subsystem is gone
`src/features/plugin/`, the plugin pages/routes/nav, `MePermissions.pluginIds`, the global-search `PluginHit` and the agent↔plugin binding were all removed when the backend dropped plugins. Skills replaced them: bind via **`/console/agents/:id` → 技能绑定** (`SkillBindPanel`, backed by `/admin/agent/agents/{id}/skills`). Anything still mentioning plugins is stale — the trace views keep `PLUGIN_TRIGGER` labels **on purpose**, only to render historical trace rows.
```

第 2 处，把：

```markdown
Several backend DTOs send structured fields as **JSON-encoded strings** (e.g. `Connection.allowPaths` is a JSON array *string*, `allowMethods` is comma-separated). Each `features/<domain>/api.ts` converts at the edge so components work with real arrays/objects. Sending objects where the backend expects a string (or vice-versa) makes Jackson throw `HttpMessageNotReadableException`. Keep new fields flowing through these converters.

## Routing map (`src/router/index.tsx`)
`/login` · `/console/{dashboard,agents,agents/new,agents/:id,knowledge,knowledge/:kbId,playground/:agentId?,skills,skill/builder,connections,traces,feedback}` (admin console) · `/chat`, `/chat/agent/:agentId`, `/chat/c/:conversationId` (end-user). All non-login routes wrapped in `ProtectedRoute`; only the module-gated ones (agents / knowledge / chat) are additionally wrapped in `ModuleRoute`. Pages are `lazy()`-loaded.

## Deploy / SSE-sensitive serving
Served from an nginx image. **Two build paths, and CI uses the second one:**
```

替换为：

```markdown
Several backend DTOs send structured fields as **JSON-encoded strings** (e.g. `Connection.allowPaths` is a JSON array *string*, `allowMethods` is comma-separated). Each `features/<domain>/api.ts` converts at the edge so components work with real arrays/objects. Sending objects where the backend expects a string (or vice-versa) makes Jackson throw `HttpMessageNotReadableException`. Keep new fields flowing through these converters.

## Routing map (`src/router/index.tsx`)
`/login` · `/console/{dashboard,agents,agents/new,agents/:id,knowledge,knowledge/:kbId,playground/:agentId?,skills,skill/builder,connections,data-graph,traces,feedback}` (admin console) · `/chat`, `/chat/agent/:agentId`, `/chat/c/:conversationId` (end-user). All non-login routes wrapped in `ProtectedRoute`; only the module-gated ones (agents / knowledge / chat / data-graph) are additionally wrapped in `ModuleRoute`. Pages are `lazy()`-loaded.

## Deploy / SSE-sensitive serving
Served from an nginx image. **Two build paths, and CI uses the second one:**
```

- [ ] **Step 2: typecheck、lint、e2e 全绿**

```bash
cd $FE && source ~/.nvm/nvm.sh && nvm use 20 >/dev/null && npm run typecheck && npm run lint && npm run test:data-graph; echo exit=$?
```
Expected：最后一行 `=== data-graph: 77/77 passed ===`，`exit=0`。

- [ ] **Step 3: 提交**

```bash
cd $FE && git add src/components/atlas/workbenchNav.ts src/router/index.tsx CLAUDE.md && \
  git commit -m "feat(data-graph): 入口改为按「数据星图」模块授权（导航与路由）

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: 真实数据全栈验收（不提交代码）

在本地全栈上用真实数据逐条核对 spec §1 的七条验收。补全链会真实调用模型（每个连接 2–4 次），这是整个计划里唯一真调模型的一步。本地后端现在跑的若是别的构建，按 Step 1 换成本分支。

- [ ] **Step 1: 打包并重启本地 data-server（对账间隔临时调成 30 秒），清掉 test 租户的补全链状态**

本地库里可能已经有补全链的状态行（比如验证时留下的）：它们是 READY、指纹也是最新的，重启后定时对账不会派发任何东西。删掉 test 租户的状态行，两个连接就按「从没跑完过」各跑一轮。只删状态，不碰语义层和业务视图。

```bash
pkill -f 'data-server-1.0-SNAPSHOT.jar' || true; sleep 3
docker exec dev-mysql mysql -uroot -p123456 -N data-server -e "DELETE FROM connector_enrichment_state WHERE tenant_id='test'; SELECT ROW_COUNT();" 2>/dev/null
cd $DS && mvn -o -q install -DskipTests -pl common/common-core,common/common-persistence && \
  mvn -o -q -pl modules/data-server -am package -DskipTests && \
  (NACOS_SERVER_ADDR=localhost:8849 nohup java -Xmx768m -jar modules/data-server/target/data-server-1.0-SNAPSHOT.jar \
     --connector.semantic.enrichment.reconcile-interval-ms=30000 > /tmp/jm-data-server-v3.log 2>&1 &); \
  for i in $(seq 1 60); do grep -q 'Started DataServerApplication' /tmp/jm-data-server-v3.log && echo started && break; sleep 2; done
```
Expected：先打出删掉的状态行数（没跑过补全链的库上是 `0`，验证过的库上是 `2`），最后输出 `started`。`--connector.semantic.enrichment.reconcile-interval-ms=30000` 只为验收提速（Nacos 里没有这个键，命令行参数生效）；第一次对账在启动 5 分钟后。

- [ ] **Step 2: 等两个系统各跑完一轮补全链（验收第 5 条）**

```bash
for i in $(seq 1 120); do n=$(grep -c '补全链完成\|补全链失败' /tmp/jm-data-server-v3.log); [ "$n" -ge 2 ] && break; sleep 5; done; \
  grep -E '补全链(定时对账|完成|失败)|关系发现完成|业务文字生成完成' /tmp/jm-data-server-v3.log | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-260
```
Expected：两个连接各有一行 `补全链完成 … 结果=READY`（先 klny_erp，约 30 秒后 erp_real：对账每轮只派发一个，按连接 id 升序）；从「定时对账：派发」到「补全链完成」每个连接不超过 10 分钟（验证时各约 10–20 秒）；`两次都不合格退回兜底 0 条`。关系发现那一行里 klny_erp（`connectorId=2100122821063675906`）是「外键 0、命名规律 33」，erp_real 是「外键 0、命名规律 16」。在没跑过补全链的库上，klny_erp 会「替换 1 条」（`EFI_VENDOR_CPYCODEDTL.COMPANYCODEID` 那条指向兄弟明细表的推断，被换成指向公司代码），业务名一次补齐；在验证过的库上替换和业务名都是 0（已有的不重写）。「模型」那一项每次不同（验证时三次分别是 36/83/17 与 45/17/4，后两次是收紧提示词之后），它们只进语义层、不上星图。

某一行若是 `结果=FAILED` 且写着 `模型那一遍失败：…`：多半是模型服务那边的偶发错误（验证时遇到过一次断流，`StreamResetException: stream was reset: CANCEL`），规则候选和业务文字照常写了，只是要 6 小时后才重试。把那条连接的上次尝试时间往前拨 7 小时，对账会在 30 秒内重跑：

```bash
docker exec dev-mysql mysql -uroot -p123456 -N data-server -e "UPDATE connector_enrichment_state SET last_attempt_at = NOW() - INTERVAL 7 HOUR WHERE tenant_id='test' AND connector_id=2100122821063675906;" 2>/dev/null
```
（`connector_id` 换成失败的那条），等它那一行 `补全链完成 … 结果=READY` 出来再往下走。

- [ ] **Step 3: 强制再跑一轮，确认模型不会被重问（Task 6 的指纹修复）**

把 klny_erp 存下的输入指纹改成一个假值，对账就会认为输入变了、在 30 秒内再派发一轮；模型那一遍的输入没变，所以不该再问模型。

```bash
docker exec dev-mysql mysql -uroot -p123456 -N data-server -e "UPDATE connector_enrichment_state SET input_fingerprint = 'force-rerun-check' WHERE tenant_id='test' AND connector_id=2100122821063675906;" 2>/dev/null; \
  for i in $(seq 1 24); do grep -q '模型的输入没变，这次没问' /tmp/jm-data-server-v3.log && break; sleep 5; done; \
  grep '关系发现完成 connectorId=2100122821063675906' /tmp/jm-data-server-v3.log | sed 's/\x1b\[[0-9;]*m//g' | tail -1 | cut -c1-260
```
Expected：`关系发现完成 connectorId=2100122821063675906 新增 0 条、替换 0 条；候选：外键 0、命名规律 33、模型 0（模型的输入没变，这次没问）`，这一轮整条链 1 秒内跑完（验证时修复之前，这样一轮会把模型重问一遍、又多出 26 条）。

- [ ] **Step 4: 跑接口验收脚本（验收第 1、2、4、6 条）**

脚本放在 `$DS/.superpowers/sdd/` 下（那个目录里有 `*` 的 .gitignore），用完即删：

`$DS/.superpowers/sdd/data_graph_acceptance.py`：

```python
#!/usr/bin/env python3
"""数据星图 v3 本地真实数据验收（spec §1 第 1、2、4、6 条）。读星图接口和本地 dev 库；第 4 条会建两个角色、两个成员，最后停用成员。

用法：python3 data_graph_acceptance.py [网关，默认 http://localhost:10011]
环境变量：E2E_PASSWORD（企业超管 admin 的密码，默认 admin123）、E2E_TENANT（默认 test）
"""
import json, os, re, subprocess, sys, urllib.parse, urllib.request

GW = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:10011'
TENANT = os.environ.get('E2E_TENANT', 'test')
PASSWORD = os.environ.get('E2E_PASSWORD', 'admin123')

# spec 附录 A1：klny_erp 由命名规则推出的 33 条
A1 = """D1_CUSTOMER.INTERNALCOMPANYCODEID D1_COMPANYCODE.OID
D1_CUSTOMER.VENDORID D1_VENDOR.OID
D1_VENDOR.CUSTOMERID D1_CUSTOMER.OID
D1_VENDOR.INTERNALCOMPANYCODEID D1_COMPANYCODE.OID
EFI_CUSTOMER_CPYCODEDTL.COMPANYCODEID D1_COMPANYCODE.OID
EFI_CUSTOMER_CPYCODEDTL.RECONACCOUNTID D1_ACCOUNT.OID
EFI_ORIGINALTRANSDTL.VOUCHERDTLID EFI_VOUCHERDTL.OID
EFI_VENDOR_CPYCODEDTL.COMPANYCODEID D1_COMPANYCODE.OID
EFI_VENDOR_CPYCODEDTL.RECONACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.ACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.COUNTRYACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.CUSTOMERID D1_CUSTOMER.OID
EFI_VOUCHERDTL.GLACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.HOUSEACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.NEWCOMPANYCODEID D1_COMPANYCODE.OID
EFI_VOUCHERDTL.PARTNERPROFITCENTERID D1_PROFITCENTER.OID
EFI_VOUCHERDTL.PROFITCENTERID D1_PROFITCENTER.OID
EFI_VOUCHERDTL.RECONACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.VENDORID D1_VENDOR.OID
EMM_PURCHASEORDERDTL.COMPANYCODEID D1_COMPANYCODE.OID
EMM_PURCHASEORDERDTL.CUSTOMERID D1_CUSTOMER.OID
EMM_PURCHASEORDERDTL.DELIVERYCUSTOMERID D1_CUSTOMER.OID
EMM_PURCHASEORDERDTL.FI_ACCOUNTID D1_ACCOUNT.OID
EMM_PURCHASEORDERDTL.PROFITCENTERID D1_PROFITCENTER.OID
EMM_PURCHASEORDERDTL.VENDORID D1_VENDOR.OID
EMM_PURCHASEORDERHEAD.COMPANYCODEID D1_COMPANYCODE.OID
EMM_PURCHASEORDERHEAD.VENDORID D1_VENDOR.OID
EPS_PROJECT.COMPANYCODEID D1_COMPANYCODE.OID
ESD_SALEORDERDTL.COMPANYCODEID D1_COMPANYCODE.OID
ESD_SALEORDERDTL.PROFITCENTERID D1_PROFITCENTER.OID
ESD_SALEORDERHEAD.CREDITACCOUNTID D1_ACCOUNT.OID
ESD_SALEORDERHEAD.HEAD_COMPANYCODEID D1_COMPANYCODE.OID
ESD_SALEORDERHEAD.RECEIPTVENDORID D1_VENDOR.OID""".splitlines()
A1_PAIRS = [('EMM_PURCHASEORDERDTL', 'EMM_PURCHASEORDERHEAD'), ('ESD_SALEORDERDTL', 'ESD_SALEORDERHEAD')]
# spec 附录 A2：erp_real 命名规则 16 条 + v2 已有的 2 条
A2 = """D1_VENDOR.INTERNALCOMPANYCODEID D1_COMPANYCODE.OID
EFI_VENDOR_CPYCODEDTL.COMPANYCODEID D1_COMPANYCODE.OID
EFI_VENDOR_CPYCODEDTL.RECONACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.ACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.COUNTRYACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.GLACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.HOUSEACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.NEWCOMPANYCODEID D1_COMPANYCODE.OID
EFI_VOUCHERDTL.PARTNERPROFITCENTERID D1_PROFITCENTER.OID
EFI_VOUCHERDTL.PROFITCENTERID D1_PROFITCENTER.OID
EFI_VOUCHERDTL.RECONACCOUNTID D1_ACCOUNT.OID
EFI_VOUCHERDTL.VENDORID D1_VENDOR.OID
EFI_VOUCHERDTL.WBSELEMENTID EPS_WBSELEMENT.OID
EFI_VOUCHERDTL_EXT.COMPANYCODEID D1_COMPANYCODE.OID
EFI_VOUCHERDTL_EXT.SRCCOMPANYCODEID D1_COMPANYCODE.OID
EPS_WBSELEMENT.COMPANYCODEID D1_COMPANYCODE.OID
EFI_VENDOR_CPYCODEDTL.OID D1_VENDOR.OID
EFI_VOUCHERDTL_EXT.OID EFI_VOUCHERDTL.OID""".splitlines()
BANNED = ['判不出', '未经', '验证', '样本', '采样', '置信', '估算', 'innodb', '疑似', '或许', '大概', '推测', '语义层',
          '说明书', '主键', '外键', '字段']

results = []


def check(name, ok, detail=''):
    results.append(ok)
    print(('PASS  ' if ok else 'FAIL  ') + name + (f'  —— {detail}' if detail and not ok else ''))


def call(method, path, token=None, body=None):
    req = urllib.request.Request(GW + '/data' + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    if token:
        req.add_header('Authorization', token)
        req.add_header('X-Tenant-Id', TENANT)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.status, json.loads(resp.read().decode())


def login(username, password):
    status, body = call('POST', '/admin/auth/login', body={'username': username, 'password': password, 'tenantId': TENANT})
    assert body.get('success'), f'登录失败 {username}: {body.get("respMsg")}'
    return body['data']['token']


def ids_of(graph, details):
    tables = [t['name'] for t in graph['tables']]
    columns = [f['name'] for d in details.values() for f in d['fields']]
    out = {t.lower() for t in tables if re.fullmatch(r'[A-Za-z0-9_$]+', t)}
    out |= {c.lower() for c in columns if len(c) >= 4 and re.fullmatch(r'[A-Za-z0-9_$]+', c)}
    return out


def text_problems(text, identifiers):
    if not text:
        return None
    low = text.lower()
    for w in BANNED:
        if w in low:
            return f'禁用词「{w}」'
    for tok in re.findall(r'[A-Za-z0-9_$]+', text):
        if tok.lower() in identifiers:
            return f'代码「{tok}」'
    return None


admin = login('admin', PASSWORD)
_, systems = call('GET', '/admin/data-graph/systems', admin)
systems = systems['data']
by_name = {s['displayName'] or s['name']: s for s in systems}
print('系统：', ', '.join(f"{k}({s['tableCount']} 张，truncated={s['truncated']}，viewStatus={s['viewStatus']})" for k, s in by_name.items()))

for label, expected, pairs, minimum in (('klny_erp', A1, A1_PAIRS, 33), ('erp_real', A2, [], 18)):
    sys_ = next(s for s in systems if label in (s['displayName'], s['name']))
    _, g = call('GET', f"/admin/data-graph/systems/{sys_['connectorId']}", admin)
    g = g['data']
    rels = {f"{r['fromTable']}.{r['fromColumn']} {r['toTable']}.{r['toColumn']}": r for r in g['relations']}
    missing, rejected = [], []
    for e in expected:
        if e in rels:
            continue
        frm, to = e.split(' ')
        table, column = frm.split('.')
        sql = ("SELECT CONCAT(verified, ' ', IFNULL(JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.basis')),'')) FROM connector_semantic "
               f"WHERE deleted=0 AND scope='JOIN' AND connector_id={sys_['connectorId']} AND object_name='{table}' AND field_name='{column}'")
        verdict = subprocess.run(['docker', 'exec', 'dev-mysql', 'mysql', '--default-character-set=utf8mb4', '-uroot', '-p123456',
                                  '-N', 'data-server', '-e', sql], capture_output=True, text=True).stdout.strip()
        (rejected if verdict.startswith(('REJECTED', 'WEAK')) else missing).append(f'{e}（{verdict or "语义层里没有"}）')
    for r in rejected:
        print(f'记录  #1 {label}：被采样核对否掉、按规则不画：{r}')
    check(f'#1 {label}：附录 A 的关系除被数据否掉的外全部出现（出现 {len(expected) - len(missing) - len(rejected)}，'
          f'否掉 {len(rejected)}，缺 {len(missing)}）', not missing, '; '.join(missing))
    minimum -= len(rejected)
    for p in pairs:
        # 模型那一遍推出的关系核对通过才上星图（spec §7.1 第 7′ 行），本地库数据太少判不出，所以在语义层里核对。
        sql = ("SELECT COUNT(*) FROM connector_semantic WHERE deleted=0 AND scope='JOIN' "
               f"AND connector_id={sys_['connectorId']} AND object_name='{p[0]}' "
               f"AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.to_object'))='{p[1]}'")
        n = subprocess.run(['docker', 'exec', 'dev-mysql', 'mysql', '-uroot', '-p123456', '-N', 'data-server', '-e', sql],
                           capture_output=True, text=True).stdout.strip()
        # 记录项：模型那一遍有随机性，只记录，不判通过（spec §1 第 1 条）。
        print(f"记录  #1 {label}：语义层里 {p[0]} → {p[1]} 的关联 {n or '?'} 条")
    check(f'#1 {label}：关系至少 {minimum} 条（实际 {len(rels)}）', len(rels) >= minimum)
    non_key = [k for k, r in rels.items() if r['cardinality'] is None and r['tier'] != 'CONFIRMED']
    check(f'#1 {label}：终点不是唯一键的关系都已确认', not non_key, '; '.join(non_key))
    bad_names = [t['name'] for t in g['tables'] if t['nameSource'] != 'BUSINESS_VIEW']
    check(f'#2 {label}：{len(g["tables"])} 个对象的标题都来自业务视图', not bad_names, ', '.join(bad_names))
    details = {}
    for t in g['tables']:
        _, d = call('GET', f"/admin/data-graph/systems/{sys_['connectorId']}/tables?name={urllib.parse.quote(t['name'])}", admin)
        details[t['name']] = d['data']
    identifiers = ids_of(g, details)
    problems = []
    for t in g['tables']:
        for field in ('displayName', 'summary', 'domain'):
            p = text_problems(t[field], identifiers) if t['nameSource'] == 'BUSINESS_VIEW' or field != 'displayName' else None
            if p:
                problems.append(f"{t['name']}.{field}={t[field]}：{p}")
        for s in t['selfReferences']:
            p = text_problems(s['role'], identifiers)
            if p:
                problems.append(f"{t['name']} 自关联 {s['role']}：{p}")
    for k, r in rels.items():
        p = text_problems(r['role'], identifiers)
        if p:
            problems.append(f"{k} 角色 {r['role']}：{p}")
    check(f'#6 {label}：业务文字没有禁用词、没有代码', not problems, '; '.join(problems[:5]))

# #4 权限：一个有「数据星图」模块的成员、一个没有的
_, modules = call('GET', '/admin/rbac/grantable/modules', admin)
check('#4 可授权模块里有「数据星图」', any(m['code'] == 'DATA_GRAPH_MODULE' and m['name'] == '数据星图' for m in modules['data']))
def role(code, name, modules):
    """按角色码找，没有就建；授权每次按 modules 重写。重复跑脚本时复用上一次建的。"""
    _, roles = call('GET', '/admin/rbac/roles', admin)
    found = next((r for r in roles['data'] if r.get('code') == code), None)
    if found is None:
        _, created = call('POST', '/admin/rbac/roles', admin, {'code': code, 'name': name})
        found = created['data']
    call('PUT', f"/admin/rbac/roles/{found['id']}/grants", admin, {'modules': modules, 'agents': [], 'knowledgeBases': []})
    return found['id']


def member(username, display, role_id):
    """按用户名找，没有就建；有就启用并把角色换成这一个。"""
    _, members = call('GET', '/admin/rbac/members', admin)
    found = next((m for m in members['data'] if m.get('username') == username), None)
    if found is None:
        _, created = call('POST', '/admin/rbac/members', admin,
                          {'username': username, 'password': 'Accept#2026', 'displayName': display, 'roleIds': [role_id]})
        return created['data']['id']
    call('POST', f"/admin/rbac/members/{found['id']}/enable", admin)
    call('PUT', f"/admin/rbac/members/{found['id']}/roles", admin, {'roleIds': [role_id]})
    return found['id']


m1 = member('dg_accept_yes', '星图验收甲', role('dg_viewer_accept', '星图验收-有模块', ['DATA_GRAPH_MODULE']))
m2 = member('dg_accept_no', '星图验收乙', role('dg_none_accept', '星图验收-无模块', []))
try:
    yes = login('dg_accept_yes', 'Accept#2026')
    status, body = call('GET', '/admin/data-graph/systems', yes)
    check('#4 有模块的成员：看得到本企业全部系统', status == 200 and body['success'] and len(body['data']) == len(systems))
    no = login('dg_accept_no', 'Accept#2026')
    status, body = call('GET', '/admin/data-graph/systems', no)
    check('#4 没有模块的成员：HTTP 200 + 4001「没有数据星图的访问权限」',
          status == 200 and not body['success'] and str(body['respCode']) == '4001' and body['respMsg'] == '没有数据星图的访问权限',
          f"{status} {body.get('respCode')} {body.get('respMsg')}")
finally:
    for m in (m1, m2):
        call('POST', f"/admin/rbac/members/{m}/disable", admin)

print(f'\n=== data-graph-acceptance: {sum(results)}/{len(results)} passed ===')
sys.exit(0 if all(results) else 1)
```

```bash
cd $DS && python3 .superpowers/sdd/data_graph_acceptance.py
```
Expected：最后一行 `=== data-graph-acceptance: 13/13 passed ===`；中间的「记录」行把被采样核对否掉的关系（验证时是 klny_erp 的 `EFI_ORIGINALTRANSDTL.VOUCHERDTLID`，采样 20 个取值、命中 0）和「明细 → 表头」在语义层里的条数列出来，把这几行原样转给用户。脚本会建两个角色（`dg_viewer_accept`、`dg_none_accept`）和两个成员（`dg_accept_yes`、`dg_accept_no`），结束时停用两个成员；角色留着无害。

- [ ] **Step 5: 认领的 CAS 在真库上只让一个拿到（Review Focus #2）**

```bash
docker exec dev-mysql mysql -uroot -p123456 -N data-server -e "
UPDATE connector_enrichment_state SET claim_at = NOW(), last_attempt_at = NOW() WHERE tenant_id='test' AND connector_id=2101916578146746370 AND (claim_at IS NULL OR claim_at < NOW() - INTERVAL 30 MINUTE); SELECT ROW_COUNT();
UPDATE connector_enrichment_state SET claim_at = NOW(), last_attempt_at = NOW() WHERE tenant_id='test' AND connector_id=2101916578146746370 AND (claim_at IS NULL OR claim_at < NOW() - INTERVAL 30 MINUTE); SELECT ROW_COUNT();
UPDATE connector_enrichment_state SET claim_at = NULL WHERE tenant_id='test' AND connector_id=2101916578146746370;" 2>/dev/null
```
Expected：两行，第一行 `1`、第二行 `0`（和 `ConnectorEnrichmentStateMapper.claim` 是同一条 SQL）。最后一句放掉认领。

- [ ] **Step 6: 页面验收（验收第 1、2、3、6、7 条在页面上再核一遍；第 4 条的页面部分由 Task 15 的 e2e 覆盖）**

在浏览器里看真实数据：两个系统 27 个对象的标题都是业务名（画布、清单、详情三处一致）；不选对象、以及逐个打开 27 个对象的详情（技术信息折叠）时，页面文字、读屏标签、悬停提示里都没有这个系统的物理表名和长度 ≥ 4 的字段名；业务文字没有附录 B 的禁用词；刷新 10 次坐标不变、点卡片不动、全程控制台无报错。每对预期的对象之间至少一根线（附录 A，被采样核对否掉的除外）。

前端 dev 指向本地网关（`.env.development` 里是 `VITE_API_TARGET=http://localhost:10011`，即 Step 1 起的后端经网关）。脚本放在 `$FE/e2e/` 下（要用那里的 `lib.mjs` 和 Playwright），用完即删：

`$FE/e2e/data-graph-live.local.mjs`：

```js
// 数据星图 v3：真实数据上的验收检查（本地脚本，不进仓库）。
//
// 前提：前端 dev 指向装了 v3 的网关，本地 test 租户的补全链已经跑完。用法：
//   node e2e/data-graph-live.local.mjs
// 环境变量：
//   E2E_BASE_URL      前端地址，默认 http://localhost:5181
//   E2E_USERNAME / E2E_PASSWORD   登录账号，默认 admin / admin123（企业超管）
//   DG_EXPECTED_FILE  预期关系（JSON，结构同下面的 DEFAULT_EXPECTED）；不给就用设计文档附录 A。
//
// 覆盖验收（设计文档 §1）：
//   1 关系覆盖——预期关系逐条在接口里（否掉的除外），每对预期的对象之间画着至少一根线；终点不是唯一键的只画已核对的；
//   2 业务名称——每个系统的对象数对得上，标题全部来自业务视图，画布、清单、详情上显示的就是这个业务名；
//   3 默认视图没有代码——不选对象时、逐个打开对象详情（技术信息折叠）时，页面文字、读屏标签、悬停提示里
//     都没有这个系统的物理表名和长度 ≥ 4 的字段名（不分大小写、按完整单词）；
//   6 说人话——业务文字没有附录 B 的禁用词，也没有表名、字段名；
//   7 v2 的验收——刷新 10 次坐标不变、点对象卡片不动、界面固定文案没有运维 / AI 用语、全程控制台无报错。
import { readFileSync } from 'node:fs';
import { launchBrowser, reporter, shot } from './lib.mjs';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5181';
const USERNAME = process.env.E2E_USERNAME ?? 'admin';
const PASSWORD = process.env.E2E_PASSWORD ?? 'admin123';

// 附录 A2：命名规则推出的 16 条。
const A2_RULES = [
  'D1_VENDOR.INTERNALCOMPANYCODEID → D1_COMPANYCODE.OID',
  'EFI_VENDOR_CPYCODEDTL.COMPANYCODEID → D1_COMPANYCODE.OID      （v2 已有）',
  'EFI_VENDOR_CPYCODEDTL.RECONACCOUNTID → D1_ACCOUNT.OID',
  'EFI_VOUCHERDTL.ACCOUNTID → D1_ACCOUNT.OID',
  'EFI_VOUCHERDTL.COUNTRYACCOUNTID → D1_ACCOUNT.OID',
  'EFI_VOUCHERDTL.GLACCOUNTID → D1_ACCOUNT.OID                   （v2 已有）',
  'EFI_VOUCHERDTL.HOUSEACCOUNTID → D1_ACCOUNT.OID',
  'EFI_VOUCHERDTL.NEWCOMPANYCODEID → D1_COMPANYCODE.OID',
  'EFI_VOUCHERDTL.PARTNERPROFITCENTERID → D1_PROFITCENTER.OID',
  'EFI_VOUCHERDTL.PROFITCENTERID → D1_PROFITCENTER.OID           （v2 已有）',
  'EFI_VOUCHERDTL.RECONACCOUNTID → D1_ACCOUNT.OID',
  'EFI_VOUCHERDTL.VENDORID → D1_VENDOR.OID                       （v2 已有）',
  'EFI_VOUCHERDTL.WBSELEMENTID → EPS_WBSELEMENT.OID',
  'EFI_VOUCHERDTL_EXT.COMPANYCODEID → D1_COMPANYCODE.OID',
  'EFI_VOUCHERDTL_EXT.SRCCOMPANYCODEID → D1_COMPANYCODE.OID',
  'EPS_WBSELEMENT.COMPANYCODEID → D1_COMPANYCODE.OID             （v2 已有）',
];
// 附录 A2：v2 已有、不靠命名规则的 2 条。
const A2_V2 = [
  'EFI_VENDOR_CPYCODEDTL.OID → D1_VENDOR.OID',
  'EFI_VOUCHERDTL_EXT.OID → EFI_VOUCHERDTL.OID',
];

/**
 * 预期（设计文档附录 A，原样抄录；行尾「（…）」是注释，解析时去掉）。
 * - system：连接名或显示名，对上任意一个即可；objects：这个系统的对象数。
 * - relations：要求出现在星图上的关系，一行一条「表.列 → 表.列」；它们两两所在的对象对都要有线。
 * - rejected：被采样核对否掉的（数据不支持就不画），允许缺席，只记录在不在。
 * - pairs：另外要求有线的对象对，一行一对「表 → 表」。
 * - recordPairs：记录项，有没有线只记录、不判通过。
 */
const DEFAULT_EXPECTED = [
  {
    system: ['test', 'klny_erp'],
    objects: 19,
    relations: [
      'D1_CUSTOMER.INTERNALCOMPANYCODEID → D1_COMPANYCODE.OID',
      'D1_CUSTOMER.VENDORID → D1_VENDOR.OID',
      'D1_VENDOR.CUSTOMERID → D1_CUSTOMER.OID',
      'D1_VENDOR.INTERNALCOMPANYCODEID → D1_COMPANYCODE.OID',
      'EFI_CUSTOMER_CPYCODEDTL.COMPANYCODEID → D1_COMPANYCODE.OID',
      'EFI_CUSTOMER_CPYCODEDTL.RECONACCOUNTID → D1_ACCOUNT.OID',
      'EFI_ORIGINALTRANSDTL.VOUCHERDTLID → EFI_VOUCHERDTL.OID',
      'EFI_VENDOR_CPYCODEDTL.COMPANYCODEID → D1_COMPANYCODE.OID      （现有一条错的推断占着这一列，靠 §5.4 的替换写入）',
      'EFI_VENDOR_CPYCODEDTL.RECONACCOUNTID → D1_ACCOUNT.OID',
      'EFI_VOUCHERDTL.ACCOUNTID → D1_ACCOUNT.OID',
      'EFI_VOUCHERDTL.COUNTRYACCOUNTID → D1_ACCOUNT.OID',
      'EFI_VOUCHERDTL.CUSTOMERID → D1_CUSTOMER.OID',
      'EFI_VOUCHERDTL.GLACCOUNTID → D1_ACCOUNT.OID',
      'EFI_VOUCHERDTL.HOUSEACCOUNTID → D1_ACCOUNT.OID',
      'EFI_VOUCHERDTL.NEWCOMPANYCODEID → D1_COMPANYCODE.OID',
      'EFI_VOUCHERDTL.PARTNERPROFITCENTERID → D1_PROFITCENTER.OID',
      'EFI_VOUCHERDTL.PROFITCENTERID → D1_PROFITCENTER.OID           （v2 已有）',
      'EFI_VOUCHERDTL.RECONACCOUNTID → D1_ACCOUNT.OID',
      'EFI_VOUCHERDTL.VENDORID → D1_VENDOR.OID                       （v2 已有）',
      'EMM_PURCHASEORDERDTL.COMPANYCODEID → D1_COMPANYCODE.OID',
      'EMM_PURCHASEORDERDTL.CUSTOMERID → D1_CUSTOMER.OID',
      'EMM_PURCHASEORDERDTL.DELIVERYCUSTOMERID → D1_CUSTOMER.OID',
      'EMM_PURCHASEORDERDTL.FI_ACCOUNTID → D1_ACCOUNT.OID',
      'EMM_PURCHASEORDERDTL.PROFITCENTERID → D1_PROFITCENTER.OID',
      'EMM_PURCHASEORDERDTL.VENDORID → D1_VENDOR.OID',
      'EMM_PURCHASEORDERHEAD.COMPANYCODEID → D1_COMPANYCODE.OID',
      'EMM_PURCHASEORDERHEAD.VENDORID → D1_VENDOR.OID',
      'EPS_PROJECT.COMPANYCODEID → D1_COMPANYCODE.OID',
      'ESD_SALEORDERDTL.COMPANYCODEID → D1_COMPANYCODE.OID',
      'ESD_SALEORDERDTL.PROFITCENTERID → D1_PROFITCENTER.OID',
      'ESD_SALEORDERHEAD.CREDITACCOUNTID → D1_ACCOUNT.OID',
      'ESD_SALEORDERHEAD.HEAD_COMPANYCODEID → D1_COMPANYCODE.OID',
      'ESD_SALEORDERHEAD.RECEIPTVENDORID → D1_VENDOR.OID',
    ],
    // 2026-10-01 本地库上否掉的 1 条（采样 20 个取值、命中 0）。
    rejected: ['EFI_ORIGINALTRANSDTL.VOUCHERDTLID → EFI_VOUCHERDTL.OID'],
    pairs: [],
    // 模型那一遍推出的「明细 → 表头」：要等核对通过才上星图（§7.1 第 7′ 行），本地库核对判不出。
    recordPairs: [
      'EMM_PURCHASEORDERDTL → EMM_PURCHASEORDERHEAD（数据上是 SOID → OID）',
      'ESD_SALEORDERDTL → ESD_SALEORDERHEAD（数据上是 SOID → OID）',
    ],
  },
  {
    system: ['erp_real'],
    objects: 8,
    // 命名规则推出的 16 条 + v2 已有、不靠命名规则的 2 条，合计 18 条。
    relations: [...A2_RULES, ...A2_V2],
    rejected: [],
    pairs: [],
    recordPairs: [],
  },
];

// 界面固定文案里不许出现的运维 / AI 用语（沿用 v2 的清单）。拉丁词按完整单词比，其余按子串。
const UI_FORBIDDEN = ['包含', 'READY', 'RUNNING', 'FAILED', 'INSPECTOR', 'UNKNOWN', '%', 'AI', '置信度', '语义层'];
// 附录 B：业务文字禁用词。
const BUSINESS_FORBIDDEN = ['判不出', '未经', '验证', '样本', '采样', '置信', '估算', 'InnoDB', '疑似', '或许', '大概', '推测', '语义层', '说明书', '主键', '外键', '字段'];
// 界面固定文案所在的区域（业务文字不在这里查运维用语：业务说明里写「包含」「13%」是正常的）。
const CHROME = [
  '.data-graph-header',
  '.data-graph-systems',
  '.data-graph-summary',
  '.data-graph-notes',
  '.data-graph-legend',
  '[data-testid="dg-explore-hint"]',
  '[data-testid="dg-empty"]',
  '.ant-tabs-nav',
  '.dg-source',
  '.dg-detail h4',
  '.dg-detail__back',
  '.dg-tech > summary',
].join(', ');

// ---------------------------------------------------------------- 解析预期

const clean = (line) => line.replace(/（.*$/, '').trim();
const linesOf = (value) => (Array.isArray(value) ? value : String(value ?? '').split('\n')).map(clean).filter(Boolean);

function parseRelation(line) {
  const m = line.match(/^([\w$]+)\.([\w$]+)\s*(?:→|->)\s*([\w$]+)\.([\w$]+)$/);
  if (!m) throw new Error(`看不懂的关系：${line}`);
  return { fromTable: m[1], fromColumn: m[2], toTable: m[3], toColumn: m[4], text: line };
}

function parsePair(line) {
  const m = line.match(/^([\w$]+)\s*(?:→|->)\s*([\w$]+)$/);
  if (!m) throw new Error(`看不懂的对象对：${line}`);
  return { fromTable: m[1], toTable: m[2], text: line };
}

const relationKey = (r) => `${r.fromTable}.${r.fromColumn}→${r.toTable}.${r.toColumn}`.toLowerCase();
const pairKey = (r) => `${r.fromTable}→${r.toTable}`.toLowerCase();

function loadExpected() {
  const file = process.env.DG_EXPECTED_FILE;
  const raw = file ? JSON.parse(readFileSync(file, 'utf8')) : DEFAULT_EXPECTED;
  const list = Array.isArray(raw) ? raw : raw.systems;
  return list.map((item) => ({
    system: [item.system].flat(),
    objects: item.objects,
    relations: linesOf(item.relations).map(parseRelation),
    rejected: linesOf(item.rejected).map(parseRelation),
    pairs: linesOf(item.pairs).map(parsePair),
    recordPairs: linesOf(item.recordPairs).map(parsePair),
  }));
}

// ---------------------------------------------------------------- 文字扫描

const LATIN = /^[A-Za-z0-9_ ]+$/;
const escape = (word) => word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** 命中的词：拉丁词按完整单词、不分大小写，其余按子串。 */
const wordsIn = (text, words) =>
  words.filter((word) =>
    LATIN.test(word) ? new RegExp(`(?<![A-Za-z0-9_])${escape(word)}(?![A-Za-z0-9_])`, 'i').test(text) : text.includes(word),
  );

/** 一个系统的物理标识符（小写）：全部表名 + 长度 ≥ 4 的列名（验收 3 的口径）。 */
function identifiersOf(graph, details) {
  const out = new Set(graph.tables.map((table) => table.name.toLowerCase()));
  for (const detail of details.values()) {
    for (const field of detail.fields) if (field.name.length >= 4) out.add(field.name.toLowerCase());
  }
  for (const relation of graph.relations) {
    for (const column of [relation.fromColumn, relation.toColumn, relation.discriminatorColumn]) {
      if (column && column.length >= 4) out.add(column.toLowerCase());
    }
  }
  return out;
}

/** 按完整单词、不分大小写找物理标识符，去重。 */
const codesIn = (text, identifiers) => [
  ...new Set([...String(text).matchAll(/[A-Za-z0-9_$]+/g)].map((m) => m[0]).filter((token) => identifiers.has(token.toLowerCase()))),
];

// ---------------------------------------------------------------- 浏览器

const r = reporter('data-graph-live');
const { browser, page } = await launchBrowser();
const errors = [];
page.on('pageerror', (error) => errors.push(String(error)));
page.on('console', (message) => {
  // 浏览器自己去要的 /favicon.ico（开发服务没有这个文件）不算页面的错。
  if (message.type() === 'error' && !(message.location()?.url ?? '').endsWith('/favicon.ico')) errors.push(message.text());
});

/** 用页面上登录好的会话直接调接口：Authorization 放原始 token，外加 X-Tenant-Id。 */
async function api(path, params = {}) {
  const { status, body } = await page.evaluate(
    async ({ path, params }) => {
      const raw = localStorage.getItem('jm-agent-auth') ?? sessionStorage.getItem('jm-agent-auth');
      const auth = raw ? (JSON.parse(raw).state ?? {}) : {};
      const url = new URL(`/data${path}`, location.origin);
      Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, value));
      const response = await fetch(url, {
        headers: { Authorization: auth.token ?? '', 'X-Tenant-Id': auth.tenantId ?? '' },
      });
      return { status: response.status, body: await response.json().catch(() => null) };
    },
    { path, params },
  );
  if (status !== 200 || String(body?.respCode) !== '200') {
    throw new Error(`${path} → HTTP ${status} ${body?.respCode ?? ''} ${body?.respMsg ?? ''}`);
  }
  return body.data;
}

/** 等视口（自动缩放 / 居中 / 显示全图）连续两次读数不变。 */
async function settle() {
  let last = '';
  for (let i = 0; i < 40; i += 1) {
    const now = await page.$eval('.react-flow__viewport', (el) => el.style.transform).catch(() => '');
    if (now && now === last) return;
    last = now;
    await page.waitForTimeout(100);
  }
}

/** 等画布画完：卡片数、线数到位，视口停稳。等不到返回 false。 */
async function waitForGraph(cards, edges) {
  const drawn = await page
    .waitForFunction(
      ([c, e]) =>
        document.querySelectorAll('[data-testid="dg-card"]').length === c &&
        document.querySelectorAll('.react-flow__edge').length === e,
      [cards, edges],
      { timeout: 30_000 },
    )
    .then(() => true)
    .catch(() => false);
  if (drawn) await settle();
  return drawn;
}

const positions = () =>
  page.$$eval('.react-flow__node', (nodes) => Object.fromEntries(nodes.map((n) => [n.getAttribute('data-id'), n.style.transform])));

/** 默认视图「看得到、听得到」的全部文字：页面可见文字 + 读屏标签 + 悬停提示。折叠的技术信息不在可见文字里。 */
async function visibleText() {
  const text = await page.locator('[data-testid="data-graph-page"]').innerText();
  const spoken = await page.$$eval(
    '[data-testid="data-graph-page"] [aria-label], [data-testid="data-graph-page"] [title]',
    (els) => els.map((el) => `${el.getAttribute('aria-label') ?? ''}\n${el.getAttribute('title') ?? ''}`).join('\n'),
  );
  return `${text}\n${spoken}`;
}

const chromeText = () => page.$$eval(CHROME, (els) => els.map((el) => el.innerText).join('\n'));

/** 等右侧详情换成这个对象（标题对上）。 */
const waitDetail = (title) =>
  page
    .waitForFunction((want) => document.querySelector('[data-testid="dg-detail"] .dg-detail__title')?.textContent === want, title, {
      timeout: 10_000,
    })
    .then(() => true)
    .catch(() => false);

async function checkSystem(spec, summary) {
  const id = summary.connectorId;
  const tag = summary.displayName || summary.name;
  const graph = await api(`/admin/data-graph/systems/${encodeURIComponent(id)}`);
  const details = new Map();
  for (const table of graph.tables) {
    details.set(table.name, await api(`/admin/data-graph/systems/${encodeURIComponent(id)}/tables`, { name: table.name }));
  }
  const ids = identifiersOf(graph, details);
  const byName = new Map(graph.tables.map((table) => [table.name.toLowerCase(), table]));
  const titleOf = (name) => {
    const table = byName.get(name.toLowerCase());
    return table ? (table.displayName ?? table.name) : name;
  };
  const related = graph.tables.filter((table) => table.related);
  const isolated = graph.tables.filter((table) => !table.related);
  const apiRelations = new Set(graph.relations.map(relationKey));
  const apiPairs = new Map();
  for (const relation of graph.relations) {
    const key = `${relation.fromTable}→${relation.toTable}`;
    apiPairs.set(key, [...(apiPairs.get(key) ?? []), relation]);
  }

  // ======================================================== 接口
  r.ok(`前提（${tag}）：补全链已跑完`, summary.viewStatus === 'READY', `viewStatus=${summary.viewStatus}`);

  const notBusiness = graph.tables.filter((table) => table.nameSource !== 'BUSINESS_VIEW');
  r.ok(
    `【验收2】${tag}：${spec.objects} 个对象，标题全部来自业务视图`,
    graph.tables.length === spec.objects && notBusiness.length === 0,
    `对象 ${graph.tables.length}；没拿到业务名：${notBusiness.map((t) => `${t.name}(${t.nameSource})`).join('、') || '无'}`,
  );

  const rejected = new Set(spec.rejected.map(relationKey));
  const wanted = spec.relations.filter((relation) => !rejected.has(relationKey(relation)));
  const missing = wanted.filter((relation) => !apiRelations.has(relationKey(relation)));
  r.ok(
    `【验收1】${tag}：预期的 ${wanted.length} 条关系都在星图上（否掉的 ${spec.rejected.length} 条除外）`,
    missing.length === 0,
    missing.map((relation) => relation.text).join('；'),
  );
  for (const relation of spec.rejected) {
    console.log(`INFO  ${tag}：否掉的 ${relation.text} ${apiRelations.has(relationKey(relation)) ? '仍在星图上' : '不在星图上'}`);
  }
  const confirmed = graph.relations.filter((relation) => relation.tier === 'CONFIRMED').length;
  console.log(`INFO  ${tag}：星图上共 ${graph.relations.length} 条关系（已核对 ${confirmed}、待核对 ${graph.relations.length - confirmed}）`);
  const loose = graph.relations.filter((relation) => relation.cardinality == null && relation.tier !== 'CONFIRMED');
  r.ok(
    `【验收1】${tag}：终点不是唯一键的关系都是已核对的`,
    loose.length === 0,
    loose.map((relation) => `${relation.fromTable}.${relation.fromColumn} → ${relation.toTable}.${relation.toColumn}`).join('；'),
  );

  const texts = [];
  for (const table of graph.tables) {
    texts.push([table.name, '名称', table.displayName], [table.name, '说明', table.summary], [table.name, '领域', table.domain]);
    for (const ref of table.selfReferences) texts.push([`${table.name}.${ref.fromColumn}`, '角色', ref.role]);
  }
  for (const relation of graph.relations) texts.push([`${relation.fromTable}.${relation.fromColumn}`, '角色', relation.role]);
  const dirty = texts
    .filter(([, , text]) => text)
    .map(([where, kind, text]) => [where, kind, text, [...wordsIn(text, BUSINESS_FORBIDDEN), ...codesIn(text, ids)]])
    .filter(([, , , hits]) => hits.length > 0);
  r.ok(
    `【验收6】${tag}：业务文字（名称、说明、领域、角色名）没有禁用词、没有表名字段名`,
    dirty.length === 0,
    dirty.slice(0, 10).map(([where, kind, text, hits]) => `${where} ${kind}「${text}」→ ${hits.join('、')}`).join('；'),
  );

  // ======================================================== 页面：默认视图
  const url = `${BASE}/console/data-graph?system=${encodeURIComponent(id)}`;
  await page.goto(url);
  await page.locator('[data-testid="data-graph-page"]').waitFor({ state: 'visible', timeout: 15_000 });
  if (!graph.relations.length) {
    r.ok(`${tag}：没有关系时画布区显示空状态`, await page.locator('[data-testid="dg-empty"]').isVisible());
    return;
  }
  const drawn = await waitForGraph(related.length, apiPairs.size);
  r.ok(`${tag}：画布画完（${related.length} 个对象、${apiPairs.size} 根线）`, drawn);
  if (!drawn) return;
  await page.screenshot({ path: shot(`data-graph-live-${summary.name}.png`), fullPage: true });

  const lines = new Set(await page.$$eval('.react-flow__edge', (els) => els.map((el) => el.getAttribute('data-id'))));
  const nameIn = (name) => byName.get(name.toLowerCase())?.name ?? name;
  const lineOf = (pair) => `${nameIn(pair.fromTable)}→${nameIn(pair.toTable)}`;
  const wantedPairs = new Map();
  for (const pair of [...wanted, ...spec.pairs]) wantedPairs.set(pairKey(pair), pair);
  const noLine = [...wantedPairs.values()]
    .filter((pair) => !lines.has(lineOf(pair)))
    .map(
      (pair) =>
        `「${titleOf(pair.fromTable)}」→「${titleOf(pair.toTable)}」（${pair.fromTable} → ${pair.toTable}，${apiPairs.has(lineOf(pair)) ? '接口里有' : '接口里也没有'}）`,
    );
  r.ok(`【验收1】${tag}：每对预期的对象之间至少一根线（${wantedPairs.size} 对）`, noLine.length === 0, noLine.join('；'));
  for (const pair of spec.recordPairs) {
    console.log(`INFO  ${tag} 记录项：「${titleOf(pair.fromTable)}」→「${titleOf(pair.toTable)}」${lines.has(lineOf(pair)) ? '画了线' : '没有线'}`);
  }

  const labels = await page.$$eval('[data-testid="dg-edge-label"]', (els) =>
    Object.fromEntries(els.map((el) => [el.getAttribute('data-edge'), el.textContent])),
  );
  const wrongLabels = [...apiPairs]
    .map(([pair, relations]) => [pair, labels[pair] ?? null, relations.length > 1 ? `${relations.length} 种关联` : (relations[0].role ?? null)])
    .filter(([, shown, want]) => shown !== want);
  r.ok(
    `${tag}：线上的字对得上（多条写「N 种关联」，一条写角色名）`,
    wrongLabels.length === 0,
    wrongLabels.map(([pair, shown, want]) => `${pair}：${shown} ≠ ${want}`).join('；'),
  );

  const cardTitles = await page.$$eval('[data-testid="dg-card"]', (els) =>
    Object.fromEntries(els.map((el) => [el.getAttribute('data-table'), el.querySelector('.dg-card__title')?.textContent])),
  );
  const wrongCards = related.filter((table) => cardTitles[table.name] !== table.displayName);
  r.ok(`【验收2】${tag}：卡片标题就是业务名`, wrongCards.length === 0, wrongCards.map((t) => `${t.name}: ${cardTitles[t.name]}`).join('；'));

  const overview = (await page.locator('.data-graph-summary').innerText()).replace(/\s+/g, ' ');
  r.ok(
    `${tag}：概览数字与接口一致`,
    overview.includes(`对象 ${graph.tables.length}`) &&
      overview.includes(`已核对的关联 ${confirmed}`) &&
      overview.includes(`待核对的关联 ${graph.relations.length - confirmed}`) &&
      overview.includes(`暂未发现关联的对象 ${isolated.length}`),
    overview,
  );
  r.ok(`${tag}：补全链跑完、名字齐了，不挂「业务名称整理中」`, (await page.locator('[data-testid="dg-hint-naming"]').count()) === 0);

  const defaultCodes = codesIn(await visibleText(), ids);
  r.ok(`★【验收3】${tag}：默认视图（不选对象）没有表名、字段名`, defaultCodes.length === 0, defaultCodes.join(','));
  await page.getByRole('tab', { name: /暂未发现关联的对象/ }).click();
  const isolatedCodes = codesIn(await visibleText(), ids);
  const isolatedTitles = isolated.length
    ? await page.$$eval('[data-testid="dg-isolated-list"] .dg-table-list__title', (els) => els.map((el) => el.textContent))
    : [];
  r.ok(
    `【验收2/3】${tag}：「暂未发现关联的对象」列的是业务名，没有表名、字段名`,
    isolatedCodes.length === 0 && JSON.stringify(isolatedTitles) === JSON.stringify(isolated.map((t) => t.displayName)),
    `${isolatedCodes.join(',')} ${isolatedTitles.join('、')}`,
  );
  await page.getByRole('tab', { name: /关联清单/ }).click();
  const leakedWords = wordsIn(await chromeText(), UI_FORBIDDEN);
  r.ok(`【验收7】${tag}：界面固定文案没有运维 / AI 用语`, leakedWords.length === 0, leakedWords.join(','));

  // ======================================================== 页面：布局确定
  const first = await positions();
  let stable = true;
  for (let i = 0; i < 9; i += 1) {
    await page.goto(url);
    stable = (await waitForGraph(related.length, apiPairs.size)) && stable && JSON.stringify(await positions()) === JSON.stringify(first);
  }
  r.ok(`【验收7】${tag}：同一份数据刷新 10 次，坐标完全一致`, stable);

  // ======================================================== 页面：逐个打开对象详情
  // 先「显示全图」，让每张卡片都在画布里；被小地图、提示条挡住的卡片退回直接派发点击。
  await page.locator('.react-flow__controls-fitview').click();
  await settle();
  const errorsBefore = errors.length;
  const leaks = [];
  const wrongDetails = [];
  let moved = false;
  const inspect = async (table) => {
    if (!(await waitDetail(table.displayName ?? table.name))) {
      wrongDetails.push(table.name);
      return;
    }
    const codes = codesIn(await visibleText(), ids);
    if (codes.length) leaks.push(`${table.name}: ${codes.join(',')}`);
  };
  for (const table of related) {
    const card = page.locator(`[data-testid="dg-card"][data-table="${table.name}"]`);
    await card.click({ timeout: 3_000 }).catch(() => card.dispatchEvent('click'));
    await inspect(table);
    moved = moved || JSON.stringify(await positions()) !== JSON.stringify(first);
  }
  if (isolated.length) {
    await page.locator('.dg-detail__back').click();
    await page.getByRole('tab', { name: /暂未发现关联的对象/ }).click();
    for (let i = 0; i < isolated.length; i += 1) {
      await page.locator('[data-testid="dg-isolated-list"] li').nth(i).locator('button').click();
      await inspect(isolated[i]);
      await page.locator('.dg-detail__back').click();
    }
    await page.getByRole('tab', { name: /关联清单/ }).click();
  }
  r.ok(`【验收2】${tag}：逐个打开 ${graph.tables.length} 个对象，详情标题都是业务名`, wrongDetails.length === 0, wrongDetails.join('、'));
  r.ok(`★【验收3】${tag}：每个对象的详情（技术信息折叠）没有表名、字段名`, leaks.length === 0, leaks.slice(0, 10).join('；'));
  r.ok(`【验收7】${tag}：点卡片时卡片不动`, !moved);
  r.ok(`【验收7】${tag}：逐个打开对象期间控制台无报错`, errors.length === errorsBefore, errors.slice(errorsBefore).join(' | '));
  const sample = related[0] ?? isolated[0];
  const card = page.locator(`[data-testid="dg-card"][data-table="${sample.name}"]`);
  await card.click({ timeout: 3_000 }).catch(() => card.dispatchEvent('click'));
  await waitDetail(sample.displayName ?? sample.name);
  await page.locator('.dg-tech > summary').click();
  const tech = await page.locator('.dg-tech').innerText();
  r.ok(`${tag}：展开技术信息才看得到表名`, tech.includes(sample.name), tech.slice(0, 120));
}

try {
  await page.goto(`${BASE}/login`);
  await page.fill('#username', USERNAME);
  await page.fill('#password', PASSWORD);
  await page.locator('.login-submit').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15_000 });

  const expected = loadExpected();
  const systems = await api('/admin/data-graph/systems');
  let objects = 0;
  for (const spec of expected) {
    const summary = systems.find((s) => spec.system.includes(s.name) || spec.system.includes(s.displayName));
    if (!summary) {
      r.ok(`找到系统 ${spec.system.join(' / ')}`, false, `租户里的系统：${systems.map((s) => `${s.name}(${s.displayName})`).join('、')}`);
      continue;
    }
    try {
      await checkSystem(spec, summary);
      objects += spec.objects;
    } catch (error) {
      r.ok(`${summary.displayName || summary.name}：检查过程没出错`, false, String(error));
    }
  }
  console.log(`INFO  共检查 ${objects} 个对象（验收 2 要求两个系统合计 27 个）`);
  r.ok('【验收7】全程控制台无报错', errors.length === 0, errors.slice(0, 5).join(' | '));
} finally {
  await browser.close();
}
process.exit(r.summary() ? 0 : 1);
```

```bash
cd $FE && source ~/.nvm/nvm.sh && nvm use 20 >/dev/null && \
  (lsof -nP -iTCP:5181 -sTCP:LISTEN >/dev/null || (nohup npx vite --port 5181 --strictPort > /tmp/jm-front-5181.log 2>&1 &)); \
  for i in $(seq 1 30); do curl -s -o /dev/null http://localhost:5181/ && break; sleep 1; done; \
  ps -o command= -p "$(lsof -t -nP -iTCP:5181 -sTCP:LISTEN | head -1)"; \
  node e2e/data-graph-live.local.mjs; echo exit=$?
```
Expected：`ps` 那一行是 `$FE` 下的 `node_modules/.bin/vite --port 5181 --strictPort`（不是的话，5181 被别的项目占着：停掉它再跑）；最后一行 `=== data-graph-live: 41/41 passed ===`，`exit=0`；倒数第三行附近有 `INFO  共检查 27 个对象`。若第一条 `前提（klny_erp）：补全链已跑完` 是 FAIL、`viewStatus=FAILED`，按 Step 2 末尾的办法让那条连接重跑，等 READY 之后再跑一遍。

- [ ] **Step 7: 清理**

```bash
rm $DS/.superpowers/sdd/data_graph_acceptance.py $FE/e2e/data-graph-live.local.mjs; git -C $DS status --short; git -C $FE status --short
```
Expected：两个 `git status` 都没有输出。本地 data-server 保持运行（v3 构建、对账间隔 30 秒），5181 上的前端 dev 也留着：告诉用户本地后端现在是 v3，以后要恢复默认对账间隔就去掉启动参数重启。

---
