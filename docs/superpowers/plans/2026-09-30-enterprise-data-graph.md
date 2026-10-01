# 数据星图 v2 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把数据星图重做成语义层表关系的只读客户视图：后端每次请求从结构快照和语义层 JOIN 行现算，前端用表卡片加确定性分层布局展示表与表的关系。

**Architecture:** data-service 新增 `ai/connector/graph/`：纯函数投影 `DataGraphProjector`、读服务 `DataGraphService`、超管接口 `DataGraphController`。不落库、不排队、不调模型。顺带把表注释末尾「约 N 行」这段后缀收成唯一来源 `RowEstimateNote`。jm-agent-front 新增 `features/data-graph/`（dagre 分层布局 + React Flow 画布 + 深色右侧面板）和 `/console/data-graph` 页面。

**Tech Stack:** Java 17 / Spring Boot 3.0.2 / MyBatis-Plus / Lombok（运行时 jackson-databind 2.11）；React 18 / TypeScript 5.6 / antd 5 / @tanstack/react-query 5 / `@xyflow/react@12.12.0` / `@dagrejs/dagre@3.1.1`；Playwright（`e2e/` 独立依赖）。

**Spec:** `data-service/docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md`（分支 `feat/data-graph-v2`，提交 `7fc0e05` 与 `e7f843c`）

> 本计划里的全部代码都在两个 worktree 里实跑验证过（2026-09-30），验证完已把 worktree 还原干净，现在按任务重新写入并逐个提交。验证结果如下：
> - 后端：星图相关测试 37 个（RowEstimateNote 3、投影 25、性能 1、服务 6、控制器 2），被改文件原有的测试全部通过；data-server 全量测试 2155 个，0 失败（2 个跳过，原本就是跳过的）；200 张表的投影 P95 为 7 ms。
> - 前端：typecheck、lint 通过；端到端夹具检查 29/29，连跑多次都稳定；颜色对比度 25 对全部达到 WCAG AA。

## 工作区（先读）

- 后端：`/Users/hukui/Desktop/workspace/jm/.worktrees/data-service-data-graph-v2`，分支 `feat/data-graph-v2`，已含 spec 两个提交。下文记作 `$DS`。
- 前端：`/Users/hukui/Desktop/workspace/jm/.worktrees/jm-agent-front-data-graph-v2`，分支 `feat/data-graph-v2`，基于 main `cbc1c7b`。`node_modules` 和 `e2e/node_modules` 已装好。下文记作 `$FE`。
- **不要在 `jm/` 根目录跑 git**：那里的 git 指向 home 目录下一个无关的仓库。一律 `cd` 进上面两个 worktree，或者用 `git -C`。
- 旧实现分支 `codex/enterprise-data-graph`（两个仓库各一个，对应 `.worktrees/*-enterprise-data-graph`）不合并、不改动。
- 每个 Bash 调用开头先设好变量：`DS=/Users/hukui/Desktop/workspace/jm/.worktrees/data-service-data-graph-v2; FE=/Users/hukui/Desktop/workspace/jm/.worktrees/jm-agent-front-data-graph-v2`。

## Global Constraints

- 出网对象一律用 Lombok `@Value` 静态类：项目运行时的 jackson-databind 是 2.11，不认 record（上一版在提交 `0ca5d72` 踩过）。
- 后端测试只放在 `modules/data-server`。跑单测用 `mvn -o -q -pl modules/data-server test -Dtest='类名*' -Dsurefire.failIfNoSpecifiedTests=false`，**类名后面的 `*` 不能省**，否则 `@Nested` 里的用例不会跑。`@Nested` 的用例只记在 `target/surefire-reports/TEST-*.xml` 里，`.txt` 汇总的 Tests run 不含它们；用例数和失败数用 `grep -c '<testcase'` 和 `grep -c '<failure\|<error'` 数 xml。
- 前端用 Node 20（`source ~/.nvm/nvm.sh && nvm use 20`）。通过标准是 `npm run typecheck && npm run lint`（`--max-warnings 0`）。前端没有单测框架，行为用 `npm run test:data-graph`（Playwright 夹具脚本）验证。
- 只新增两个依赖，锁定版本：`@xyflow/react@12.12.0`、`@dagrejs/dagre@3.1.1`。
- 接口路径：`GET /data/admin/data-graph/systems`、`/systems/{connectorId}`、`/systems/{connectorId}/tables?name=`。前端的 `get()` 会自动加 `/data` 前缀，所以代码里写 `/admin/data-graph/...`。
- 「仅超管可用」三处都要有：后端 `superAdminGuard.requireSuperAdmin()`、路由 `SuperAdminRoute`、导航 `superAdminOnly: true`。
- 界面文案（表名、字段名、注释等客户数据除外）不得出现：`READY` / `RUNNING` / `FAILED` 等枚举原文、`INSPECTOR`、`UNKNOWN`、置信度或百分比、「AI 推测」「AI 可信度」、语义层的 gloss / 依据 / 核对说明原文。
- 关系句式、空状态文案、图例文案逐字照本计划写，e2e 按原文逐字断言。
- 不涉及 DDL、Nacos 变更或新配置项。
- 提交信息用中文，末尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。**不要 push**：推送需要用户另行同意，而且两个仓库要串行推（先 data-service 后前端，它们共用一台 runner）。

## Review Focus

1. **旧快照没有 `extra.unique_keys`（唯一键未知）**：推断关系一条都不画，已确认的照常画，页面不报错。生产里的存量快照在下次定时刷新结构之前都是这种状态。由 Task 2 的 `DataGraphProjectorTest` → `Edges#旧快照` 覆盖。
2. **中文、带空格的表名和列名（ERP 里常见）**：照常判定，id 稳定，单表详情能按原名取到，画布照常连线、照常成句。由 Task 2 `#中文名`、Task 3 `#表名中间空格`、Task 5 的 e2e「中文带空格的表名照常成句」覆盖。
3. **同一对表之间有多条关系（列不同）**：每条单独保留，各自连到自己的字段行。由 Task 2 `#同一对表多条关系`、Task 5 的 e2e「5 条关系线」覆盖。
4. **别的租户的 connectorId**：返回「系统不存在」，不泄漏任何表结构。星图没有自己的租户逻辑，完全依赖三张源表在租户白名单里。由 Task 3 `#三张源表都按租户过滤` 和 `#系统不存在` 覆盖。
5. **某一行的 `detail_json` 损坏**：只丢这一行，整张图照常显示。由 Task 2 `#坏行` 覆盖。

---

### Task 1: 估算行数后缀改为唯一来源（data-service）

表注释末尾「（约 N 行，InnoDB 估算值，不可当作准确计数）」这段是平台加的，不是客户写的。现在写入方（`MySqlSession`）和剥离方（`RuleContext` 里的私有正则）各有一份文案，改了一边另一边会悄悄失效。星图需要拿到客户原始的表注释，所以先把它收成一处（spec §5.4）。

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/model/RowEstimateNote.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/model/RowEstimateNoteTest.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlSession.java`（第 11 行 import 段；约 988–996 行）
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/generation/consistency/RuleContext.java`（import 段；删除常量 `ROW_ESTIMATE_SUFFIX`；`objectCommentOf`）

**Interfaces:**
- Produces:
  - `RowEstimateNote.append(String comment, Long rows): String`：注释和行数都没有时返回 `null`；
  - `RowEstimateNote.strip(String comment): String`：去掉后缀并 trim，剩下为空时返回 `null`。
  - Task 2 用到 `strip`。

- [ ] **Step 1: 写失败的测试**

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/model/RowEstimateNoteTest.java`：

```java
package com.jimeng.dataserver.ai.connector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RowEstimateNoteTest {

    @Test
    @DisplayName("追加：行数未知或为 0 不写；注释为空时只剩后缀；两样都没有是 null")
    void 追加() {
        assertEquals("订单表（约 5 行，InnoDB 估算值，不可当作准确计数）", RowEstimateNote.append("订单表", 5L));
        assertEquals("订单表", RowEstimateNote.append("订单表", null));
        assertEquals("订单表", RowEstimateNote.append("订单表", 0L));
        assertEquals("（约 7 行，InnoDB 估算值，不可当作准确计数）", RowEstimateNote.append("  ", 7L));
        assertNull(RowEstimateNote.append(null, null));
        assertNull(RowEstimateNote.append("", 0L));
    }

    @Test
    @DisplayName("★ 往返：strip(append(c, n)) == c —— 写入方和剥离方永远说同一句话")
    void 往返() {
        for (String c : List.of("订单表", "GB5D 公司代码", "带（括号）的注释", "客户-财务视图明细")) {
            for (Long n : Arrays.asList(null, 0L, 1L, 123456L)) {
                assertEquals(c, RowEstimateNote.strip(RowEstimateNote.append(c, n)), c + " / " + n);
            }
        }
        assertNull(RowEstimateNote.strip(RowEstimateNote.append(null, 7L)), "只有后缀 = 客户没写注释");
    }

    @Test
    @DisplayName("剥离：认旧措辞；只剥末尾；空白与 null 都是 null")
    void 剥离() {
        assertEquals("订单表", RowEstimateNote.strip("订单表（约 8371 行，InnoDB 估算值）"));
        assertEquals("（约 5 行，InnoDB 估算值，不可当作准确计数）备注",
                RowEstimateNote.strip("（约 5 行，InnoDB 估算值，不可当作准确计数）备注"));
        assertNull(RowEstimateNote.strip("   "));
        assertNull(RowEstimateNote.strip(null));
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='RowEstimateNoteTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报 `cannot find symbol ... RowEstimateNote`。

- [ ] **Step 3: 写实现**

`modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/model/RowEstimateNote.java`：

```java
package com.jimeng.dataserver.ai.connector.model;

import java.util.regex.Pattern;

/**
 * 表注释末尾那段平台追加的估算行数：「（约 N 行，InnoDB 估算值，不可当作准确计数）」。
 *
 * <p>这段是<b>平台</b>写的，不是客户的注释。写入方（MySQL 连接器列目录）和剥离方（语义层一致性规则、数据星图）
 * 从前各写一份文案，改一边另一边静默失效；现在只在这里。放在 model 包：语义层不该依赖某一个连接器实现，
 * 两边都只依赖这个值对象级的小工具。
 *
 * <p>{@link #strip} 认「估算值」之后到右括号的任意内容，所以已落库的旧措辞（如「（约 5 行，InnoDB 估算值）」）
 * 不必重拉快照也能去掉。
 */
public final class RowEstimateNote {

    private static final Pattern SUFFIX = Pattern.compile("\\s*（约\\s*\\d+\\s*行，InnoDB\\s*估算值[^）]*）\\s*$");

    private RowEstimateNote() {
    }

    /**
     * 在注释后追加估算行数。行数未知（{@code null}）和 0 都不写：前者没数可写，后者可能只是没统计过，
     * 写「约 0 行」会让模型断言这张表是空的。
     *
     * @return 既没有注释也不写行数时为 {@code null}
     */
    public static String append(String comment, Long rows) {
        String base = comment == null || comment.isBlank() ? "" : comment;
        String withRows = base + (rows != null && rows > 0
                ? "（约 " + rows + " 行，InnoDB 估算值，不可当作准确计数）" : "");
        return withRows.isBlank() ? null : withRows;
    }

    /** 去掉末尾的估算行数并 trim；剩下为空（客户根本没写注释）返回 {@code null}。 */
    public static String strip(String comment) {
        if (comment == null) {
            return null;
        }
        String stripped = SUFFIX.matcher(comment).replaceFirst("").trim();
        return stripped.isEmpty() ? null : stripped;
    }
}
```

- [ ] **Step 4: 跑测试，确认通过**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='RowEstimateNoteTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  f=modules/data-server/target/surefire-reports/TEST-com.jimeng.dataserver.ai.connector.model.RowEstimateNoteTest.xml; \
  echo "cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"
```
Expected: `cases=3 failures=0`

- [ ] **Step 5: `MySqlSession` 改用 `RowEstimateNote.append`**

在 import 段 `import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;` 的下一行加：

```java
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
```

把原来这段（约 991–996 行）：

```java
                    // 写「约 0 行」会让模型断言这张表是空的。
                    String withRows = (comment == null || comment.isBlank() ? "" : comment)
                            + (rows != null && rows > 0
                            ? "（约 " + rows + " 行，InnoDB 估算值，不可当作准确计数）" : "");
                    CatalogEntry entry = new CatalogEntry(rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_TYPE"), withRows.isBlank() ? null : withRows);
```

替换为：

```java
                    // 写「约 0 行」会让模型断言这张表是空的。文案与剥离规则只在 RowEstimateNote 一处。
                    CatalogEntry entry = new CatalogEntry(rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_TYPE"), RowEstimateNote.append(comment, rows));
```

行为不变：注释为空或全是空白时，只剩后缀；行数为 `null` 或 0 时不写后缀；两样都没有时是 `null`。

- [ ] **Step 6: `RuleContext` 改用 `RowEstimateNote.strip`**

import 段改成（加 `RowEstimateNote`，删 `java.util.regex.Pattern`）：

```java
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
```

删掉整段常量及它的 Javadoc：

```java
    /**
     * MySQL 连接器把 {@code TABLE_ROWS} 估算值追加在表注释末尾（{@code MySqlSession} 列目录那一段，
     * 原文「（约 N 行，InnoDB 估算值，不可当作准确计数）」）。这段是<b>平台</b>写的，不是客户的注释：
     * 不去掉它，一张没写注释的表会被当成「有注释」，evidence=COMMENT 的判定就失效了。
     * 这里不 import 连接器实现（语义层不该依赖某一个连接器），按文案形状匹配，改那边的措辞要同步这里。
     */
    private static final Pattern ROW_ESTIMATE_SUFFIX = Pattern.compile("\\s*（约\\s*\\d+\\s*行，InnoDB\\s*估算值[^）]*）\\s*$");

```

把 `objectCommentOf` 从：

```java
    /** 表注释，去掉平台追加的估算行数；没有注释为 {@code null}。 */
    public String objectCommentOf(String object) {
        String c = object == null ? null : objectComments.get(object);
        if (c == null) {
            return null;
        }
        String stripped = ROW_ESTIMATE_SUFFIX.matcher(c).replaceFirst("").trim();
        return stripped.isEmpty() ? null : stripped;
    }
```

改为：

```java
    /**
     * 表注释，去掉平台追加的估算行数（{@link RowEstimateNote}）；没有注释为 {@code null}。
     * 不去掉它，一张没写注释的表会被当成「有注释」，evidence=COMMENT 的判定就失效了。
     */
    public String objectCommentOf(String object) {
        return RowEstimateNote.strip(object == null ? null : objectComments.get(object));
    }
```

- [ ] **Step 7: 跑受影响类的既有测试**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='RowEstimateNoteTest*,CommentEvidenceRuleTest*,ConnectorSchemaServiceTest*,MySqlSession*Test*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 每个文件 `failures=0`；用例数分别为 CommentEvidenceRuleTest 3、ConnectorSchemaServiceTest 20、MySqlSessionRowPresenceTest 11、MySqlSessionWriteTest 15、RowEstimateNoteTest 3。

- [ ] **Step 8: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/model/RowEstimateNote.java \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/model/RowEstimateNoteTest.java \
  modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/impl/mysql/MySqlSession.java \
  modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/generation/consistency/RuleContext.java && \
git commit -q -F - <<'MSG'
refactor(connector): 表注释的估算行数后缀改为唯一来源

写入方 MySqlSession 与剥离方 RuleContext 从前各写一份文案，改一边另一边静默失效。
收进 model/RowEstimateNote（append / strip），往返测试钉住两边永远说同一句话；
数据星图要取客户原始表注释，也用这一处。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 2: 投影——关系判定、唯一键与基数、表名、排序（data-service）

星图的全部规则都放在一个纯函数里（spec §5.2–§5.5）：输入一条连接的结构快照和 JOIN 行，输出客户视图，不访问数据库，也不依赖 Spring。

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/generation/SemanticTableRenderer.java`（`namedUniqueKeys` 和 `NamedKey` 改为 public）
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphViews.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjector.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphFixtures.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorTest.java`

**Interfaces:**
- Consumes:
  - `RowEstimateNote.strip(String)`（Task 1）；
  - `SemanticRowAssembler.parseFields(List<ConnectorSchema>): Map<String, Map<String, FieldDetail>>`（已有）；
  - `SemanticTableRenderer.namedUniqueKeys(ConnectorSchema): List<NamedKey>`（本任务改为 public，`null` 表示未知）；
  - `ConnectorSemanticService` 的常量 `KEY_TO_OBJECT`、`KEY_TO_COLUMN`、`KEY_HUMAN_VERDICT`、`HV_RELATED`、`HV_UNRELATED`、`ST_STALE`、`V_CONFIRMED`、`V_REJECTED`、`V_WEAK`；
  - `SemanticJoinValidator.KIND_POLYMORPHIC`。
- Produces（Task 3 会用到）：
  - `DataGraphProjector.system(Connection, List<ConnectorSchema>, List<ConnectorSemantic>): SystemGraph`；
  - `DataGraphProjector.table(List<ConnectorSchema>, List<ConnectorSemantic>, String name): TableDetail`：表不存在时返回 `null`；
  - `DataGraphProjector.objectType(String raw): String`：返回 `TABLE`、`VIEW` 或 `null`；
  - `DataGraphProjector.looksLikeName(String): boolean`；
  - `DataGraphViews` 下的 `SystemSummary`、`SystemGraph`、`TableCard`、`ColumnRef`、`SelfReference`、`Relation`、`Field`、`TableDetail`（字段见下方代码，前端 `types.ts` 与之一一对应）；
  - 测试夹具 `DataGraphFixtures`（Task 3 复用）。

- [ ] **Step 1: 把 `SemanticTableRenderer.namedUniqueKeys` 和 `NamedKey` 改为 public**

把：

```java
     * 在文案里却列出一个键。
     *
     * @return {@code null} = 不知道
     */
    @SuppressWarnings("unchecked")
    private static List<NamedKey> namedUniqueKeys(ConnectorSchema row) {
```

改为：

```java
     * 在文案里却列出一个键。数据星图判「终点列是不是唯一键」、标主键也用这一份，不另写解析。
     *
     * @return {@code null} = 不知道；主键排在最前
     */
    @SuppressWarnings("unchecked")
    public static List<NamedKey> namedUniqueKeys(ConnectorSchema row) {
```

再把文件末尾的：

```java
    private record NamedKey(String name, List<String> columns, boolean primary) {
    }
```

改为：

```java
    public record NamedKey(String name, List<String> columns, boolean primary) {
    }
```

- [ ] **Step 2: 写出网对象（纯数据类，没有逻辑）**

`modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphViews.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import lombok.Value;

import java.util.List;

/**
 * 数据星图的出网对象（设计文档 §5.1）。
 *
 * <p>项目实际用的 jackson-databind 是 2.11，不认 record，所以一律用 Lombok {@code @Value}。
 * 这里只放客户能看的东西：语义层的 gloss / 依据 / 置信度 / 判别值、租户、凭据<b>永不出网</b>。
 */
public final class DataGraphViews {

    private DataGraphViews() {
    }

    /** {@code /systems} 的一项。{@code semanticStatus} 只给前端判断空状态，不上屏。 */
    @Value
    public static class SystemSummary {
        String connectorId;
        String name;
        String displayName;
        String kind;
        String status;
        String semanticStatus;
        int tableCount;
    }

    /** {@code /systems/{connectorId}}：全部表卡片 + 通过判定的关系（不含自关联）。 */
    @Value
    public static class SystemGraph {
        String connectorId;
        String name;
        String displayName;
        String semanticStatus;
        List<TableCard> tables;
        List<Relation> relations;
    }

    @Value
    public static class ColumnRef {
        String name;
        String comment;
    }

    @Value
    public static class SelfReference {
        String fromColumn;
        String toColumn;
    }

    @Value
    public static class TableCard {
        String name;
        String displayName;
        String comment;
        String objectType;
        boolean related;
        List<SelfReference> selfReferences;
        List<ColumnRef> keyColumns;
        List<ColumnRef> relationColumns;
        int fieldCount;
    }

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
        String label;
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
        String comment;
        String objectType;
        List<SelfReference> selfReferences;
        List<Field> fields;
        List<Relation> relations;
    }
}
```

- [ ] **Step 3: 写测试夹具和失败的测试**

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphFixtures.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手工构造的结构快照与 JOIN 行：覆盖本地数据里出现过的每种形态（主键、非主键唯一键、组合键、唯一键未知、
 * 没有唯一键、说明句注释、空注释、自关联），但不含任何真实客户的表名。
 */
final class DataGraphFixtures {

    static final long CONNECTOR_ID = 7L;

    /** 与项目运行时同版本的普通 ObjectMapper（jackson 2.11，不认 record）。 */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private DataGraphFixtures() {
    }

    static Connection connection() {
        Connection c = new Connection();
        c.setId(CONNECTOR_ID);
        c.setName("erp");
        c.setDisplayName("ERP 系统");
        c.setKind("MYSQL");
        c.setStatus("ACTIVE");
        c.setSemanticStatus("READY");
        return c;
    }

    /**
     * 基础快照：订单（注释带估算行数）/ 客户（主键 + 唯一键 code）/ 明细 / 商品（唯一键未知）/
     * 扩展表（注释是说明句）/ 地区（无注释，可自关联）/ 日志（没有唯一键）/ 销售视图（importance_rank 为空）。
     */
    static List<ConnectorSchema> schemas() {
        return List.of(
                table("t_order", "订单表（约 5 行，InnoDB 估算值，不可当作准确计数）", 1, List.of(pk("id")),
                        "id|订单主键", "customer_id|下单客户", "customer_code", "shop_code", "status"),
                table("t_customer", "客户表", 2, List.of(pk("id"), uk("uk_code", "code")),
                        "id", "code|客户编码", "region_id"),
                table("t_item", "订单明细", 3, List.of(pk("id")), "id", "order_id|所属订单", "sku_id|SKU_ID"),
                table("t_sku", "商品", null, null, "id", "name"),
                table("t_ext", "扩展表，按 id 一对一拆出", 4, List.of(pk("id")), "id", "memo"),
                table("t_region", "", 5, List.of(pk("id")), "id", "parent_id|上级地区"),
                table("t_log", "操作日志", 6, List.of(), "order_id", "action"),
                view("v_sales", "销售汇总", "order_id", "amount"));
    }

    static Map<String, Object> pk(String... columns) {
        return Map.of("name", "PRIMARY", "columns", List.of(columns), "primary", true);
    }

    static Map<String, Object> uk(String name, String... columns) {
        return Map.of("name", name, "columns", List.of(columns), "primary", false);
    }

    /** {@code columns} 写「列名」或「列名|注释」；{@code keys} 为 {@code null} = 唯一键未知（旧快照没有 extra.unique_keys）。 */
    static ConnectorSchema table(String name, String comment, Integer rank, List<Map<String, Object>> keys,
                                 String... columns) {
        return object("BASE TABLE", name, comment, rank, keys, columns);
    }

    static ConnectorSchema view(String name, String comment, String... columns) {
        return object("VIEW", name, comment, null, List.of(), columns);
    }

    private static ConnectorSchema object(String type, String name, String comment, Integer rank,
                                          List<Map<String, Object>> keys, String... columns) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String column : columns) {
            String[] parts = column.split("\\|", 2);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", parts[0]);
            f.put("type", "bigint");
            f.put("nullable", true);
            f.put("comment", parts.length > 1 ? parts[1] : "");
            fields.add(f);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("type", type);
        detail.put("fields", fields);
        if (keys != null) {
            detail.put("extra", Map.of("unique_keys", keys));
        }
        ConnectorSchema row = new ConnectorSchema();
        row.setConnectorId(CONNECTOR_ID);
        row.setObjectType(type);
        row.setObjectName(name);
        row.setObjectComment(comment);
        row.setImportanceRank(rank);
        row.setDetailJson(json(detail));
        return row;
    }

    /** 推导出来、还没核对的 JOIN 行：{@code verified=NONE}、{@code status=DRAFT}。 */
    static ConnectorSemantic join(String from, String fromColumn, String to, String toColumn) {
        return join(from, fromColumn, to, toColumn, "NONE", Map.of());
    }

    static ConnectorSemantic join(String from, String fromColumn, String to, String toColumn, String verified,
                                  Map<String, Object> moreDetail) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to_object", to);
        detail.put("to_column", toColumn);
        detail.putAll(moreDetail);
        ConnectorSemantic row = new ConnectorSemantic();
        row.setConnectorId(CONNECTOR_ID);
        row.setScope("JOIN");
        row.setObjectName(from);
        row.setFieldName(fromColumn);
        row.setTerm("");
        row.setDetailJson(json(detail));
        row.setVerified(verified);
        row.setStatus("DRAFT");
        row.setSource("INFERRED");
        return row;
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorTest.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.ColumnRef;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Field;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Relation;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableCard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.MAPPER;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.json;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.schemas;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.uk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设计文档 §5.2–§5.5：关系判定、唯一键与基数、表名与注释、排序，以及「永不出网」。 */
class DataGraphProjectorTest {

    @Nested
    @DisplayName("§5.2 关系判定（按顺序，先命中者为准）")
    class Judgement {

        @Test
        @DisplayName("1. 两端的表或列不在当前快照里：丢弃")
        void 端点缺失() {
            SystemGraph g = system(
                    join("t_item", "no_such_col", "t_order", "id"),
                    join("t_ext", "id", "t_gone", "id"),
                    join("t_region", "parent_id", "t_customer", "no_such_col"));
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("2. status=STALE：丢弃，即使采样核对通过")
        void 过期() {
            ConnectorSemantic row = join("t_item", "order_id", "t_order", "id", "CONFIRMED", Map.of());
            row.setStatus("STALE");
            assertTrue(system(row).getRelations().isEmpty());
        }

        @Test
        @DisplayName("3/4. 业务方的判断压过采样：UNRELATED 压过 CONFIRMED，RELATED 压过 REJECTED")
        void 业务方优先() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id", "CONFIRMED", Map.of("human_verdict", "UNRELATED")),
                    join("t_order", "shop_code", "t_customer", "code", "REJECTED", Map.of("human_verdict", "RELATED")));
            assertEquals(List.of("t_order.shop_code→t_customer.code"), edges(g));
            Relation r = g.getRelations().get(0);
            assertEquals("CONFIRMED", r.getTier());
            assertEquals("BUSINESS", r.getConfirmedBy());
        }

        @Test
        @DisplayName("★ 业务方写入时 status 就变成 CONFIRMED：没有 human_verdict 的 status=CONFIRMED 不算已确认")
        void status不代表成立() {
            ConnectorSemantic row = join("t_order", "status", "t_customer", "region_id");
            row.setStatus("CONFIRMED");
            assertTrue(system(row).getRelations().isEmpty());
        }

        @Test
        @DisplayName("5. 采样核对通过：实线，来源 DATA")
        void 数据核对() {
            Relation r = system(join("t_item", "order_id", "t_order", "id", "CONFIRMED", Map.of()))
                    .getRelations().get(0);
            assertEquals("CONFIRMED", r.getTier());
            assertEquals("DATA", r.getConfirmedBy());
        }

        @Test
        @DisplayName("6. REJECTED、WEAK：丢弃，即使终点是主键")
        void 数据否定() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id", "REJECTED", Map.of()),
                    join("t_ext", "id", "t_order", "id", "WEAK", Map.of()));
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("7. 多态关系：未确认不画；确认后画出并带判别列，从不带判别值")
        void 多态() {
            Map<String, Object> poly = Map.of("join_kind", "POLYMORPHIC", "discriminator_column", "status",
                    "discriminator_value", "SECRET_VALUE");
            assertTrue(system(join("t_order", "customer_id", "t_customer", "id", "UNDECIDABLE", poly))
                    .getRelations().isEmpty());
            SystemGraph confirmed = system(join("t_order", "customer_id", "t_customer", "id", "CONFIRMED", poly));
            assertEquals("status", confirmed.getRelations().get(0).getDiscriminatorColumn());
            assertFalse(json(confirmed).contains("SECRET_VALUE"));
        }

        @Test
        @DisplayName("8. 未核对 / 判不出来：终点列单独构成唯一键才画虚线（主键或唯一键都算）")
        void 结构推断() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id", "UNDECIDABLE", Map.of()),
                    join("t_order", "customer_code", "t_customer", "code"),
                    join("t_item", "sku_id", "t_sku", "id"),
                    join("t_order", "status", "t_log", "order_id"));
            assertEquals(List.of("t_item.order_id→t_order.id", "t_order.customer_code→t_customer.code"), edges(g));
            for (Relation r : g.getRelations()) {
                assertEquals("INFERRED", r.getTier());
                assertNull(r.getConfirmedBy());
            }
        }

        @Test
        @DisplayName("8. 终点列只是组合键的一部分：不算唯一键，不画")
        void 组合键() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_price", "价格", 7, List.of(pk("sku_id", "region_id")), "sku_id", "region_id", "price"));
            SystemGraph g = DataGraphProjector.system(connection(), rows,
                    List.of(join("t_item", "sku_id", "t_price", "sku_id")));
            assertTrue(g.getRelations().isEmpty());
        }

        @Test
        @DisplayName("9. 终点不是唯一键、也没被确认：不画（同名码值列、同维度列都在这里被挡掉）")
        void 非键终点() {
            assertTrue(system(join("t_order", "status", "t_customer", "region_id", "UNDECIDABLE", Map.of()))
                    .getRelations().isEmpty());
        }
    }

    @Nested
    @DisplayName("§5.3 基数与自关联")
    class Cardinality {

        @Test
        @DisplayName("终点唯一：起点不唯一为多对一，起点也唯一为一对一；终点不唯一（只会是已确认关系）为 null")
        void 基数() {
            SystemGraph g = system(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_ext", "id", "t_order", "id"),
                    join("t_log", "order_id", "v_sales", "order_id", "CONFIRMED", Map.of()));
            assertEquals("MANY_TO_ONE", relation(g, "t_item.order_id→t_order.id").getCardinality());
            assertEquals("ONE_TO_ONE", relation(g, "t_ext.id→t_order.id").getCardinality());
            assertNull(relation(g, "t_log.order_id→v_sales.order_id").getCardinality());
        }

        @Test
        @DisplayName("自关联进 selfReferences、不进 relations；只有自关联的表不算 related")
        void 自关联() {
            SystemGraph g = system(join("t_region", "parent_id", "t_region", "id"));
            assertTrue(g.getRelations().isEmpty());
            TableCard region = card(g, "t_region");
            assertEquals(1, region.getSelfReferences().size());
            assertEquals("parent_id", region.getSelfReferences().get(0).getFromColumn());
            assertEquals("id", region.getSelfReferences().get(0).getToColumn());
            assertFalse(region.isRelated());
        }
    }

    @Nested
    @DisplayName("§5.4 表名、卡片行与线上的字")
    class Naming {

        @Test
        @DisplayName("表名：去掉估算行数后缀；注释像名称才当标题；说明句和空注释为 null；BASE TABLE 归一")
        void 表名() {
            SystemGraph g = system();
            assertEquals("订单表", card(g, "t_order").getDisplayName());
            assertEquals("订单表", card(g, "t_order").getComment());
            assertNull(card(g, "t_ext").getDisplayName());
            assertEquals("扩展表，按 id 一对一拆出", card(g, "t_ext").getComment());
            assertNull(card(g, "t_region").getDisplayName());
            assertNull(card(g, "t_region").getComment());
            assertEquals("TABLE", card(g, "t_order").getObjectType());
            assertEquals("VIEW", card(g, "v_sales").getObjectType());
        }

        @Test
        @DisplayName("「像名称」：16 个字符为界，含句子标点即不算")
        void 像名称() {
            assertTrue(DataGraphProjector.looksLikeName("一二三四五六七八九十一二三四五六"));
            assertFalse(DataGraphProjector.looksLikeName("一二三四五六七八九十一二三四五六七"));
            assertFalse(DataGraphProjector.looksLikeName("无公司代码字段，样本数据"));
            assertFalse(DataGraphProjector.looksLikeName("orders, legacy"));
            assertTrue(DataGraphProjector.looksLikeName("GB5D 公司代码"));
            assertTrue(DataGraphProjector.looksLikeName("客户-财务视图明细"));
            assertFalse(DataGraphProjector.looksLikeName(null));
        }

        @Test
        @DisplayName("非 TABLE / VIEW 的对象不进星图")
        void 对象类型() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            ConnectorSchema seq = table("s_seq", "序列", 0, List.of(), "id");
            seq.setObjectType("SEQUENCE");
            rows.add(seq);
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of());
            assertTrue(g.getTables().stream().noneMatch(t -> t.getName().equals("s_seq")));
        }

        @Test
        @DisplayName("卡片行：主键列；没有主键取列数最少的唯一键；relationColumns 含本表作为起点和终点的列")
        void 卡片行() {
            List<ConnectorSchema> rows = new ArrayList<>(schemas());
            rows.add(table("t_code", "编码", 8, List.of(uk("uk_b", "a", "b"), uk("uk_a", "code")), "a", "b", "code|编码"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(
                    join("t_item", "order_id", "t_order", "id"),
                    join("t_order", "customer_code", "t_customer", "code")));
            assertEquals(List.of("id"), names(card(g, "t_order").getKeyColumns()));
            assertEquals("订单主键", card(g, "t_order").getKeyColumns().get(0).getComment());
            assertEquals(List.of("code"), names(card(g, "t_code").getKeyColumns()));
            assertEquals(List.of(), names(card(g, "t_log").getKeyColumns()));
            assertEquals(List.of("customer_code", "id"), names(card(g, "t_order").getRelationColumns()));
            assertEquals(List.of("code"), names(card(g, "t_customer").getRelationColumns()));
            assertTrue(card(g, "t_order").isRelated());
            assertFalse(card(g, "t_sku").isRelated());
            assertEquals(5, card(g, "t_order").getFieldCount());
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

    @Test
    @DisplayName("§5.5 顺序确定：输入顺序打乱，输出的表、关系、id 都不变；id 互不相同")
    void 顺序与id() {
        List<ConnectorSemantic> joins = List.of(
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
        assertEquals(tableNames(a), tableNames(b));
        assertEquals(List.of("t_ext.id→t_order.id", "t_item.order_id→t_order.id",
                "t_order.customer_code→t_customer.code"), edges(a));
        assertEquals(edges(a), edges(b));
        assertEquals(ids(a), ids(b));
        assertEquals(3, new HashSet<>(ids(a)).size());
        ids(a).forEach(id -> assertTrue(id.matches("[0-9a-f]{16}"), id));
    }

    @Test
    @DisplayName("单表详情：字段按快照顺序，标主键 / 唯一键与是否参与关系；只带与本表相关的关系；未知表为 null")
    void 单表详情() {
        List<ConnectorSemantic> joins = List.of(
                join("t_item", "order_id", "t_order", "id"),
                join("t_order", "customer_code", "t_customer", "code"),
                join("t_region", "parent_id", "t_region", "id"),
                join("t_ext", "id", "t_order", "id"));
        TableDetail order = DataGraphProjector.table(schemas(), joins, "t_order");
        assertEquals(List.of("id", "customer_id", "customer_code", "shop_code", "status"),
                order.getFields().stream().map(Field::getName).toList());
        assertEquals("PRIMARY", field(order, "id").getKey());
        assertTrue(field(order, "id").isInRelation());
        assertTrue(field(order, "customer_code").isInRelation());
        assertFalse(field(order, "shop_code").isInRelation());
        assertEquals("订单主键", field(order, "id").getComment());
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
    @DisplayName("容易踩到的输入")
    class Edges {

        @Test
        @DisplayName("整份快照都是旧的（没有 extra.unique_keys）：推断关系一条都不画，已确认的照常画、基数为空")
        void 旧快照() {
            List<ConnectorSchema> rows = List.of(
                    table("t_order", "订单表", 1, null, "id", "customer_id"),
                    table("t_customer", "客户表", 2, null, "id"));
            SystemGraph g = DataGraphProjector.system(connection(), rows, List.of(
                    join("t_order", "customer_id", "t_customer", "id"),
                    join("t_order", "id", "t_customer", "id", "CONFIRMED", Map.of())));
            assertEquals(List.of("t_order.id→t_customer.id"), edges(g));
            assertNull(g.getRelations().get(0).getCardinality());
            assertEquals(List.of(), names(card(g, "t_order").getKeyColumns()));
        }

        @Test
        @DisplayName("中文、带空格的表名和列名：照常判定，id 是 16 位十六进制，单表详情按原名取到")
        void 中文名() {
            List<ConnectorSchema> rows = List.of(
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
        @DisplayName("同一对表之间多条关系（不同列）：各自保留、id 不同，两端的关系列都列出来")
        void 同一对表多条关系() {
            SystemGraph g = system(
                    join("t_order", "customer_id", "t_customer", "id"),
                    join("t_order", "customer_code", "t_customer", "code"));
            assertEquals(List.of("t_order.customer_code→t_customer.code", "t_order.customer_id→t_customer.id"),
                    edges(g));
            assertEquals(2, new HashSet<>(ids(g)).size());
            assertEquals(List.of("code", "id"), names(card(g, "t_customer").getRelationColumns()));
            assertEquals(List.of("customer_code", "customer_id"), names(card(g, "t_order").getRelationColumns()));
        }

        @Test
        @DisplayName("一行 detail_json 坏了：只丢这一行，其余照常")
        void 坏行() {
            ConnectorSemantic broken = join("t_ext", "id", "t_order", "id");
            broken.setDetailJson("{not json");
            SystemGraph g = system(broken, join("t_item", "order_id", "t_order", "id"));
            assertEquals(List.of("t_item.order_id→t_order.id"), edges(g));
        }
    }

    @Test
    @DisplayName("★ 永不出网：语义层写给 Agent 的说明、依据、置信度、判别值、租户都不在输出里")
    void 不出网() {
        ConnectorSemantic row = join("t_item", "order_id", "t_order", "id", "UNDECIDABLE", Map.of(
                "basis", "左侧只采到 1 个非空取值", "note", "验证时先统计 order_id=0 的占比",
                "care_reason", "注意空值", "verify_note", "样本不足", "discriminator_value", "SECRET"));
        row.setGloss("关联 t_order.id。已探查但判不出来");
        row.setConfidence(55);
        row.setEvidence("NAME");
        row.setTenantId("tenant-x");
        SystemGraph g = system(row);
        TableDetail d = DataGraphProjector.table(schemas(), List.of(row), "t_item");
        for (String out : List.of(json(g), json(d))) {
            for (String banned : List.of("\"gloss\"", "\"confidence\"", "\"basis\"", "\"note\"", "\"care_reason\"",
                    "\"verify_note\"", "\"discriminator_value\"", "\"evidence\"", "\"tenantId\"",
                    "已探查", "左侧只采到", "验证时先统计", "SECRET", "tenant-x")) {
                assertFalse(out.contains(banned), banned + " 出现在 " + out);
            }
        }
    }

    @Test
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
        return g.getRelations().stream()
                .map(r -> r.getFromTable() + "." + r.getFromColumn() + "→" + r.getToTable() + "." + r.getToColumn())
                .toList();
    }

    private static Relation relation(SystemGraph g, String edge) {
        int i = edges(g).indexOf(edge);
        assertTrue(i >= 0, edge + " 不在 " + edges(g));
        return g.getRelations().get(i);
    }

    private static TableCard card(SystemGraph g, String name) {
        return g.getTables().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Field field(TableDetail d, String name) {
        return d.getFields().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::getName).toList();
    }

    private static List<String> tableNames(SystemGraph g) {
        return g.getTables().stream().map(TableCard::getName).toList();
    }

    private static List<String> ids(SystemGraph g) {
        return g.getRelations().stream().map(Relation::getId).toList();
    }
}
```

- [ ] **Step 4: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='DataGraphProjectorTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报 `cannot find symbol ... DataGraphProjector`。

- [ ] **Step 5: 写投影**

`modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjector.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.utils.CommonUtil;
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
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据星图的全部规则（设计文档 §5.2–§5.5）：把一条连接的结构快照和语义层 JOIN 行投影成客户视图。
 *
 * <p>纯函数：不访问数据库、不依赖 Spring，同样的输入永远得到同样的输出（顺序也一样）。
 * 它不产生任何新结论，只做两件事——按固定顺序筛关系、把结构元数据翻译成客户看得懂的呈现。
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

    /** 表注释「像名称」的长度上限（字符数），超过就只当说明、不当标题。 */
    static final int NAME_MAX_CHARS = 16;
    private static final String SENTENCE_MARKS = "，。；,;.\n\r";

    // detail_json 里的结构键。写入方是 SemanticJoinValidator.JoinVerdict#structuralPatch；
    // 各读取方（推导、工具执行器）都各自声明常量，这里同样。
    private static final String KEY_JOIN_KIND = "join_kind";
    private static final String KEY_DISCRIMINATOR_COLUMN = "discriminator_column";

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

    public static SystemGraph system(Connection connection, List<ConnectorSchema> schemas,
                                     List<ConnectorSemantic> joins) {
        Projection p = project(schemas, joins);
        List<TableCard> cards = new ArrayList<>(p.tables().size());
        for (Table t : p.tables().values()) {
            cards.add(card(t, p));
        }
        return new SystemGraph(String.valueOf(connection.getId()), connection.getName(),
                connection.getDisplayName(), connection.getSemanticStatus(), List.copyOf(cards), p.relations());
    }

    /** @return 表不在快照里（或不是 TABLE / VIEW）时为 {@code null} */
    public static TableDetail table(List<ConnectorSchema> schemas, List<ConnectorSemantic> joins, String name) {
        Projection p = project(schemas, joins);
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
        return new TableDetail(t.name(), t.displayName(), t.comment(), t.objectType(), selfRefs,
                List.copyOf(fields), incident);
    }

    // ------------------------------------------------------------------ 投影

    private static Projection project(List<ConnectorSchema> schemas, List<ConnectorSemantic> joins) {
        Map<String, Table> tables = tables(schemas);
        Map<String, List<SelfReference>> selfRefs = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();
        for (ConnectorSemantic row : joins == null ? List.<ConnectorSemantic>of() : joins) {
            Judged j = judge(row, tables);
            if (j == null) {
                continue;
            }
            if (j.fromTable().equals(j.toTable())) {
                selfRefs.computeIfAbsent(j.fromTable(), k -> new ArrayList<>())
                        .add(new SelfReference(j.fromColumn(), j.toColumn()));
            } else {
                relations.add(relation(j, tables));
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
    private static Map<String, Table> tables(List<ConnectorSchema> schemas) {
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
            out.put(r.getObjectName(), new Table(r.getObjectName(), objectType(r.getObjectType()), comment,
                    looksLikeName(comment) ? comment : null,
                    fields.getOrDefault(r.getObjectName(), Map.of()),
                    SemanticTableRenderer.namedUniqueKeys(r)));
        }
        return out;
    }

    /**
     * 设计文档 §5.2 的判定表，按顺序、先命中者为准。{@code confidence}、{@code source}、{@code evidence} 不参与。
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
        // 8. 结构上成立：终点列单独构成终点表的一个唯一键
        if (singleColumnUnique(to, toColumn)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_INFERRED, null, null);
        }
        // 9. 其余不画
        return null;
    }

    private static Relation relation(Judged j, Map<String, Table> tables) {
        Table from = tables.get(j.fromTable());
        Table to = tables.get(j.toTable());
        return new Relation(relationId(j.fromTable(), j.fromColumn(), j.toTable(), j.toColumn()),
                j.fromTable(), j.fromColumn(), j.toTable(), j.toColumn(),
                cardinality(from, j.fromColumn(), to, j.toColumn()), j.tier(), j.confirmedBy(),
                label(from, j.fromColumn()), j.discriminatorColumn());
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
        return new TableCard(t.name(), t.displayName(), t.comment(), t.objectType(), related,
                p.selfReferences().getOrDefault(t.name(), List.of()),
                keyColumns(t).stream().map(c -> columnRef(t, c)).toList(),
                relationColumns.stream().map(c -> columnRef(t, c)).toList(),
                t.fields().size());
    }

    private static ColumnRef columnRef(Table t, String column) {
        FieldDetail f = t.fields().get(column);
        return new ColumnRef(column, f == null ? null : text(f.comment()));
    }

    /** 线上的字：起点列的客户注释；为空或与列名相同（忽略大小写）时不写。 */
    private static String label(Table from, String column) {
        FieldDetail f = from.fields().get(column);
        String comment = f == null ? null : text(f.comment());
        return comment == null || comment.equalsIgnoreCase(column) ? null : comment;
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

    private record Table(String name, String objectType, String comment, String displayName,
                 Map<String, FieldDetail> fields, List<NamedKey> keys) {
    }

    private record Judged(String fromTable, String fromColumn, String toTable, String toColumn,
                  String tier, String confirmedBy, String discriminatorColumn) {
    }

    private record Projection(Map<String, Table> tables, List<Relation> relations,
                              Map<String, List<SelfReference>> selfReferences) {
    }
}
```

- [ ] **Step 6: 跑测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='DataGraphProjectorTest*,SemanticTableRendererTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: `DataGraphProjectorTest cases=25 failures=0`，`SemanticTableRendererTest cases=3 failures=0`。

- [ ] **Step 7: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/generation/SemanticTableRenderer.java \
  modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph && \
git commit -q -F - <<'MSG'
feat(graph): 数据星图投影——按语义层结论筛关系、翻译成客户视图

纯函数，不落库不调模型：实线 = 采样核对通过或业务方确认，虚线 = 指向对方唯一键但未核对，
其余（否定、部分成立、过期、多态未确认、指向非键列）不画；业务方否认压过采样结论。
基数由唯一键推出；自关联记成「有上下级」；表名只用像名称的客户表注释；
语义层写给 Agent 的说明、置信度、判别值永不出网。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 3: 读服务与超管接口（data-service）

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphService.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphController.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphServiceTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphControllerTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorPerformanceTest.java`（spec §4 的性能验收）

**Interfaces:**
- Consumes: `DataGraphProjector.*`、`DataGraphFixtures`（Task 2）；`ConnectionMapper`、`ConnectorSchemaMapper`、`ConnectorSemanticMapper`（已有）；`SuperAdminGuard.requireSuperAdmin()`（已有，非超管时抛 `ServiceException(AUTHENTICATION_FAIL)`）。
- Produces（HTTP 接口，Task 5–9 的前端会用到）：
  - `GET /data/admin/data-graph/systems` → `SystemSummary[]`；
  - `GET /data/admin/data-graph/systems/{connectorId}` → `SystemGraph`；
  - `GET /data/admin/data-graph/systems/{connectorId}/tables?name=` → `TableDetail`；
  - 出错时 `respCode` 为 `4004`，`respMsg` 为「系统不存在」或「表不存在」；表名为空时 `respCode` 为 `5007`，`respMsg` 为「表名不能为空」。

- [ ] **Step 1: 写失败的测试**

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphServiceTest.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.when;

class DataGraphServiceTest {

    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private DataGraphService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper);
    }

    @Test
    @DisplayName("系统列表：只列出至少有一个 TABLE / VIEW 的连接，计数不含其他对象类型")
    void 系统列表() {
        Connection http = new Connection();
        http.setId(8L);
        http.setName("http");
        Connection seqOnly = new Connection();
        seqOnly.setId(9L);
        seqOnly.setName("seq-only");
        when(connectionMapper.selectList(any())).thenReturn(List.of(connection(), http, seqOnly));
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schemaRow(7L, "BASE TABLE"), schemaRow(7L, "VIEW"), schemaRow(9L, "SEQUENCE")));

        List<SystemSummary> systems = service.systems();

        assertEquals(1, systems.size());
        assertEquals("7", systems.get(0).getConnectorId());
        assertEquals("ERP 系统", systems.get(0).getDisplayName());
        assertEquals("READY", systems.get(0).getSemanticStatus());
        assertEquals(2, systems.get(0).getTableCount());
    }

    @Test
    @DisplayName("连接 id 不合法或查不到（含别的租户的连接）：系统不存在，且不再查快照")
    void 系统不存在() {
        ServiceException bad = assertThrows(ServiceException.class, () -> service.system("abc"));
        assertEquals(ExceptionCode.NOT_FOUND.getResultCode(), bad.getRespCode());
        assertEquals("系统不存在", bad.getRespMsg());
        when(connectionMapper.selectById(99L)).thenReturn(null);
        assertEquals("系统不存在", assertThrows(ServiceException.class, () -> service.system("99")).getRespMsg());
        verifyNoInteractions(schemaMapper, semanticMapper);
    }

    @Test
    @DisplayName("单表详情：表名为空是非法请求；表不在快照里是表不存在；表名两端空白会去掉")
    void 单表() {
        assertEquals("表名不能为空", assertThrows(ServiceException.class, () -> service.table("7", " ")).getRespMsg());
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(table("t_order", "订单表", 1, List.of(pk("id")), "id")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());

        ServiceException missing = assertThrows(ServiceException.class, () -> service.table("7", "t_nope"));
        assertEquals(ExceptionCode.NOT_FOUND.getResultCode(), missing.getRespCode());
        assertEquals("表不存在", missing.getRespMsg());
        assertEquals("t_order", service.table("7", " t_order ").getName());
    }

    @Test
    @DisplayName("表名只去两端空白，中间的空格原样保留")
    void 表名中间空格() {
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(table("采购 单", "采购单", 1, List.of(pk("编号")), "编号")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        assertEquals("采购 单", service.table("7", " 采购 单 ").getName());
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

    @Test
    @DisplayName("系统图：把这条连接的快照与 JOIN 行交给投影")
    void 系统图() {
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                table("t_order", "订单表", 1, List.of(pk("id")), "id"),
                table("t_item", "订单明细", 2, List.of(pk("id")), "id", "order_id")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(join("t_item", "order_id", "t_order", "id")));

        SystemGraph g = service.system("7");

        assertEquals("7", g.getConnectorId());
        assertEquals(2, g.getTables().size());
        assertEquals(1, g.getRelations().size());
    }

    private static ConnectorSchema schemaRow(long connectorId, String objectType) {
        ConnectorSchema row = new ConnectorSchema();
        row.setConnectorId(connectorId);
        row.setObjectType(objectType);
        return row;
    }
}
```

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphControllerTest.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataGraphControllerTest {

    @Test
    @DisplayName("非企业超管：三个接口都被拒，而且一行数据都不查")
    void 非超管被拒() {
        SuperAdminGuard guard = mock(SuperAdminGuard.class);
        DataGraphService service = mock(DataGraphService.class);
        when(guard.requireSuperAdmin())
                .thenThrow(new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "需要企业超级管理员权限"));
        DataGraphController controller = new DataGraphController(guard, service);

        assertThrows(ServiceException.class, controller::systems);
        assertThrows(ServiceException.class, () -> controller.system("7"));
        assertThrows(ServiceException.class, () -> controller.table("7", "t_order"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("企业超管：原样交给服务")
    void 超管放行() {
        SuperAdminGuard guard = mock(SuperAdminGuard.class);
        DataGraphService service = mock(DataGraphService.class);
        List<DataGraphViews.SystemSummary> systems = List.of();
        when(service.systems()).thenReturn(systems);
        DataGraphController controller = new DataGraphController(guard, service);

        assertSame(systems, controller.systems());
        controller.system("7");
        controller.table("7", "t_order");
        verify(service).system("7");
        verify(service).table("7", "t_order");
    }
}
```

spec §4 的性能验收：投影是接口里唯一的重活，另外只有两条按 connector_id 的查询。这里单测它，200 张表预热后连续跑 50 次，要求 P95 < 300 ms。实测是 7 ms，阈值留了很大余量，不会因为机器慢而偶发失败。

`modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/DataGraphProjectorPerformanceTest.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计文档 §4 的性能验收：一条连接 200 张表、200 条 JOIN 行。投影是接口里唯一的重活（另外只有两条按 connector_id
 * 的查询），这里单测它：预热后连续 50 次，P95 < 300 ms。
 */
class DataGraphProjectorPerformanceTest {

    @Test
    @DisplayName("200 张表（每张 30 列）、200 条 JOIN 行：预热后连续 50 次，P95 < 300 ms")
    void 两百张表() {
        List<ConnectorSchema> rows = new ArrayList<>();
        List<ConnectorSemantic> joins = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String[] columns = new String[30];
            columns[0] = "id|主键";
            columns[1] = "parent_id|上级";
            for (int c = 2; c < columns.length; c++) {
                columns[c] = "col_" + c + "|第 " + c + " 列";
            }
            rows.add(table(name(i), "表" + i, i, List.of(pk("id")), columns));
            if (i > 0) {
                joins.add(join(name(i), "parent_id", name((i - 1) / 4), "id"));
            }
        }
        joins.add(join(name(150), "col_2", name(10), "id", "CONFIRMED", Map.of()));

        for (int i = 0; i < 5; i++) {
            DataGraphProjector.system(connection(), rows, joins);
        }
        long[] nanos = new long[50];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            SystemGraph g = DataGraphProjector.system(connection(), rows, joins);
            nanos[i] = System.nanoTime() - start;
            assertEquals(200, g.getRelations().size());
        }
        Arrays.sort(nanos);
        long p95Millis = nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000;
        assertTrue(p95Millis < 300, "P95 = " + p95Millis + " ms");
        System.out.println("数据星图投影 200 表 P95 = " + p95Millis + " ms");
    }

    private static String name(int i) {
        return String.format("t_%03d", i);
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```bash
cd $DS && mvn -o -q -pl modules/data-server test -Dtest='DataGraphServiceTest*,DataGraphControllerTest*' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，报 `cannot find symbol ... DataGraphService`。

- [ ] **Step 3: 写服务和控制器**

`modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphService.java`：

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
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据星图的读取：每次请求从 {@code connection} + {@code connector_schema} + {@code connector_semantic(JOIN)} 现算，
 * 不落库、不排队、不调模型（设计文档 §4）。语义层任何入口的改动，下一次打开页面即生效。
 *
 * <p>三张表都在 {@code TENANT_AWARE_TABLES} 里，查询在调用方租户下进行：别的租户的连接在这里就是查不到。
 */
@Service
@RequiredArgsConstructor
public class DataGraphService {

    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;

    /** 只列出结构快照里至少有一个 TABLE / VIEW 的连接，按连接 id 升序。 */
    public List<SystemSummary> systems() {
        List<Connection> connections = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .orderByAsc(Connection::getId));
        Map<Long, Integer> tableCounts = new HashMap<>();
        for (ConnectorSchema row : schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .select(ConnectorSchema::getConnectorId, ConnectorSchema::getObjectType))) {
            if (row.getConnectorId() != null && DataGraphProjector.objectType(row.getObjectType()) != null) {
                tableCounts.merge(row.getConnectorId(), 1, Integer::sum);
            }
        }
        List<SystemSummary> out = new ArrayList<>();
        for (Connection c : connections) {
            int tables = tableCounts.getOrDefault(c.getId(), 0);
            if (tables > 0) {
                out.add(new SystemSummary(String.valueOf(c.getId()), c.getName(), c.getDisplayName(), c.getKind(),
                        c.getStatus(), c.getSemanticStatus(), tables));
            }
        }
        return out;
    }

    public SystemGraph system(String connectorId) {
        Connection connection = requireConnection(connectorId);
        return DataGraphProjector.system(connection, schemas(connection.getId()), joins(connection.getId()));
    }

    public TableDetail table(String connectorId, String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "表名不能为空");
        }
        Connection connection = requireConnection(connectorId);
        TableDetail detail = DataGraphProjector.table(schemas(connection.getId()), joins(connection.getId()),
                name.trim());
        if (detail == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "表不存在");
        }
        return detail;
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
}
```

`modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/DataGraphController.java`：

```java
package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
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
 * 数据星图：语义层表关系的只读客户视图（设计文档 {@code docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md}）。
 *
 * <p>全部限企业超管，与 {@code ConnectorAdminController} 一致：表结构本身就是敏感元数据。
 */
@Tag(name = "数据星图", description = "各业务系统的表与表关系（只读）")
@RestController
@RequestMapping("/data/admin/data-graph")
@RequiredArgsConstructor
public class DataGraphController {

    private final SuperAdminGuard superAdminGuard;
    private final DataGraphService dataGraphService;

    @Operation(summary = "系统列表（有结构快照的数据连接）")
    @GetMapping("/systems")
    public List<SystemSummary> systems() {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.systems();
    }

    @Operation(summary = "某个系统的表卡片与关系")
    @GetMapping("/systems/{connectorId}")
    public SystemGraph system(@PathVariable String connectorId) {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.system(connectorId);
    }

    @Operation(summary = "单表详情：字段与相关关系")
    @GetMapping("/systems/{connectorId}/tables")
    public TableDetail table(@PathVariable String connectorId, @RequestParam("name") String name) {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.table(connectorId, name);
    }
}
```

- [ ] **Step 4: 跑星图全部测试，确认通过**

```bash
cd $DS && rm -rf modules/data-server/target/surefire-reports && \
  mvn -o -q -pl modules/data-server test -Dtest='DataGraph*Test*,RowEstimateNoteTest*' -Dsurefire.failIfNoSpecifiedTests=false; \
  cd modules/data-server/target/surefire-reports && for f in TEST-*.xml; do echo "$f cases=$(grep -c '<testcase' $f) failures=$(grep -c '<failure\|<error' $f)"; done
```
Expected: 控制器 `cases=2`、投影 `cases=25`、性能 `cases=1`、服务 `cases=6`、RowEstimateNote `cases=3`，全部 `failures=0`。mvn 的输出里有一行 `数据星图投影 200 表 P95 = N ms`，N 为个位数到几十。

- [ ] **Step 5: 跑 data-server 全量测试**

```bash
cd $DS && mvn -o -pl modules/data-server test 2>&1 | grep -E "Tests run:.*Failures|BUILD"
```
Expected: 汇总行是 `Tests run: 2155, Failures: 0, Errors: 0, Skipped: 2`（如果 main 之后又加了测试，总数会变，但 Failures 和 Errors 必须都是 0），并且出现 `BUILD SUCCESS`。

- [ ] **Step 6: 提交**

```bash
cd $DS && git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph \
  modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph && \
git commit -q -F - <<'MSG'
feat(graph): 数据星图只读接口（系统列表 / 系统图 / 单表详情）

每次请求从 connection + connector_schema + connector_semantic(JOIN) 现算，
没有快照、队列、重建触发：语义层任何入口的改动下一次打开即生效。
全部限企业超管；租户隔离依赖三张源表在白名单里，单测钉住。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 4: 本地后端验收（真实数据，不改代码）

用本地 `test` 租户的真实数据核对 spec §1 的第 1–3 条，以及「永不出网」。

- [ ] **Step 1: 先问用户，再停掉正在跑的旧 data-server**

本地现在跑的 data-server 是旧分支 `codex/enterprise-data-graph` 构建的。停掉之前**必须先问用户**：这会影响用户正在用的本地环境。用户同意后执行：

```bash
pgrep -fl 'data-server-1.0-SNAPSHOT.jar'          # 应当只有一个进程，工作目录是 .worktrees/data-service-enterprise-data-graph
pkill -f 'data-server-1.0-SNAPSHOT.jar'; sleep 5; pgrep -fl 'data-server-1.0-SNAPSHOT.jar' || echo stopped
```
Expected: 第一条命令只列出一个进程；最后输出 `stopped`。gateway 的 jar 名是 `gateway-1.0-SNAPSHOT.jar`，不会被这条 pkill 匹配到。

- [ ] **Step 2: 构建并启动 v2 的 data-server**

```bash
cd $DS && mvn -o -q install -DskipTests && \
  NACOS_SERVER_ADDR=localhost:8849 nohup java -Xmx768m -jar modules/data-server/target/data-server-1.0-SNAPSHOT.jar > /tmp/jm-data-server.log 2>&1 & disown
```

然后轮询，直到日志里出现 `Started DataServerApplication`（最多等 3 分钟）：

```bash
for i in $(seq 1 36); do grep -q "Started DataServerApplication" /tmp/jm-data-server.log && echo started && break; sleep 5; done
```
Expected: 输出 `started`。gateway 继续用现在这个进程（它跑在 data-service main 上），不用动。

- [ ] **Step 3: 登录，列出系统**

```bash
TOKEN=$(curl -s -X POST localhost:10011/data/admin/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems | python3 -c '
import json,sys; d=json.load(sys.stdin)["data"]
for s in d: print(s["name"], s["displayName"], s["tableCount"], s["semanticStatus"])'
```
Expected（按连接 id 升序）：

```
test klny_erp 19 READY
erp_real erp_real 8 READY
```

- [ ] **Step 4: 核对两个系统的关系数、基数、自关联和中文名**

```bash
for NAME in erp_real test; do
  ID=$(curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems | python3 -c "import json,sys; print([s['connectorId'] for s in json.load(sys.stdin)['data'] if s['name']=='$NAME'][0])")
  curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems/$ID | python3 -c '
import json,sys; d=json.load(sys.stdin)["data"]; rs=d["relations"]; ts=d["tables"]
print(d["name"], "relations", len(rs), sorted(r["cardinality"] for r in rs), sorted({r["tier"] for r in rs}),
      "self", [t["name"] for t in ts if t["selfReferences"]],
      "related", sum(1 for t in ts if t["related"]), "isolated", sum(1 for t in ts if not t["related"]),
      "named", sum(1 for t in ts if t["displayName"]), "/", len(ts))'
done
```
Expected:

```
erp_real relations 7 ['MANY_TO_ONE', 'MANY_TO_ONE', 'MANY_TO_ONE', 'MANY_TO_ONE', 'MANY_TO_ONE', 'ONE_TO_ONE', 'ONE_TO_ONE'] ['INFERRED'] self ['EPS_WBSELEMENT'] related 8 isolated 0 named 5 / 8
test relations 2 ['MANY_TO_ONE', 'MANY_TO_ONE'] ['INFERRED'] self ['D1_ACCOUNT'] related 3 isolated 16 named 18 / 19
```
两个系统合计有中文名 23/27，对应 spec §1-3。

- [ ] **Step 5: 核对「改了语义层，下次打开就生效」（spec §1-2），改完立刻改回**

```bash
ERP=$(curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems | python3 -c "import json,sys; print([s['connectorId'] for s in json.load(sys.stdin)['data'] if s['name']=='erp_real'][0])")
Q() { docker exec -i dev-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 data-server -e "$1" 2>/dev/null; }
V() { curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems/$ERP | python3 -c '
import json,sys; rs=json.load(sys.stdin)["data"]["relations"]
print([(r["tier"], r["confirmedBy"]) for r in rs if r["fromTable"]=="EFI_VOUCHERDTL" and r["fromColumn"]=="VENDORID"])'; }
W="scope='JOIN' AND connector_id=$ERP AND object_name='EFI_VOUCHERDTL' AND field_name='VENDORID'"
V
Q "UPDATE connector_semantic SET verified='CONFIRMED' WHERE $W"; V
Q "UPDATE connector_semantic SET verified='UNDECIDABLE' WHERE $W"
Q "UPDATE connector_semantic SET detail_json=JSON_SET(detail_json,'$.human_verdict','UNRELATED') WHERE $W"; V
Q "UPDATE connector_semantic SET detail_json=JSON_REMOVE(detail_json,'$.human_verdict') WHERE $W"; V
```
Expected，依次四行：

```
[('INFERRED', None)]
[('CONFIRMED', 'DATA')]
[]
[('INFERRED', None)]
```
最后一行说明数据已经还原。

- [ ] **Step 6: 看一眼真实接口的耗时**

```bash
for i in $(seq 1 20); do curl -s -o /dev/null -w '%{time_total}\n' -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems/$ERP; done | sort -n | tail -2
```
Expected: 两个数都小于 `0.3`（秒）。这里只有 8 张表，只是个粗查；200 张表的验收由 Task 3 的性能测试负责。

- [ ] **Step 7: 核对「永不出网」和「系统不存在」**

```bash
curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems/$ERP > /tmp/dg-erp.json
for w in '"gloss"' '"confidence"' '"basis"' '"care_reason"' '"verify_note"' '"evidence"' '已探查' '判不出来' '验证时'; do grep -q "$w" /tmp/dg-erp.json && echo "LEAK $w"; done; echo scan-done
curl -s -H "Authorization: $TOKEN" localhost:10011/data/admin/data-graph/systems/1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["respCode"], d["respMsg"])'
```
Expected：只输出 `scan-done`，没有任何 `LEAK` 行；然后输出 `4004 系统不存在`。

---

### Task 5: 前端——端到端夹具检查脚本（先写，先失败）

前端没有单测框架，所以先把验收脚本写出来：自己起一个临时 Vite，在浏览器里拦截所有 `/data/` 请求，返回夹具数据。一开始整页都不存在，脚本应该失败；Task 8 完成后应该全部通过。

**Files:**
- Create: `e2e/data-graph-check.mjs`
- Modify: `package.json`（`scripts` 里在 `"test:workbench"` 下一行加一条）

**Interfaces:**
- Consumes: `e2e/lib.mjs` 已有的 `launchBrowser`、`reporter`、`shot`。
- 约定：页面上的 `data-testid` 有 `data-graph-page`、`data-graph-canvas`、`dg-card`（带 `data-table` 属性）、`dg-detail`、`dg-side`、`dg-relation-list`、`dg-isolated-list`、`dg-empty`、`dg-explore-hint`；CSS 类有 `.react-flow__node`、`.react-flow__edge`、`.react-flow__edge-path.dg-edge.is-inferred`、`.dg-card.is-dimmed`、`.data-graph-summary`、`.data-graph-note`、`.data-graph-search`、`.dg-search-option`、`.dg-detail__back`、`.react-flow__minimap`。Task 7、Task 8 必须按这些名字实现。

- [ ] **Step 1: 写脚本**

`e2e/data-graph-check.mjs`：

```js
// 数据星图端到端检查（data-service 设计文档 §8）：自起临时 Vite，浏览器里拦截全部 /data/ 请求返回夹具，
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

const systems = [
  { connectorId: '7', name: 'erp', displayName: 'ERP 系统', kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '7' },
  { connectorId: '8', name: 'crm', displayName: null, kind: 'MYSQL', status: 'DISABLED', semanticStatus: null, tableCount: '2' },
  { connectorId: '9', name: 'big', displayName: '大系统', kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'RUNNING', tableCount: '200' },
  { connectorId: '10', name: 'running', displayName: null, kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'RUNNING', tableCount: '1' },
  { connectorId: '11', name: 'failed', displayName: null, kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'FAILED', tableCount: '1' },
  { connectorId: '12', name: 'ready-empty', displayName: null, kind: 'MYSQL', status: 'ACTIVE', semanticStatus: 'READY', tableCount: '1' },
];

const card = (name, displayName, comment, related, keyColumns, relationColumns, extra = {}) => ({
  name,
  displayName,
  comment,
  objectType: 'TABLE',
  related,
  selfReferences: [],
  keyColumns,
  relationColumns,
  fieldCount: String(keyColumns.length + relationColumns.length + 3),
  ...extra,
});

const erpGraph = {
  connectorId: '7',
  name: 'erp',
  displayName: 'ERP 系统',
  semanticStatus: 'READY',
  tables: [
    card('t_order', '订单表', '订单表', true, [col('id', '订单主键')], [col('customer_code'), col('customer_id', '下单客户'), col('id', '订单主键')]),
    card('t_customer', '客户表', '客户表', true, [col('id')], [col('code', '客户编码'), col('id')]),
    card('t_item', '订单明细', '订单明细', true, [col('id')], [col('order_id', '所属订单')]),
    card('t_ext', null, '扩展表，按 id 一对一拆出', true, [col('id')], [col('id')]),
    // 中文、带空格的表名和列名（ERP 里常见），走同样的连线与详情接口
    card('采购 单', '采购单', '采购单', true, [col('编号')], [col('客户 编号', '客户编号')]),
    card('t_region', '地区', '地区', false, [col('id')], [], { selfReferences: [{ fromColumn: 'parent_id', toColumn: 'id' }] }),
    card('t_log', '操作日志', '操作日志', false, [], []),
  ],
  relations: [
    { id: 'a1b2c3d4e5f60001', fromTable: 't_ext', fromColumn: 'id', toTable: 't_order', toColumn: 'id', cardinality: 'ONE_TO_ONE', tier: 'INFERRED', confirmedBy: null, label: null, discriminatorColumn: null },
    { id: 'a1b2c3d4e5f60002', fromTable: 't_item', fromColumn: 'order_id', toTable: 't_order', toColumn: 'id', cardinality: 'MANY_TO_ONE', tier: 'CONFIRMED', confirmedBy: 'DATA', label: '所属订单', discriminatorColumn: null },
    // 同一对表之间两条关系（不同列），各自连到各自的字段行
    { id: 'a1b2c3d4e5f60004', fromTable: 't_order', fromColumn: 'customer_code', toTable: 't_customer', toColumn: 'code', cardinality: 'MANY_TO_ONE', tier: 'CONFIRMED', confirmedBy: 'DATA', label: null, discriminatorColumn: null },
    { id: 'a1b2c3d4e5f60003', fromTable: 't_order', fromColumn: 'customer_id', toTable: 't_customer', toColumn: 'id', cardinality: 'MANY_TO_ONE', tier: 'CONFIRMED', confirmedBy: 'BUSINESS', label: '下单客户', discriminatorColumn: null },
    { id: 'a1b2c3d4e5f60005', fromTable: '采购 单', fromColumn: '客户 编号', toTable: 't_customer', toColumn: 'id', cardinality: 'MANY_TO_ONE', tier: 'INFERRED', confirmedBy: null, label: '客户编号', discriminatorColumn: null },
  ],
};

const lonely = (connectorId, name, semanticStatus) => ({
  connectorId,
  name,
  displayName: null,
  semanticStatus,
  tables: [card(`${name}_t1`, null, null, false, [col('id')], [])],
  relations: [],
});

// 200 张表、200 条关系：每张表挂到 (i-1)/4 号表上形成扇入树，再补一条跨枝的线。
const bigTables = Array.from({ length: 200 }, (_, i) => {
  const name = `table_${String(i).padStart(3, '0')}`;
  const outgoing = i > 0 ? [col('parent_id', '上级')] : [];
  return card(name, `表${i}`, `表${i}`, true, [col('id')], [...outgoing, col('id')]);
});
const bigRelations = bigTables.slice(1).map((table, index) => ({
  id: `b${String(index).padStart(15, '0')}`,
  fromTable: table.name,
  fromColumn: 'parent_id',
  toTable: bigTables[Math.floor(index / 4)].name,
  toColumn: 'id',
  cardinality: 'MANY_TO_ONE',
  tier: index % 3 === 0 ? 'CONFIRMED' : 'INFERRED',
  confirmedBy: index % 3 === 0 ? 'DATA' : null,
  label: null,
  discriminatorColumn: null,
}));
bigRelations.push({ ...bigRelations[0], id: 'bextra0000000000', fromTable: 'table_150', toTable: 'table_010' });
const bigGraph = { connectorId: '9', name: 'big', displayName: '大系统', semanticStatus: 'RUNNING', tables: bigTables, relations: bigRelations };

const graphs = {
  7: erpGraph,
  8: { ...lonely('8', 'crm', null), tables: [card('crm_a', null, null, false, [col('id')], []), card('crm_b', null, null, false, [col('id')], [])] },
  9: bigGraph,
  10: lonely('10', 'running', 'RUNNING'),
  11: lonely('11', 'failed', 'FAILED'),
  12: lonely('12', 'ready-empty', 'READY'),
};

function tableDetail(graph, name) {
  const table = graph.tables.find((t) => t.name === name);
  if (!table) return null;
  const columns = [...table.keyColumns, ...table.relationColumns.filter((c) => !table.keyColumns.some((k) => k.name === c.name))];
  return {
    name: table.name,
    displayName: table.displayName,
    comment: table.comment,
    objectType: 'TABLE',
    selfReferences: table.selfReferences,
    fields: columns.map((c, index) => ({
      name: c.name,
      type: 'bigint',
      nullable: index > 0,
      comment: c.comment,
      key: index === 0 && table.keyColumns.length ? 'PRIMARY' : null,
      inRelation: table.relationColumns.some((r) => r.name === c.name),
    })),
    relations: graph.relations.filter((r) => r.fromTable === name || r.toTable === name),
  };
}

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

const FORBIDDEN = ['包含', 'READY', 'RUNNING', 'FAILED', 'INSPECTOR', 'UNKNOWN', '%', 'AI 推测', 'AI 可信度', '置信度'];

async function nodePositions(page) {
  return page.$$eval('.react-flow__node', (nodes) =>
    Object.fromEntries(nodes.map((node) => [node.getAttribute('data-id'), node.style.transform])),
  );
}

// 等画布画完：卡片数、连线数到位，视口（自动缩放 / 居中）连续两次读数不变。
async function waitForGraph(page, cards, edges) {
  await page.waitForFunction(
    ([c, e]) =>
      document.querySelectorAll('[data-testid="dg-card"]').length === c &&
      document.querySelectorAll('.react-flow__edge').length === e,
    [cards, edges],
    { timeout: 30_000 },
  );
  let last = '';
  for (let i = 0; i < 30; i += 1) {
    const now = await page.$eval('.react-flow__viewport', (el) => el.style.transform).catch(() => '');
    if (now && now === last) return;
    last = now;
    await page.waitForTimeout(100);
  }
}

async function openSystem(page, baseUrl, system) {
  await page.goto(`${baseUrl}/console/data-graph${system ? `?system=${system}` : ''}`, { waitUntil: 'domcontentloaded' });
  await page.locator('[data-testid="data-graph-page"]').waitFor({ state: 'visible', timeout: 15_000 });
}

const { baseUrl, child } = await startVite();
const r = reporter('data-graph');
const { browser, page } = await launchBrowser();
const pageErrors = [];
const unknownApis = new Set();
page.on('pageerror', (error) => pageErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() === 'error') pageErrors.push(message.text());
});

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
      if (path.endsWith('/admin/auth/login')) return json({ token, user });
      if (path.endsWith('/admin/auth/me')) return json(user);
      if (path.endsWith('/admin/me/permissions')) return json({ superAdmin: true, modules: [], agentIds: [], knowledgeBaseIds: [] });
      if (path === '/data/admin/data-graph/systems') return json(systems);
      const tableMatch = path.match(/^\/data\/admin\/data-graph\/systems\/([^/]+)\/tables$/);
      if (tableMatch) {
        const name = new URL(route.request().url()).searchParams.get('name');
        return json(tableDetail(graphs[tableMatch[1]], name));
      }
      const systemMatch = path.match(/^\/data\/admin\/data-graph\/systems\/([^/]+)$/);
      if (systemMatch) return json(graphs[systemMatch[1]]);
      unknownApis.add(path);
      return json(null);
    },
  );

  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', 'graph-admin');
  await page.fill('#password', 'fixture-password');
  await page.locator('.login-submit').click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15_000 });

  // 1. 默认打开第一个系统：只有有关系的表上画布，线有实线有虚线
  await openSystem(page, baseUrl, null);
  await waitForGraph(page, 5, 5);
  r.ok('画布只放有关系的表（5 张）', (await page.locator('[data-testid="dg-card"]').count()) === 5);
  r.ok('5 条关系线（同一对表的两条各自画出）', (await page.locator('.react-flow__edge').count()) === 5);
  r.ok('推断关系是虚线（2 条）', (await page.locator('.react-flow__edge-path.dg-edge.is-inferred').count()) === 2);
  const summary = await page.locator('.data-graph-summary').innerText();
  r.ok('概览：7 张表、已确认 3、推断 2、未发现关联 2', /表\s*7/.test(summary) && /已确认关系\s*3/.test(summary) && /推断关系\s*2/.test(summary) && /未发现关联的表\s*2/.test(summary), summary.replace(/\s+/g, ' '));
  const pageText = await page.locator('[data-testid="data-graph-page"]').innerText();
  const leaked = FORBIDDEN.filter((word) => pageText.includes(word));
  r.ok('界面文案不含运维 / AI 用语', leaked.length === 0, leaked.join(','));
  r.ok('图例写明实线与虚线', pageText.includes('已确认：数据核对通过或业务方确认') && pageText.includes('推断：按表结构，尚未核对'));
  r.ok('关系清单是句子', pageText.includes('每条「订单明细」对应一条「订单表」（order_id → id）'));
  r.ok('一对一句子', pageText.includes('「t_ext」与「订单表」一一对应（id → id）'));
  r.ok('中文带空格的表名照常成句', pageText.includes('每条「采购单」对应一条「客户表」（客户 编号 → id）'));
  r.ok('来源说明', pageText.includes('业务方确认') && pageText.includes('数据核对通过') && pageText.includes('按表结构推断，尚未核对'));
  r.ok(
    '小图整图显示，没有小地图和提示',
    (await page.locator('.react-flow__minimap').count()) === 0 &&
      (await page.locator('[data-testid="dg-explore-hint"]').count()) === 0,
  );
  const allInside = await page.evaluate(() => {
    const canvas = document.querySelector('[data-testid="data-graph-canvas"]').getBoundingClientRect();
    return [...document.querySelectorAll('[data-testid="dg-card"]')].every((element) => {
      const box = element.getBoundingClientRect();
      return box.left >= canvas.left && box.right <= canvas.right && box.top >= canvas.top && box.bottom <= canvas.bottom;
    });
  });
  r.ok('小图 5 张卡片都在画布视野里', allInside);
  await page.screenshot({ path: shot('data-graph-v2.png'), fullPage: true });

  // 2. 布局确定：连续加载 3 次坐标一致
  const first = await nodePositions(page);
  let stable = true;
  for (let i = 0; i < 2; i += 1) {
    await openSystem(page, baseUrl, '7');
    await waitForGraph(page, 5, 5);
    stable = stable && JSON.stringify(await nodePositions(page)) === JSON.stringify(first);
  }
  r.ok('同一份数据刷新 3 次，卡片坐标完全一致', stable);

  // 3. 点每一张卡片：不报错、坐标不动、右侧出详情
  const errorsBefore = pageErrors.length;
  let clickStable = true;
  let detailShown = true;
  for (const name of ['t_order', 't_customer', 't_item', 't_ext', '采购 单']) {
    await page.locator(`[data-testid="dg-card"][data-table="${name}"]`).click();
    detailShown = detailShown && (await page.locator('[data-testid="dg-detail"]').waitFor({ state: 'visible', timeout: 5_000 }).then(() => true).catch(() => false));
    clickStable = clickStable && JSON.stringify(await nodePositions(page)) === JSON.stringify(first);
  }
  r.ok('点卡片后右侧出现表详情', detailShown);
  r.ok('点卡片不改变任何卡片坐标', clickStable);
  r.ok('点卡片期间控制台无报错', pageErrors.length === errorsBefore, pageErrors.slice(errorsBefore).join(' | '));
  r.ok('选中后非相邻的表变暗', (await page.locator('.dg-card.is-dimmed').count()) >= 1);

  // 3b. 搜索定位：选中搜索结果即打开该表详情
  await page.locator('.dg-detail__back').click();
  await page.locator('.data-graph-search input').fill('订单明细');
  await page.locator('.dg-search-option', { hasText: '订单明细' }).first().click();
  const searchDetail = await page.locator('[data-testid="dg-detail"]').innerText().catch(() => '');
  r.ok('搜索选中后右侧是该表详情', searchDetail.includes('订单明细') && searchDetail.includes('t_item'));

  // 4. 未发现关联的表：在右侧列表里，带「有上下级」标记
  await page.locator('.dg-detail__back').click();
  await page.getByRole('tab', { name: /未发现关联的表/ }).click();
  const isolatedText = await page.locator('[data-testid="dg-isolated-list"]').innerText();
  r.ok('未发现关联的表列出地区与操作日志', isolatedText.includes('地区') && isolatedText.includes('操作日志') && isolatedText.includes('有上下级'));

  // 5. 空状态
  const states = [
    ['8', '这个系统的表关系还没整理'],
    ['10', '正在整理表关系，完成后刷新页面即可看到'],
    ['11', '表关系整理没有成功'],
    ['12', '暂未发现可以确认的表关系'],
  ];
  for (const [system, text] of states) {
    await openSystem(page, baseUrl, system);
    const shown = await page.locator('[data-testid="dg-empty"]').waitFor({ state: 'visible', timeout: 10_000 }).then(() => true).catch(() => false);
    r.ok(`空状态（系统 ${system}）`, shown && (await page.locator('[data-testid="dg-empty"]').innerText()).includes(text));
  }

  // 6. 200 张表、200 条关系：能渲染，记录首屏耗时；语义层更新中的提示
  const started = Date.now();
  await openSystem(page, baseUrl, '9');
  await waitForGraph(page, 200, 200);
  const elapsed = Date.now() - started;
  r.ok('200 张表的大图能渲染', true, `首屏 ${elapsed} ms`);
  r.ok('语义层更新中有提示', (await page.locator('.data-graph-note').innerText()).includes('语义层正在更新'));
  r.ok('大图不强行缩成一屏：有小地图', await page.locator('.react-flow__minimap').isVisible());
  const hint = await page.locator('[data-testid="dg-explore-hint"]').innerText().catch(() => '');
  r.ok('大图提示先显示关联最多的表附近', hint.includes('「表10」附近'), hint);
  const hubBox = await page.locator('[data-testid="dg-card"][data-table="table_010"]').boundingBox();
  const canvasBox = await page.locator('[data-testid="data-graph-canvas"]').boundingBox();
  r.ok(
    '关联最多的表在视野里、卡片够大能读',
    Boolean(hubBox && canvasBox) &&
      hubBox.width >= 200 &&
      hubBox.x >= canvasBox.x &&
      hubBox.x + hubBox.width <= canvasBox.x + canvasBox.width &&
      hubBox.y >= canvasBox.y &&
      hubBox.y + hubBox.height <= canvasBox.y + canvasBox.height,
    JSON.stringify({ hubBox, canvasBox }),
  );
  await page.screenshot({ path: shot('data-graph-v2-big.png'), fullPage: true });
  r.ok('全程无未预期的接口', unknownApis.size === 0, [...unknownApis].join(','));
} finally {
  await browser.close();
  if (child) child.kill('SIGTERM');
}

const ok = r.summary();
process.exit(ok ? 0 : 1);
```

- [ ] **Step 2: 加 npm 脚本**

在 `package.json` 的 `"test:workbench": "node e2e/run-workbench.mjs",` 下一行加：

```json
    "test:data-graph": "node e2e/data-graph-check.mjs",
```

- [ ] **Step 3: 跑脚本，确认失败**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && \
  (ls e2e/node_modules/playwright >/dev/null 2>&1 || (cd e2e && npm install --no-audit --no-fund)) && \
  npm run test:data-graph 2>&1 | tail -5
```
Expected: 以非零退出码结束，报 `locator.waitFor: Timeout 15000ms exceeded`，等待的是 `[data-testid="data-graph-page"]`。原因是路由 `/console/data-graph` 还不存在。

- [ ] **Step 4: 提交**

```bash
cd $FE && git add e2e/data-graph-check.mjs package.json && git commit -q -F - <<'MSG'
test(data-graph): 数据星图端到端夹具检查（先红）

自起临时 Vite、拦截全部 /data/ 请求返回夹具，不碰真实库、网关、模型：
布局确定、点卡片不动也不报错、界面不出现运维与 AI 用语、空状态、
中文表名、同一对表多条关系、200 表大图的显示方式。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 6: 前端——依赖与数据层

**Files:**
- Modify: `package.json`、`package-lock.json`（由 npm install 修改）
- Create: `src/features/data-graph/types.ts`
- Create: `src/features/data-graph/api.ts`
- Create: `src/features/data-graph/text.ts`
- Create: `src/features/data-graph/layout.ts`
- Create: `src/features/data-graph/flow.ts`

**Interfaces:**
- Consumes: Task 3 的 HTTP 接口；`get<T>(url, params?)`（`@/api/client`，自动加 `/data` 前缀，成功时直接返回解开信封后的 data）。
- Produces（Task 7、Task 8 会用到）：
  - `dataGraphApi.systems(): Promise<SystemSummary[]>`；
  - `dataGraphApi.system(id): Promise<SystemGraph>`；
  - `dataGraphApi.table(id, name): Promise<TableDetail>`；
  - `tableTitle(t)`、`relationSentence(r, titleOf)`、`selfReferenceSentence(title, ref)`、`relationSource(r)`；
  - `layoutTables(tables, relations): Map<string, CardBox>`、`fitsOnOneScreen(boxes)`、`hubTable(tables, relations)`、`cardRows(table)`、`EXPLORE_ZOOM`、`CARD_*` 常量；
  - `buildFlow(tables, relations, boxes, selected, onSelect)`，以及 `TableFlowNode`、`RelationFlowEdge`、`FocusRequest` 三个类型。

- [ ] **Step 1: 装依赖（锁版本）**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && npm install --no-audit --no-fund @xyflow/react@12.12.0 @dagrejs/dagre@3.1.1
```
Expected: `package.json` 的 dependencies 里多出 `"@dagrejs/dagre": "^3.1.1"` 和 `"@xyflow/react": "^12.12.0"`。

- [ ] **Step 2: 写类型与接口**

`src/features/data-graph/types.ts`：

```ts
// 数据星图接口的形状（data-service 设计文档 §5.1）。
// spring.jackson.write_numbers_as_strings=true：数字到前端都是字符串，api.ts 负责转成 number。

export type SemanticStatus = 'READY' | 'RUNNING' | 'FAILED' | null;
export type RelationTier = 'CONFIRMED' | 'INFERRED';
export type RelationCardinality = 'MANY_TO_ONE' | 'ONE_TO_ONE' | null;
export type ConfirmedBy = 'DATA' | 'BUSINESS' | null;
export type ColumnKey = 'PRIMARY' | 'UNIQUE' | null;

type NumericWire = number | string;

export interface SystemSummaryWire {
  connectorId: string;
  name: string;
  displayName: string | null;
  kind: string | null;
  status: string | null;
  semanticStatus: SemanticStatus;
  tableCount: NumericWire;
}

export interface SystemSummary extends Omit<SystemSummaryWire, 'tableCount'> {
  tableCount: number;
}

export interface ColumnRef {
  name: string;
  comment: string | null;
}

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
  selfReferences: SelfReference[];
  keyColumns: ColumnRef[];
  relationColumns: ColumnRef[];
  fieldCount: NumericWire;
}

export interface TableCard extends Omit<TableCardWire, 'fieldCount'> {
  fieldCount: number;
}

export interface Relation {
  id: string;
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
  cardinality: RelationCardinality;
  tier: RelationTier;
  confirmedBy: ConfirmedBy;
  label: string | null;
  discriminatorColumn: string | null;
}

export interface SystemGraphWire {
  connectorId: string;
  name: string;
  displayName: string | null;
  semanticStatus: SemanticStatus;
  tables: TableCardWire[];
  relations: Relation[];
}

export interface SystemGraph extends Omit<SystemGraphWire, 'tables'> {
  tables: TableCard[];
}

export interface FieldInfo {
  name: string;
  type: string | null;
  nullable: boolean;
  comment: string | null;
  key: ColumnKey;
  inRelation: boolean;
}

export interface TableDetail {
  name: string;
  displayName: string | null;
  comment: string | null;
  objectType: 'TABLE' | 'VIEW';
  selfReferences: SelfReference[];
  fields: FieldInfo[];
  relations: Relation[];
}
```

`src/features/data-graph/api.ts`：

```ts
import { get } from '@/api/client';
import type {
  SystemGraph,
  SystemGraphWire,
  SystemSummary,
  SystemSummaryWire,
  TableDetail,
} from './types';

const ROOT = '/admin/data-graph';

const toCount = (value: number | string): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};

export const dataGraphApi = {
  systems: async (): Promise<SystemSummary[]> =>
    (await get<SystemSummaryWire[]>(`${ROOT}/systems`)).map((system) => ({
      ...system,
      tableCount: toCount(system.tableCount),
    })),
  system: async (connectorId: string): Promise<SystemGraph> => {
    const wire = await get<SystemGraphWire>(`${ROOT}/systems/${encodeURIComponent(connectorId)}`);
    return {
      ...wire,
      tables: wire.tables.map((table) => ({ ...table, fieldCount: toCount(table.fieldCount) })),
    };
  },
  table: (connectorId: string, name: string): Promise<TableDetail> =>
    get<TableDetail>(`${ROOT}/systems/${encodeURIComponent(connectorId)}/tables`, { name }),
};
```

- [ ] **Step 3: 写文案工具（句式与 spec §6.4 逐字一致）**

`src/features/data-graph/text.ts`：

```ts
import type { Relation, SelfReference } from './types';

interface Named {
  name: string;
  displayName: string | null;
}

/** 标题：像名称的客户表注释，否则物理名（data-service 设计文档 §5.4）。 */
export const tableTitle = (table: Named): string => table.displayName ?? table.name;

/** 关系说成一句话（设计文档 §6.4）；表名一律用标题。 */
export function relationSentence(relation: Relation, titleOf: (table: string) => string): string {
  const from = titleOf(relation.fromTable);
  const to = titleOf(relation.toTable);
  const columns = `${relation.fromColumn} → ${relation.toColumn}`;
  let sentence: string;
  if (relation.cardinality === 'MANY_TO_ONE') {
    sentence = `每条「${from}」对应一条「${to}」（${columns}）`;
  } else if (relation.cardinality === 'ONE_TO_ONE') {
    sentence = `「${from}」与「${to}」一一对应（${columns}）`;
  } else {
    sentence = `「${from}」的 ${relation.fromColumn} 关联「${to}」的 ${relation.toColumn}`;
  }
  return relation.discriminatorColumn
    ? `${sentence}，按 ${relation.discriminatorColumn} 区分类型`
    : sentence;
}

export const selfReferenceSentence = (title: string, ref: SelfReference): string =>
  `「${title}」内部有上下级（${ref.fromColumn} → ${ref.toColumn}）`;

export function relationSource(relation: Relation): string {
  if (relation.tier === 'INFERRED') return '按表结构推断，尚未核对';
  return relation.confirmedBy === 'BUSINESS' ? '业务方确认' : '数据核对通过';
}
```

- [ ] **Step 4: 写布局（确定性，外加「一屏看不看得清」的判据）**

`src/features/data-graph/layout.ts`：

```ts
import { Graph, layout } from '@dagrejs/dagre';
import type { ColumnRef, Relation, TableCard } from './types';

// 与 data-graph.css 里 .dg-card 的宽度和头部、行、底部高度一致：布局按这个算，DOM 也按这个画。
export const CARD_WIDTH = 240;
export const CARD_HEADER_HEIGHT = 58;
export const CARD_ROW_HEIGHT = 26;
export const CARD_FOOTER_HEIGHT = 28;

// 「整图一屏看得清」的判据：按一块 800×620 的名义画布算，整图缩放到能放下时比例不低于 0.55
// （卡片标题约 8px 以上）。只看数据不看窗口大小，所以同一份数据永远是同一种显示方式。
const NOMINAL_CANVAS_WIDTH = 800;
const NOMINAL_CANVAS_HEIGHT = 620;
const READABLE_ZOOM = 0.55;
/** 整图看不清时，打开就放大到关联最多的那张表附近，用这个比例。 */
export const EXPLORE_ZOOM = 0.9;

export interface CardRow extends ColumnRef {
  /** 主键列（没有主键时为最小的唯一键列）。 */
  isKey: boolean;
}

export interface CardBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 卡片上的行：键列在前，再是参与关系的列；去重，保持后端给的顺序。 */
export function cardRows(table: TableCard): CardRow[] {
  const keys = new Set(table.keyColumns.map((column) => column.name));
  const seen = new Set<string>();
  const rows: CardRow[] = [];
  for (const column of [...table.keyColumns, ...table.relationColumns]) {
    if (seen.has(column.name)) continue;
    seen.add(column.name);
    rows.push({ ...column, isKey: keys.has(column.name) });
  }
  return rows;
}

export const cardHeight = (table: TableCard): number =>
  CARD_HEADER_HEIGHT + cardRows(table).length * CARD_ROW_HEIGHT + CARD_FOOTER_HEIGHT;

/**
 * dagre 分层布局，rankdir=LR：引用方在左，被引用的主数据在右。
 * 节点和边按后端给的顺序喂入（设计文档 §5.5），同一份数据永远得到同一组坐标。返回卡片左上角坐标。
 */
export function layoutTables(tables: TableCard[], relations: Relation[]): Map<string, CardBox> {
  const graph = new Graph({ multigraph: true });
  graph.setGraph({ rankdir: 'LR', nodesep: 28, ranksep: 100, marginx: 24, marginy: 24 });
  graph.setDefaultEdgeLabel(() => ({}));
  tables.forEach((table) =>
    graph.setNode(table.name, { width: CARD_WIDTH, height: cardHeight(table) }),
  );
  relations.forEach((relation) =>
    graph.setEdge(relation.fromTable, relation.toTable, {}, relation.id),
  );
  layout(graph);
  const boxes = new Map<string, CardBox>();
  tables.forEach((table) => {
    const node = graph.node(table.name) as CardBox;
    boxes.set(table.name, {
      x: node.x - node.width / 2,
      y: node.y - node.height / 2,
      width: node.width,
      height: node.height,
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

/** 关联最多的表（起点、终点都算）；并列取后端顺序里靠前的。没有表时为 null。 */
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

- [ ] **Step 5: 写「接口数据 → React Flow 节点 / 边」的转换**

`src/features/data-graph/flow.ts`：

```ts
import type { Edge, Node } from '@xyflow/react';
import { cardRows, type CardBox, type CardRow } from './layout';
import { tableTitle } from './text';
import type { Relation, RelationTier, TableCard } from './types';

export interface TableNodeData extends Record<string, unknown> {
  name: string;
  title: string;
  /** 有中文名时是物理名；否则是表注释（说明句）或空。 */
  subtitle: string | null;
  subtitleIsName: boolean;
  rows: CardRow[];
  fieldCount: number;
  hasHierarchy: boolean;
  selected: boolean;
  dimmed: boolean;
  onSelect: (name: string) => void;
}

export type TableFlowNode = Node<TableNodeData, 'table'>;

export interface RelationEdgeData extends Record<string, unknown> {
  tier: RelationTier;
  label: string | null;
  fromMark: string | null;
  toMark: string | null;
  dimmed: boolean;
}

export type RelationFlowEdge = Edge<RelationEdgeData, 'relation'>;

/** 搜索或点击清单时请求画布把某张表挪到视野中央；seq 让「同一张表再点一次」也能生效。 */
export interface FocusRequest {
  name: string;
  seq: number;
}

/**
 * 把接口数据和布局结果变成 React Flow 的节点与边。坐标只来自 boxes，选中与否只改样式——点表不会让布局动。
 * 选中的表不在画布上（未发现关联的表）时不做任何变暗。
 */
export function buildFlow(
  tables: TableCard[],
  relations: Relation[],
  boxes: Map<string, CardBox>,
  selected: string | null,
  onSelect: (name: string) => void,
): { nodes: TableFlowNode[]; edges: RelationFlowEdge[] } {
  const active = selected && boxes.has(selected) ? selected : null;
  const neighbours = new Set<string>();
  if (active) {
    neighbours.add(active);
    relations.forEach((relation) => {
      if (relation.fromTable === active) neighbours.add(relation.toTable);
      if (relation.toTable === active) neighbours.add(relation.fromTable);
    });
  }
  const nodes: TableFlowNode[] = [];
  tables.forEach((table) => {
    const box = boxes.get(table.name);
    if (!box) return;
    nodes.push({
      id: table.name,
      type: 'table',
      position: { x: box.x, y: box.y },
      width: box.width,
      height: box.height,
      draggable: false,
      selectable: false,
      data: {
        name: table.name,
        title: tableTitle(table),
        subtitle: table.displayName ? table.name : table.comment,
        subtitleIsName: Boolean(table.displayName),
        rows: cardRows(table),
        fieldCount: table.fieldCount,
        hasHierarchy: table.selfReferences.length > 0,
        selected: table.name === active,
        dimmed: active !== null && !neighbours.has(table.name),
        onSelect,
      },
    });
  });
  const edges: RelationFlowEdge[] = relations.map((relation) => ({
    id: relation.id,
    type: 'relation',
    source: relation.fromTable,
    target: relation.toTable,
    sourceHandle: `out:${relation.fromColumn}`,
    targetHandle: `in:${relation.toColumn}`,
    selectable: false,
    focusable: false,
    data: {
      tier: relation.tier,
      label: relation.label,
      fromMark:
        relation.cardinality === 'MANY_TO_ONE'
          ? 'N'
          : relation.cardinality === 'ONE_TO_ONE'
            ? '1'
            : null,
      toMark: relation.cardinality ? '1' : null,
      dimmed: active !== null && relation.fromTable !== active && relation.toTable !== active,
    },
  }));
  return { nodes, edges };
}
```

- [ ] **Step 6: 类型检查与 lint**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && npm run typecheck && npm run lint
```
Expected: 两条命令都没有输出任何错误或警告，退出码 0。

- [ ] **Step 7: 提交**

```bash
cd $FE && git add package.json package-lock.json src/features/data-graph && git commit -q -F - <<'MSG'
feat(data-graph): 数据层——接口、文案、确定性分层布局

@xyflow/react 12.12.0 + @dagrejs/dagre 3.1.1（锁版本）。布局只依赖表和关系，
按后端顺序喂入 dagre，同一份数据永远同一组坐标；
「一屏看不看得清」按名义画布算，只由数据决定。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 7: 前端——画布（表卡片、关系线、两种显示方式）

**Files:**
- Create: `src/features/data-graph/components/TableCardNode.tsx`
- Create: `src/features/data-graph/components/RelationEdge.tsx`
- Create: `src/features/data-graph/components/DataGraphCanvas.tsx`

**Interfaces:**
- Consumes: Task 6 的 `buildFlow`、`layoutTables`、`fitsOnOneScreen`、`hubTable`、`EXPLORE_ZOOM`、`TableFlowNode`、`RelationFlowEdge`、`FocusRequest`。
- Produces: `DataGraphCanvas` 组件，props 为 `{ graph: SystemGraph; selected: string | null; focus: FocusRequest | null; onSelect: (name: string | null) => void }`，Task 8 的页面会用到。

- [ ] **Step 1: 写表卡片**

`src/features/data-graph/components/TableCardNode.tsx`：

```tsx
import { memo, type KeyboardEvent } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { TableFlowNode } from '../flow';

// 每一行左右各一个不可见的连接点：连线从起点列那一行出发，落到终点列那一行。
function TableCardNode({ data }: NodeProps<TableFlowNode>) {
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      data.onSelect(data.name);
    }
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
      aria-label={data.title === data.name ? data.name : `${data.title}（${data.name}）`}
      onKeyDown={onKeyDown}
      data-testid="dg-card"
      data-table={data.name}
    >
      <div className="dg-card__head">
        <div className="dg-card__title-row">
          <span className="dg-card__title" title={data.title}>
            {data.title}
          </span>
          {data.hasHierarchy ? <span className="dg-badge">有上下级</span> : null}
        </div>
        {data.subtitle ? (
          <div
            className={data.subtitleIsName ? 'dg-card__name' : 'dg-card__note'}
            title={data.subtitle}
          >
            {data.subtitle}
          </div>
        ) : null}
      </div>
      <ul className="dg-card__rows">
        {data.rows.map((row) => (
          <li key={row.name} className="dg-card__row">
            <Handle
              type="target"
              position={Position.Left}
              id={`in:${row.name}`}
              isConnectable={false}
              className="dg-card__handle"
            />
            <span className="dg-card__column">
              {row.isKey ? <span className="dg-card__key" title="唯一标识一行的列" /> : null}
              {row.name}
            </span>
            {row.comment ? (
              <span className="dg-card__comment" title={row.comment}>
                {row.comment}
              </span>
            ) : null}
            <Handle
              type="source"
              position={Position.Right}
              id={`out:${row.name}`}
              isConnectable={false}
              className="dg-card__handle"
            />
          </li>
        ))}
      </ul>
      <div className="dg-card__foot">共 {data.fieldCount} 个字段</div>
    </div>
  );
}

export default memo(TableCardNode);
```

- [ ] **Step 2: 写关系线**

`src/features/data-graph/components/RelationEdge.tsx`：

```tsx
import { memo, type CSSProperties } from 'react';
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import type { RelationFlowEdge } from '../flow';

const MARK_OFFSET_X = 14;
const MARK_OFFSET_Y = 9;

const at = (x: number, y: number): CSSProperties => ({
  transform: `translate(-50%, -50%) translate(${x}px, ${y}px)`,
});

// 实线 = 已确认，虚线 = 推断；端点写 N / 1，线中段写起点列的客户注释。
function RelationEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
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
  const edgeClass = [
    'dg-edge',
    data?.tier === 'INFERRED' ? 'is-inferred' : 'is-confirmed',
    dimmed ? 'is-dimmed' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const overlayClass = (base: string) => (dimmed ? `${base} is-dimmed` : base);
  return (
    <>
      <BaseEdge id={id} path={path} className={edgeClass} />
      <EdgeLabelRenderer>
        {data?.fromMark ? (
          <div
            className={overlayClass('dg-edge-mark')}
            style={at(sourceX + MARK_OFFSET_X, sourceY - MARK_OFFSET_Y)}
          >
            {data.fromMark}
          </div>
        ) : null}
        {data?.toMark ? (
          <div
            className={overlayClass('dg-edge-mark')}
            style={at(targetX - MARK_OFFSET_X, targetY - MARK_OFFSET_Y)}
          >
            {data.toMark}
          </div>
        ) : null}
        {data?.label ? (
          <div className={overlayClass('dg-edge-label')} style={at(labelX, labelY)}>
            {data.label}
          </div>
        ) : null}
      </EdgeLabelRenderer>
    </>
  );
}

export default memo(RelationEdge);
```

- [ ] **Step 3: 写画布（整图显示 / 放大到中心表附近）**

`src/features/data-graph/components/DataGraphCanvas.tsx`：

```tsx
import { useEffect, useMemo } from 'react';
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
import RelationEdge from './RelationEdge';
import TableCardNode from './TableCardNode';
import { buildFlow, type FocusRequest, type RelationFlowEdge, type TableFlowNode } from '../flow';
import { EXPLORE_ZOOM, fitsOnOneScreen, hubTable, layoutTables, type CardBox } from '../layout';
import type { SystemGraph } from '../types';

interface DataGraphCanvasProps {
  graph: SystemGraph;
  selected: string | null;
  focus: FocusRequest | null;
  onSelect: (name: string | null) => void;
}

const nodeTypes = { table: TableCardNode };
const edgeTypes = { relation: RelationEdge };

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const centerOf = (box: CardBox) => [box.x + box.width / 2, box.y + box.height / 2] as const;

function CanvasInner({ graph, selected, focus, onSelect }: DataGraphCanvasProps) {
  const related = useMemo(() => graph.tables.filter((table) => table.related), [graph.tables]);
  // 布局只依赖表和关系；选中、搜索都不会触发重新布局。
  const boxes = useMemo(() => layoutTables(related, graph.relations), [related, graph.relations]);
  const { nodes, edges } = useMemo(
    () => buildFlow(related, graph.relations, boxes, selected, onSelect),
    [related, graph.relations, boxes, selected, onSelect],
  );
  // 整图一屏看得清就整图显示；看不清就放大到关联最多的表附近，配小地图（布局本身不变）。
  const overview = useMemo(() => fitsOnOneScreen(boxes), [boxes]);
  const hub = useMemo(() => hubTable(related, graph.relations), [related, graph.relations]);
  const hubTitle = related.find((table) => table.name === hub)?.displayName ?? hub;
  const { setCenter } = useReactFlow();

  const onInit = (instance: ReactFlowInstance<TableFlowNode, RelationFlowEdge>) => {
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
      <ReactFlow<TableFlowNode, RelationFlowEdge>
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
          表比较多，先显示「{hubTitle}」附近。拖动画布、滚轮缩放，或用搜索找表。
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

- [ ] **Step 4: 类型检查与 lint**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && npm run typecheck && npm run lint
```
Expected: 都没有错误，退出码 0。画布行为要等 Task 8 挂上页面后，由 e2e 验证。

- [ ] **Step 5: 提交**

```bash
cd $FE && git add src/features/data-graph/components && git commit -q -F - <<'MSG'
feat(data-graph): 画布——表卡片、关系线、按可读性决定显示方式

线接在具体字段行上，实线已确认、虚线推断，端点标 N / 1；卡片不可拖拽，
点选只改样式不动坐标。整图一屏读不清时放大到关联最多的表附近并给小地图。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 8: 前端——右侧面板、页面、样式与入口

**Files:**
- Create: `src/features/data-graph/components/TableDetailPanel.tsx`
- Create: `src/features/data-graph/components/DataGraphSidePanel.tsx`
- Create: `src/pages/console/data-graph/DataGraphPage.tsx`
- Create: `src/pages/console/data-graph/data-graph.css`
- Modify: `src/router/index.tsx`（lazy import，并在 `connectors` 路由后面加一条路由）
- Modify: `src/components/atlas/workbenchNav.ts`（import，并在「数据连接」后面加一项）
- Modify: `src/components/icons/AtlasIcons.tsx`（在 `DatabaseIcon` 后面加 `DataGraphIcon`）

**Interfaces:**
- Consumes: Task 6、Task 7 的全部产出。
- Produces: 路由 `/console/data-graph`（`SuperAdminRoute`）、侧栏「数据星图」（`superAdminOnly`）。

- [ ] **Step 1: 写单表详情面板**

`src/features/data-graph/components/TableDetailPanel.tsx`：

```tsx
import { useMemo, useState } from 'react';
import { Alert, Input, Skeleton, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { dataGraphApi } from '../api';
import { relationSentence, relationSource, selfReferenceSentence, tableTitle } from '../text';

interface TableDetailPanelProps {
  connectorId: string;
  tableName: string;
  titleOf: (table: string) => string;
  onBack: () => void;
}

const FIELD_SEARCH_THRESHOLD = 30;

export default function TableDetailPanel({
  connectorId,
  tableName,
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
        message="这张表的详情没有加载出来"
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
  return (
    <div className="dg-detail" data-testid="dg-detail">
      <button type="button" className="dg-link dg-detail__back" onClick={onBack}>
        ← 返回全部关系
      </button>
      <h3 className="dg-detail__title">{title}</h3>
      {detail.displayName ? <div className="dg-detail__name">{detail.name}</div> : null}
      {detail.comment && detail.comment !== detail.displayName ? (
        <p className="dg-detail__comment">{detail.comment}</p>
      ) : null}

      <section className="dg-detail__section">
        <h4>关系</h4>
        {detail.relations.length === 0 && detail.selfReferences.length === 0 ? (
          <p className="dg-muted">暂未发现与其他表的关系</p>
        ) : (
          <ul className="dg-sentences">
            {detail.selfReferences.map((ref) => (
              <li key={`self:${ref.fromColumn}`}>{selfReferenceSentence(title, ref)}</li>
            ))}
            {detail.relations.map((relation) => (
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
        )}
      </section>

      <section className="dg-detail__section">
        <h4>字段（{detail.fields.length}）</h4>
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
      </section>
    </div>
  );
}
```

- [ ] **Step 2: 写右侧面板**

`src/features/data-graph/components/DataGraphSidePanel.tsx`：

```tsx
import { Empty, Tabs } from 'antd';
import TableDetailPanel from './TableDetailPanel';
import { relationSentence, relationSource, tableTitle } from '../text';
import type { SystemGraph } from '../types';

interface DataGraphSidePanelProps {
  graph: SystemGraph;
  selected: string | null;
  titleOf: (table: string) => string;
  onPick: (table: string) => void;
  onBack: () => void;
}

// 未选中表：关系清单 / 未发现关联的表；选中表：该表详情（不在画布上的表也从这里看）。
export default function DataGraphSidePanel({
  graph,
  selected,
  titleOf,
  onPick,
  onBack,
}: DataGraphSidePanelProps) {
  const isolated = graph.tables.filter((table) => !table.related);
  return (
    <aside className="dg-side" aria-label="表与关系" data-testid="dg-side">
      {selected ? (
        <TableDetailPanel
          connectorId={graph.connectorId}
          tableName={selected}
          titleOf={titleOf}
          onBack={onBack}
        />
      ) : (
        <Tabs
          size="small"
          items={[
            {
              key: 'relations',
              label: `关系清单（${graph.relations.length}）`,
              children: graph.relations.length ? (
                <ul className="dg-sentences" data-testid="dg-relation-list">
                  {graph.relations.map((relation) => (
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
                        {relationSentence(relation, titleOf)}
                      </button>
                      <span className="dg-source">{relationSource(relation)}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂未发现可以确认的表关系"
                />
              ),
            },
            {
              key: 'isolated',
              label: `未发现关联的表（${isolated.length}）`,
              children: isolated.length ? (
                <ul className="dg-table-list" data-testid="dg-isolated-list">
                  {isolated.map((table) => (
                    <li key={table.name}>
                      <button type="button" className="dg-link" onClick={() => onPick(table.name)}>
                        <span className="dg-table-list__title">{tableTitle(table)}</span>
                        {table.displayName ? (
                          <span className="dg-table-list__name">{table.name}</span>
                        ) : null}
                        {table.selfReferences.length ? (
                          <span className="dg-badge">有上下级</span>
                        ) : null}
                      </button>
                    </li>
                  ))}
                </ul>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所有表都已连进关系图" />
              ),
            },
          ]}
        />
      )}
    </aside>
  );
}
```

- [ ] **Step 3: 写页面**

`src/pages/console/data-graph/DataGraphPage.tsx`：

```tsx
import { useCallback, useEffect, useMemo, useState } from 'react';
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
import { dataGraphApi } from '@/features/data-graph/api';
import DataGraphCanvas from '@/features/data-graph/components/DataGraphCanvas';
import DataGraphSidePanel from '@/features/data-graph/components/DataGraphSidePanel';
import type { FocusRequest } from '@/features/data-graph/flow';
import { tableTitle } from '@/features/data-graph/text';
import type { SemanticStatus } from '@/features/data-graph/types';
import './data-graph.css';

// 深色工作区：画布、搜索框、右侧面板里的 antd 组件都用暗色算法渲染，和画布同一套底色。
const DARK_THEME = {
  algorithm: theme.darkAlgorithm,
  token: { colorPrimary: '#22d3ee', colorBgContainer: '#0a1b2e' },
};

// 语义层还没给出关系时，画布位置显示的话（设计文档 §6.5）。
function emptyCanvasText(status: SemanticStatus): string {
  if (status === null) return '这个系统的表关系还没整理。在「数据连接」里生成语义层后会自动出现。';
  if (status === 'RUNNING') return '正在整理表关系，完成后刷新页面即可看到。';
  if (status === 'FAILED') return '表关系整理没有成功，可在「数据连接」查看原因。';
  return '暂未发现可以确认的表关系。';
}

export default function DataGraphPage() {
  const [params, setParams] = useSearchParams();
  const [selected, setSelected] = useState<string | null>(null);
  const [focus, setFocus] = useState<FocusRequest | null>(null);
  const [search, setSearch] = useState('');

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

  useEffect(() => {
    setSelected(null);
    setFocus(null);
    setSearch('');
  }, [currentId]);

  const onSelect = useCallback((name: string | null) => setSelected(name), []);
  const pick = useCallback((name: string) => {
    setSelected(name);
    setFocus((previous) => ({ name, seq: (previous?.seq ?? 0) + 1 }));
  }, []);

  const titles = useMemo(
    () => new Map((graph?.tables ?? []).map((table) => [table.name, tableTitle(table)])),
    [graph?.tables],
  );
  const titleOf = useCallback((name: string) => titles.get(name) ?? name, [titles]);

  const counts = useMemo(
    () => ({
      tables: graph?.tables.length ?? 0,
      confirmed: graph?.relations.filter((relation) => relation.tier === 'CONFIRMED').length ?? 0,
      inferred: graph?.relations.filter((relation) => relation.tier === 'INFERRED').length ?? 0,
      isolated: graph?.tables.filter((table) => !table.related).length ?? 0,
    }),
    [graph],
  );

  const searchOptions = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!graph || !keyword) return [];
    return graph.tables
      .filter(
        (table) =>
          table.name.toLowerCase().includes(keyword) ||
          (table.displayName ?? '').toLowerCase().includes(keyword) ||
          (table.comment ?? '').toLowerCase().includes(keyword),
      )
      .slice(0, 20)
      .map((table) => ({
        value: table.name,
        label: (
          <div className="dg-search-option">
            <strong>{tableTitle(table)}</strong>
            {table.displayName ? <span>{table.name}</span> : null}
          </div>
        ),
      }));
  }, [graph, search]);

  const semanticLink = currentId
    ? `/console/connectors/${currentId}/semantic`
    : '/console/connectors';

  return (
    <main className="data-graph-page" data-testid="data-graph-page">
      <header className="data-graph-header">
        <h2 className="data-graph-header__title">数据星图</h2>
        <p className="data-graph-header__lead">
          查看各业务系统里有哪些表、表与表之间怎样关联。关系来自数据连接的语义层，语义层更新后这里自动同步。
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
        <section className="data-graph-blank">
          <Empty
            description={
              <span>
                还没有可以展示的业务系统。<Link to="/console/connectors">去「数据连接」</Link>
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
              onChange={(value) => setParams({ system: String(value) })}
              options={systems.map((system) => ({
                value: system.connectorId,
                label: (
                  <span>
                    {system.displayName || system.name} · {system.tableCount} 张表
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
              message="这个系统的表关系没有加载出来"
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
                  <span>表</span>
                  <strong>{counts.tables}</strong>
                </div>
                <div>
                  <span>已确认关系</span>
                  <strong>{counts.confirmed}</strong>
                </div>
                <div>
                  <span>推断关系</span>
                  <strong>{counts.inferred}</strong>
                </div>
                <div>
                  <span>未发现关联的表</span>
                  <strong>{counts.isolated}</strong>
                </div>
              </section>
              {graph.semanticStatus === 'RUNNING' && graph.relations.length > 0 ? (
                <p className="data-graph-note">语义层正在更新，完成后刷新页面可看到最新关系</p>
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
                        placeholder="搜索表（中文名或表名）"
                        notFoundContent={search.trim() ? '没有匹配的表' : null}
                      />
                    </div>
                    {graph.relations.length > 0 ? (
                      <DataGraphCanvas
                        key={graph.connectorId}
                        graph={graph}
                        selected={selected}
                        focus={focus}
                        onSelect={onSelect}
                      />
                    ) : (
                      <div className="data-graph-empty-canvas" data-testid="dg-empty">
                        <p>{emptyCanvasText(graph.semanticStatus)}</p>
                        {graph.semanticStatus !== 'READY' ? (
                          <Link to={semanticLink}>去「数据连接」</Link>
                        ) : null}
                      </div>
                    )}
                    <div className="data-graph-legend" aria-label="图例">
                      <span>
                        <i className="dg-swatch is-confirmed" aria-hidden />
                        已确认：数据核对通过或业务方确认
                      </span>
                      <span>
                        <i className="dg-swatch is-inferred" aria-hidden />
                        推断：按表结构，尚未核对
                      </span>
                    </div>
                  </div>
                  <DataGraphSidePanel
                    graph={graph}
                    selected={selected}
                    titleOf={titleOf}
                    onPick={pick}
                    onBack={() => setSelected(null)}
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

- [ ] **Step 4: 写样式（配色沿用上一版，卡片尺寸与 `layout.ts` 一致）**

`src/pages/console/data-graph/data-graph.css`：

```css
/* 数据星图。配色沿用上一版（codex/enterprise-data-graph）的深色画布与青色发光；
   卡片尺寸必须与 features/data-graph/layout.ts 的常量一致（头 58 / 行 26 / 底 28 / 宽 240）。 */

.data-graph-page {
  --graph-navy: #06111f;
  --graph-navy-soft: #0a1b2e;
  --graph-border: #17354d;
  --graph-cyan: #67e8f9;
  --graph-blue: #38bdf8;
  --graph-text: #d7eaf5;
  --graph-muted: #86a6ba;
  display: grid;
  min-width: 0;
  gap: 14px;
  color: #172033;
}

/* ---------- 页头与概览 ---------- */

.data-graph-header {
  position: relative;
  padding: 20px 24px;
  overflow: hidden;
  background:
    radial-gradient(circle at 92% 20%, rgb(14 165 233 / 0.12), transparent 22%),
    linear-gradient(135deg, #fbfdff, #f5f9fc);
  border: 1px solid #d9e4ec;
  border-radius: 14px;
}

.data-graph-header::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: linear-gradient(#0891b2, #2563eb);
  content: '';
}

.data-graph-header__title {
  margin: 0 0 6px;
  color: #0b1b2b;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.data-graph-header__lead {
  max-width: 820px;
  margin: 0;
  color: #475569;
}

.data-graph-systems {
  justify-self: start;
  max-width: 100%;
  overflow-x: auto;
}

.data-graph-systems__tag {
  margin-inline: 6px 0;
}

.data-graph-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  background: #fff;
  border: 1px solid #dce3ea;
  border-radius: 12px;
}

.data-graph-summary > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 14px 18px;
  border-right: 1px solid #edf1f5;
}

.data-graph-summary > div:last-child {
  border-right: 0;
}

.data-graph-summary span {
  color: #64748b;
  font-size: 12px;
}

.data-graph-summary strong {
  color: #11243a;
  font-size: 24px;
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
  border: 1px solid #dce3ea;
  border-radius: 14px;
}

/* ---------- 工作区：画布 + 右侧面板 ---------- */

.data-graph-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  height: 680px;
  overflow: hidden;
  background: var(--graph-navy);
  border: 1px solid #102c44;
  border-radius: 16px;
  box-shadow: 0 20px 50px rgb(15 23 42 / 0.14);
}

.data-graph-main {
  position: relative;
  min-width: 0;
  background:
    radial-gradient(circle at 50% 48%, rgb(14 116 144 / 0.16), transparent 38%),
    var(--graph-navy);
}

.data-graph-toolbar {
  position: absolute;
  top: 14px;
  left: 14px;
  z-index: 5;
}

.data-graph-search {
  width: 320px;
}

.dg-search-option {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.dg-search-option span {
  color: #94a3b8;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}

.dg-canvas,
.data-graph-empty-canvas {
  width: 100%;
  height: 100%;
}

.dg-canvas .react-flow {
  background: transparent;
}

.dg-canvas .react-flow__minimap {
  border: 1px solid var(--graph-border);
  border-radius: 10px;
}

.data-graph-hint {
  position: absolute;
  top: 58px;
  left: 14px;
  z-index: 5;
  max-width: 420px;
  padding: 6px 12px;
  color: #bae6fd;
  font-size: 12px;
  background: rgb(5 24 42 / 0.88);
  border: 1px solid #155e75;
  border-radius: 10px;
  backdrop-filter: blur(10px);
}

.data-graph-empty-canvas {
  display: grid;
  place-content: center;
  gap: 10px;
  padding: 24px;
  color: var(--graph-muted);
  text-align: center;
}

.data-graph-empty-canvas p {
  max-width: 420px;
  margin: 0;
  color: var(--graph-text);
}

.data-graph-legend {
  position: absolute;
  bottom: 14px;
  left: 14px;
  z-index: 5;
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding: 7px 12px;
  color: var(--graph-text);
  font-size: 12px;
  background: rgb(5 24 42 / 0.88);
  border: 1px solid #155e75;
  border-radius: 999px;
  backdrop-filter: blur(10px);
}

.data-graph-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dg-swatch {
  display: inline-block;
  flex: 0 0 auto;
  width: 22px;
  height: 0;
  border-top: 2px solid var(--graph-blue);
}

.dg-swatch.is-inferred {
  border-top-style: dashed;
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
  background: linear-gradient(180deg, #0d2438, #0a1b2e);
  border: 1px solid #1d4a66;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgb(2 8 23 / 0.5);
  transition: opacity 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}

.dg-card:hover {
  border-color: #2b7aa3;
}

.dg-card:focus-visible {
  outline: 2px solid var(--graph-blue);
  outline-offset: 2px;
}

.dg-card.is-selected {
  border-color: var(--graph-cyan);
  box-shadow:
    0 0 0 2px rgb(103 232 249 / 0.55),
    0 0 26px rgb(103 232 249 / 0.35);
}

.dg-card.is-dimmed {
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
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.dg-card__title {
  overflow: hidden;
  color: #f0f9ff;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
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
  width: 6px;
  height: 6px;
  min-width: 0;
  min-height: 0;
  background: transparent;
  border: 0;
  opacity: 0;
}

.dg-badge {
  flex: 0 0 auto;
  padding: 0 6px;
  color: #a5f3fc;
  font-size: 10px;
  line-height: 16px;
  background: rgb(8 145 178 / 0.25);
  border: 1px solid rgb(103 232 249 / 0.35);
  border-radius: 999px;
}

/* ---------- 连线 ---------- */

.react-flow__edge-path.dg-edge {
  stroke: var(--graph-blue);
  stroke-width: 1.8;
  transition: opacity 0.2s ease;
}

.react-flow__edge-path.dg-edge.is-inferred {
  stroke-dasharray: 6 5;
  opacity: 0.7;
}

.react-flow__edge-path.dg-edge.is-dimmed {
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

/* ---------- 右侧面板 ---------- */

.dg-side {
  min-width: 0;
  padding: 14px 16px;
  overflow: auto;
  color: var(--graph-text);
  background: linear-gradient(180deg, #0a1b2e, #081625);
  border-left: 1px solid var(--graph-border);
}

.dg-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}

.dg-link:hover {
  color: #f0f9ff;
}

.dg-link:focus-visible {
  outline: 2px solid var(--graph-blue);
  outline-offset: 2px;
}

.dg-sentences,
.dg-table-list,
.dg-fields {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.dg-sentences li {
  display: grid;
  gap: 3px;
  font-size: 13px;
  line-height: 1.5;
}

.dg-sentences li > .dg-swatch {
  margin-top: 9px;
}

.dg-detail .dg-sentences li {
  grid-template-columns: 22px minmax(0, 1fr);
  column-gap: 8px;
}

.dg-detail .dg-sentences li > .dg-source {
  grid-column: 2;
}

.dg-source,
.dg-muted {
  color: var(--graph-muted);
  font-size: 12px;
}

.dg-table-list__title {
  color: #f0f9ff;
}

.dg-table-list__name {
  color: #7dd3fc;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 11px;
}

.dg-detail {
  display: grid;
  gap: 8px;
}

.dg-detail__back {
  color: #7dd3fc;
  font-size: 12px;
}

.dg-detail__title {
  margin: 4px 0 0;
  color: #f0f9ff;
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
  display: grid;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--graph-border);
}

.dg-detail__section h4 {
  margin: 0;
  color: #bfe3f5;
  font-size: 13px;
}

.dg-fields {
  gap: 6px;
}

.dg-fields li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.dg-fields li.is-related .dg-fields__name {
  color: var(--graph-cyan);
}

.dg-fields__name {
  color: #cfe8f6;
  font-family: var(--font-mono, ui-monospace, monospace);
}

.dg-fields__type {
  color: var(--graph-muted);
  font-family: var(--font-mono, ui-monospace, monospace);
}

.dg-fields__comment {
  color: var(--graph-muted);
}

/* ---------- 窄屏与减少动效 ---------- */

@media (max-width: 1100px) {
  .data-graph-workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 560px auto;
    height: auto;
  }

  .dg-side {
    max-height: 420px;
    border-top: 1px solid var(--graph-border);
    border-left: 0;
  }

  .data-graph-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .data-graph-page *,
  .data-graph-page *::before,
  .data-graph-page *::after {
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

- [ ] **Step 5: 接入口（路由、导航、图标）**

`src/router/index.tsx`：在 `const ConnectorListPage = lazy(...)` 下一行加：

```tsx
const DataGraphPage = lazy(() => import('@/pages/console/data-graph/DataGraphPage'));
```

在 `path="connectors"` 那个 `<Route ... />` 结束之后、`path="connectors/:id/semantic"` 之前加：

```tsx
        <Route
          path="data-graph"
          element={
            <SuperAdminRoute>
              <DataGraphPage />
            </SuperAdminRoute>
          }
        />
```

`src/components/atlas/workbenchNav.ts`：在 import 列表的 `DatabaseIcon,` 下一行加 `DataGraphIcon,`；在 `key: 'connectors'` 那一项之后加：

```ts
  {
    key: 'data-graph',
    label: '数据星图',
    path: '/console/data-graph',
    Icon: DataGraphIcon,
    superAdminOnly: true,
  },
```

`src/components/icons/AtlasIcons.tsx`：在 `export const DatabaseIcon = makeIcon(...)` 之后加：

```tsx
export const DataGraphIcon = makeIcon(
  <>
    <circle cx="5" cy="12" r="2.5" />
    <circle cx="17.5" cy="5.5" r="2.5" />
    <circle cx="18" cy="18" r="2.5" />
    <path d="m7.2 10.8 8.1-4.2M7.4 13.1l8.2 3.8M17.7 8v7.5" />
  </>,
);
```

- [ ] **Step 6: 类型检查与 lint**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && npm run typecheck && npm run lint
```
Expected: 都没有错误，退出码 0。

- [ ] **Step 7: 核对文字对比度（spec §6.6，WCAG AA 4.5:1）**

下面每一对都是 `data-graph.css` 里实际用到的「文字色 / 底色」；半透明的底色按叠在画布色上之后的实色填写。改了颜色要同步改这里的清单。

```bash
node --input-type=module <<'JS'
// WCAG 2.x 对比度：data-graph.css 里每一对「文字色 / 实际底色」，半透明底按叠在画布色上算好的实色写。
const pairs = [
  ['页头标题', '#0b1b2b', '#fbfdff'], ['页头说明', '#475569', '#f5f9fc'],
  ['概览标签', '#64748b', '#ffffff'], ['概览数字', '#11243a', '#ffffff'], ['更新提示', '#0369a1', '#ffffff'],
  ['卡片标题', '#f0f9ff', '#0d2438'], ['卡片物理名', '#7dd3fc', '#0a1b2e'], ['卡片说明', '#86a6ba', '#0a1b2e'],
  ['卡片列名', '#cfe8f6', '#0a1b2e'], ['卡片列注释', '#86a6ba', '#0a1b2e'], ['卡片底部', '#86a6ba', '#0a1b2e'],
  ['徽标', '#a5f3fc', '#0b3f56'], ['线上文字', '#bae6fd', '#06111f'], ['端点 N/1', '#67e8f9', '#06111f'],
  ['图例 / 提示', '#d7eaf5', '#05172a'], ['大图提示', '#bae6fd', '#05172a'], ['空画布文字', '#d7eaf5', '#06111f'],
  ['面板正文', '#d7eaf5', '#081625'], ['面板次要', '#86a6ba', '#081625'], ['面板表名', '#7dd3fc', '#081625'],
  ['字段名', '#cfe8f6', '#081625'], ['参与关系的字段', '#67e8f9', '#081625'], ['字段类型', '#86a6ba', '#081625'],
  ['详情小标题', '#bfe3f5', '#081625'], ['详情返回', '#7dd3fc', '#081625'],
];
const lum = (hex) => {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const ratio = (a, b) => { const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05); };
let failed = 0;
for (const [name, fg, bg] of pairs) {
  const r = ratio(fg, bg);
  if (r < 4.5) failed += 1;
  console.log(`${r >= 4.5 ? 'PASS' : 'FAIL'}  ${name.padEnd(8)} ${fg} on ${bg}  ${r.toFixed(2)}:1`);
}
console.log(failed ? `${failed} 对不达 AA（4.5:1）` : '全部达到 AA（4.5:1）');
process.exit(failed ? 1 : 0);
JS
```
Expected: 25 行全是 `PASS`，最后一行是 `全部达到 AA（4.5:1）`。旧版用过的 `#5f8199` 只有 4.21:1，不达标，所以卡片底部和字段类型改用 `var(--graph-muted)`（`#86a6ba`）。

- [ ] **Step 8: 跑 e2e，确认全部通过（连跑两遍，确认稳定）**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && for i in 1 2; do npm run test:data-graph 2>&1 | grep -E "FAIL|passed|首屏"; done
```
Expected: 两遍都是 `=== data-graph: 29/29 passed ===`，没有 `FAIL` 行，首屏耗时一般在 250–400 ms。然后看一眼截图 `e2e/shots/data-graph-v2.png`（小图）和 `e2e/shots/data-graph-v2-big.png`（大图，应放大到「表10」附近并有提示框）。

- [ ] **Step 9: 提交**

```bash
cd $FE && git add src/features/data-graph/components src/pages/console/data-graph src/router/index.tsx \
  src/components/atlas/workbenchNav.ts src/components/icons/AtlasIcons.tsx && git commit -q -F - <<'MSG'
feat(data-graph): 数据星图页面——系统切换、概览、画布、关系清单与单表详情

深色工作区用 antd 暗色算法；右侧未选中时是关系清单 / 未发现关联的表，
选中时是该表详情（句子 + 实线虚线 + 来源说明 + 字段）。仅企业超管可见。

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 9: 真实数据全栈验收（不提交代码）

在本地全栈上用真实数据核对 spec §1 的第 4–6 条，并截图给用户看。前提：Task 4 起的 v2 data-server 还在跑。

- [ ] **Step 1: 在 5181 起新前端**

5180 端口被旧分支的 dev server 占着，不要动它。新前端起在 5181：

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && nohup npx vite --port 5181 --strictPort > /tmp/jm-front-5181.log 2>&1 & disown
for i in $(seq 1 30); do curl -s -o /dev/null -w '%{http_code}' localhost:5181 | grep -q 200 && echo up && break; sleep 1; done
```
Expected: 输出 `up`。

- [ ] **Step 2: 写真实数据检查脚本（放在 `e2e/` 下是为了复用 playwright 依赖；不提交，用完即删）**

保存为 `$FE/e2e/data-graph-live.local.mjs`：

```js
import { launchBrowser, reporter, shot } from './lib.mjs';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:5181';
const FORBIDDEN = ['包含', 'READY', 'RUNNING', 'FAILED', 'INSPECTOR', 'UNKNOWN', '%', 'AI 推测', 'AI 可信度', '置信度'];
const r = reporter('data-graph-live');
const { browser, page } = await launchBrowser();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => {
  if (m.type() === 'error') errors.push(m.text());
});

async function waitForGraph(cards, edges) {
  await page.waitForFunction(
    ([c, e]) =>
      document.querySelectorAll('[data-testid="dg-card"]').length === c &&
      document.querySelectorAll('.react-flow__edge').length === e,
    [cards, edges],
    { timeout: 30_000 },
  );
  let last = '';
  for (let i = 0; i < 30; i += 1) {
    const now = await page.$eval('.react-flow__viewport', (el) => el.style.transform).catch(() => '');
    if (now && now === last) return;
    last = now;
    await page.waitForTimeout(100);
  }
}
const positions = () =>
  page.$$eval('.react-flow__node', (nodes) => Object.fromEntries(nodes.map((n) => [n.getAttribute('data-id'), n.style.transform])));
const chromeText = () =>
  page.$$eval('.data-graph-header, .data-graph-summary, .data-graph-legend, .ant-tabs-nav, .data-graph-systems', (els) =>
    els.map((el) => el.innerText).join('\n'),
  );

try {
  await page.goto(`${BASE}/login`);
  await page.fill('#username', 'admin');
  await page.fill('#password', process.env.E2E_PASSWORD ?? 'admin123');
  await page.locator('.login-submit').click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 15_000 });
  await page.goto(`${BASE}/console/data-graph`);

  // erp_real：8 张卡片、7 条线、全是虚线
  await page.getByText('erp_real · 8 张表').click();
  await waitForGraph(8, 7);
  const url = page.url();
  r.ok('erp_real：7 条线全是推断（虚线）', (await page.locator('.react-flow__edge-path.dg-edge.is-inferred').count()) === 7);
  const summary = (await page.locator('.data-graph-summary').innerText()).replace(/\s+/g, ' ');
  r.ok('erp_real 概览', summary.includes('表 8') && summary.includes('已确认关系 0') && summary.includes('推断关系 7') && summary.includes('未发现关联的表 0'), summary);
  r.ok('行项目明细表用中文名作标题', (await page.locator('[data-testid="dg-card"][data-table="EFI_VOUCHERDTL"] .dg-card__title').innerText()) === '行项目明细表');
  r.ok('EPS_WBSELEMENT 标「有上下级」', (await page.locator('[data-testid="dg-card"][data-table="EPS_WBSELEMENT"] .dg-badge').count()) === 1);
  const chrome = await chromeText();
  const leaked = FORBIDDEN.filter((w) => chrome.includes(w));
  r.ok('界面文案不含运维 / AI 用语（spec §1-6）', leaked.length === 0, leaked.join(','));
  const first = await positions();
  let stable = true;
  for (let i = 0; i < 9; i += 1) {
    await page.goto(url);
    await waitForGraph(8, 7);
    stable = stable && JSON.stringify(await positions()) === JSON.stringify(first);
  }
  r.ok('同一份数据刷新 10 次，坐标完全一致（spec §1-5）', stable);
  const before = errors.length;
  let still = true;
  for (const name of Object.keys(first)) {
    await page.locator(`[data-testid="dg-card"][data-table="${name}"]`).click();
    await page.locator('[data-testid="dg-detail"]').waitFor({ state: 'visible', timeout: 5_000 });
    still = still && JSON.stringify(await positions()) === JSON.stringify(first);
  }
  r.ok('点任意一张表：无报错、位置不变、右侧出详情（spec §1-4）', still && errors.length === before, errors.slice(before).join(' | '));
  await page.screenshot({ path: shot('data-graph-live-erp.png'), fullPage: true });

  // test（klny_erp）：3 张卡片、2 条线、16 张未发现关联
  await page.goto(`${BASE}/console/data-graph`);
  await page.getByText('klny_erp · 19 张表').click();
  await waitForGraph(3, 2);
  const summary2 = (await page.locator('.data-graph-summary').innerText()).replace(/\s+/g, ' ');
  r.ok('test 概览', summary2.includes('表 19') && summary2.includes('推断关系 2') && summary2.includes('未发现关联的表 16'), summary2);
  await page.getByRole('tab', { name: /未发现关联的表/ }).click();
  const isolated = await page.locator('[data-testid="dg-isolated-list"]').innerText();
  r.ok('科目主数据表在「未发现关联的表」里，带「有上下级」', isolated.includes('科目主数据表') && isolated.includes('有上下级'));
  await page.screenshot({ path: shot('data-graph-live-test.png'), fullPage: true });
} finally {
  await browser.close();
}
process.exit(r.summary() ? 0 : 1);
```

- [ ] **Step 3: 跑检查**

```bash
cd $FE && source ~/.nvm/nvm.sh >/dev/null && nvm use 20 >/dev/null && node e2e/data-graph-live.local.mjs
```
Expected: `=== data-graph-live: 9/9 passed ===`。把 `e2e/shots/data-graph-live-erp.png` 和 `e2e/shots/data-graph-live-test.png` 给用户看。

- [ ] **Step 4: 清理**

```bash
rm $FE/e2e/data-graph-live.local.mjs; pkill -f 'vite --port 5181' || true; git -C $FE status --short
```
Expected: `git status` 没有任何输出（`e2e/shots/` 已经被忽略）。v2 的 data-server 保持运行，并告诉用户：本地后端现在是 v2 构建，旧分支的星图接口已经不在了。

- [ ] **Step 5: 交还用户**

汇报以下内容：两个分支的提交列表（`git -C $DS log --oneline main..` 和 `git -C $FE log --oneline main..`）、验收结果、截图。是否合并、是否推送由用户决定。推送时两个仓库要串行，先 data-service，再 jm-agent-front。
