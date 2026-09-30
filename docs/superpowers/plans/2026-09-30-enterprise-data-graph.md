# Enterprise Data Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only, enterprise-wide database relationship graph that combines existing intra-database semantics with explainable AI-inferred cross-database relationships.

**Architecture:** `data-service` builds durable, versioned graph snapshots from existing connection/schema/semantic rows, while `jm-agent-front` renders the latest ready snapshot as an ECharts Canvas network with search, filters, progressive detail, and an accessible relationship list. Graph generation is asynchronous, tenant-scoped, fail-closed on configuration, and completely disconnected from Agent runtime context.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, MySQL 8, JUnit 5, React 18, TypeScript 5.6, Ant Design 5, TanStack Query 5, ECharts 6 Canvas.

**Spec:** `docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md`

## Global Constraints

- The graph is presentation-only: do not add Agent tools, Skill hooks, prompt injection, RAG ingestion, or `SkillRuntimeService` dependencies.
- All graph APIs require `SuperAdminGuard.requireSuperAdmin()` and all three new tables are tenant-aware.
- Cross-database inference consumes only cached schema/comments/semantics and never queries customer business rows.
- Every accepted `CROSS_DB_AI` edge is returned regardless of confidence and always remains labeled as an AI inference.
- Snowflake IDs leave the backend as strings.
- Use the existing ECharts 6 dependency with Canvas renderer; do not add another graph library.
- Keep at most 500 live object nodes in one client graph instance; larger graphs use summaries, paging, or neighborhoods.
- Default retention is three ready snapshots; failed builds never replace the latest ready snapshot.
- Run `mvn install -DskipTests` before module tests whenever `common/*` changes.

## Review Focus

- A deleted connector must disappear immediately from every readable snapshot, including edges where it is only the target; Task 5 adds a deletion-leak integration test.
- Object/field names containing dots, underscores, colons, mixed case, Chinese, or delimiter-like text must never collide; Task 2 adds property-style key tests.
- Malformed, oversized, unknown-enum, or stale-object model output must reject only the build and preserve the last ready snapshot; Tasks 3 and 4 pin this behavior.
- Source metadata changing during a build must enqueue a newer fingerprint instead of silently publishing it as current; Task 4 tests consecutive pending snapshots.
- A graph over the 500-node client budget must remain usable through aggregation and the accessible list without rendering everything; Task 8 adds synthetic large-graph coverage.

---

### Task 1: Tenant-Aware Graph Persistence

**Files:**
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/entity/EnterpriseGraphSnapshot.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/entity/EnterpriseGraphNode.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/entity/EnterpriseGraphEdge.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/mapper/EnterpriseGraphSnapshotMapper.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/mapper/EnterpriseGraphNodeMapper.java`
- Create: `common/common-persistence/src/main/java/com/jimeng/persistence/mapper/EnterpriseGraphEdgeMapper.java`
- Create: `modules/data-server/src/main/resources/db/migration/V20260930__enterprise_data_graph.sql`
- Modify: `common/common-core/src/main/java/com/jimeng/common/core/tenant/JimengTenantLineHandler.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphSchemaContractTest.java`

**Interfaces:**
- Produces: MyBatis entities/mappers for `enterprise_graph_snapshot`, `enterprise_graph_node`, and `enterprise_graph_edge`.
- Produces: mapper methods `claimPending(id, claimAt)`, `latestReady()`, `physicalDeleteNodesByConnector(connectorId)`, `physicalDeleteEdgesByConnector(connectorId)`, and `physicalDeleteBySnapshot(snapshotId)`.

- [ ] **Step 1: Write the migration contract test**

```java
@Test
void migrationDefinesTenantScopedSnapshotNodeAndEdgeTables() throws Exception {
    String ddl = new String(getClass().getResourceAsStream(
            "/db/migration/V20260930__enterprise_data_graph.sql").readAllBytes(), UTF_8);
    assertAll(
            () -> assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS `enterprise_graph_snapshot`")),
            () -> assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS `enterprise_graph_node`")),
            () -> assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS `enterprise_graph_edge`")),
            () -> assertEquals(3, count(ddl, "`tenant_id` varchar(64) NOT NULL")),
            () -> assertTrue(ddl.contains("`source_connector_id` bigint NOT NULL")),
            () -> assertTrue(ddl.contains("`target_connector_id` bigint NOT NULL")));
}
```

- [ ] **Step 2: Run the contract test and verify it fails because the resource is missing**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphSchemaContractTest`

Expected: FAIL because `V20260930__enterprise_data_graph.sql` does not exist.

- [ ] **Step 3: Add the migration, entities, mappers, and tenant whitelist entries**

Use varchar lengths from the spec (`status` 16, `trigger` 32, `node_key`/`edge_key` 128, `evidence` 500), indexes on `(tenant_id,status,completed_at)`, `(snapshot_id,node_type)`, both edge connector IDs, and both edge node keys. Use physical deletion methods for all graph rows; do not add `@TableLogic` behavior to snapshot payload tables.

Mapper CAS signature:

```java
@Update("UPDATE enterprise_graph_snapshot SET status='BUILDING', claim_at=#{claimAt} "
      + "WHERE id=#{id} AND tenant_id=#{tenantId} AND status='PENDING'")
int claimPending(@Param("id") Long id, @Param("tenantId") String tenantId,
                 @Param("claimAt") Date claimAt);
```

- [ ] **Step 4: Build common modules and rerun the contract test**

Run: `mvn install -DskipTests && mvn -pl modules/data-server test -Dtest=EnterpriseGraphSchemaContractTest`

Expected: PASS.

- [ ] **Step 5: Commit persistence**

```bash
git add common modules/data-server/src/main/resources/db/migration modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphSchemaContractTest.java
git commit -m "feat(graph): add tenant-scoped graph snapshot storage"
```

### Task 2: Stable Keys and Deterministic Node/Internal-Edge Assembly

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphKeys.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphAssembler.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphInput.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphKeysTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphAssemblerTest.java`

**Interfaces:**
- Consumes: `Connection`, `ConnectorSchema`, and `ConnectorSemantic` rows.
- Produces: `EnterpriseGraphKeys.database(long)`, `object(long,String,String)`, and `edge(String,List<String>)`.
- Produces: `EnterpriseGraphAssembler.assembleBase(EnterpriseGraphInput)` returning `BaseGraph(nodes, edges, fieldsByNode, coverage)`.

- [ ] **Step 1: Write failing key tests for delimiter-like and Unicode names**

```java
@Test
void objectKeysDoNotCollideWhenNamesContainSeparators() {
    assertNotEquals(
        EnterpriseGraphKeys.object(12L, "TABLE", "a:b_c.中文"),
        EnterpriseGraphKeys.object(12L, "TABLE:a", "b_c.中文"));
}

@Test
void edgeKeyIsOrderStableForUndirectedEdges() {
    assertEquals(
        EnterpriseGraphKeys.undirectedEdge("CROSS_DB_AI", "left", "a", "right", "b"),
        EnterpriseGraphKeys.undirectedEdge("CROSS_DB_AI", "right", "b", "left", "a"));
}
```

- [ ] **Step 2: Run tests and verify missing classes fail compilation**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphKeysTest,EnterpriseGraphAssemblerTest`

Expected: FAIL at test compilation.

- [ ] **Step 3: Implement length-prefixed key encoding plus SHA-256 edge keys**

```java
static String encode(List<String> parts) {
    return parts.stream().map(p -> {
        String value = p == null ? "" : p;
        return value.length() + ":" + value;
    }).collect(Collectors.joining("|"));
}
```

Keep readable node keys; hash edge encodings to a bounded 64-character lowercase hex key.

- [ ] **Step 4: Write assembler tests for object filtering and internal JOIN transfer**

Assert that TABLE/VIEW rows become OBJECT nodes, ENDPOINT rows enter `unsupportedObjectTypes`, fields remain in `fieldSummaryJson`, valid JOIN rows become INTERNAL edges, missing endpoints and `verified=REJECTED` do not become edges, and `STALE` is preserved.

- [ ] **Step 5: Implement the pure assembler without database access**

`EnterpriseGraphInput` must contain fully loaded rows; `EnterpriseGraphAssembler` must be deterministic and side-effect free so build orchestration can be tested separately.

- [ ] **Step 6: Run focused tests**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphKeysTest,EnterpriseGraphAssemblerTest`

Expected: PASS.

- [ ] **Step 7: Commit assembly**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph
git commit -m "feat(graph): assemble stable database graph nodes and edges"
```

### Task 3: Bounded Cross-Database Candidate Retrieval and AI Output Validation

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphCandidateRetriever.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphAiJudge.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphPrompt.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphProperties.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphCandidateRetrieverTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphAiJudgeTest.java`

**Interfaces:**
- Consumes: `BaseGraph.fieldsByNode()` from Task 2 and `EnterpriseGraphProperties`.
- Produces: `List<CrossCandidate> retrieve(Map<String,List<FieldRef>>, int topK)`.
- Produces: `List<EnterpriseGraphEdge> judge(List<CrossCandidate>, Duration timeout)` using `ClaudeService.messagesInternal`.
- Produces: package-visible `parseAndValidate(String json, Map<String,CrossCandidate>)` for deterministic tests.

- [ ] **Step 1: Write bounded-retrieval tests**

Construct 2,000 synthetic fields across 12 connectors. Assert every candidate crosses connector IDs, type families are compatible, each source field returns at most Top-K, and the returned candidate count is at most `fieldCount * topK`.

- [ ] **Step 2: Run retriever tests and verify they fail**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphCandidateRetrieverTest`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement token/type inverted indexes and deterministic ranking**

Score exact normalized field token, shared table/semantic tokens, and compatible type family. Break ties by connector ID, object name, and field name so the same snapshot produces the same prompt ordering.

- [ ] **Step 4: Write AI parser tests**

Cover a valid `related=true` item at confidence 0, valid confidence 100, `related=false`, unknown candidate IDs, missing endpoints, confidence -1/101, unknown cardinality, evidence over 500 characters, code fences around JSON, and malformed JSON. Accepted confidence 0 must still produce a `CROSS_DB_AI` edge.

- [ ] **Step 5: Implement prompt, internal model call, and strict validation**

Request body shape:

```java
Map<String, Object> body = new LinkedHashMap<>();
body.put("model", properties.getModelCode());
body.put("max_tokens", 4096);
body.put("temperature", 0);
body.put("system", EnterpriseGraphPrompt.SYSTEM);
body.put("messages", List.of(Map.of("role", "user", "content", payloadJson)));
Object response = claudeService.messagesInternal(body, timeout);
```

Fail closed when `enabled=true` but `modelCode` is blank. Never fall back silently to a default chat model.

- [ ] **Step 6: Run focused tests**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphCandidateRetrieverTest,EnterpriseGraphAiJudgeTest`

Expected: PASS.

- [ ] **Step 7: Commit inference**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph
git commit -m "feat(graph): infer bounded cross-database relationships"
```

### Task 4: Durable Queue, Atomic Snapshot Build, and Recovery

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphQueueService.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphBuildService.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphSourceLoader.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphReconciler.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/web/StreamExecutorConfig.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphQueueServiceTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphBuildServiceTest.java`

**Interfaces:**
- Consumes: Task 1 mappers, Task 2 assembler, Task 3 retriever/judge.
- Produces: `GenerationAck request(String trigger)`, `void drain()`, `void build(Long snapshotId)`, and scheduled `void reconcile()`.
- Produces: executor bean named `enterpriseGraphExecutor`.

- [ ] **Step 1: Write queue tests for idempotency, CAS, and changed-during-build**

Assert the same fingerprint creates one row, a second fingerprint while the first is BUILDING creates a second PENDING row, only one worker wins `claimPending`, and disabled configuration returns a non-started acknowledgement without inserting rows.

- [ ] **Step 2: Run queue tests and verify they fail**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphQueueServiceTest`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement source fingerprinting and durable requests**

Fingerprint sorted connection IDs/statuses, schema object hashes/sync times, relevant OBJECT/FIELD/JOIN semantic update times, `modelCode`, prompt version, and builder version. Do not include wall-clock time.

- [ ] **Step 4: Write build tests for atomic publication and last-ready fallback**

Mock inference success and failure. Assert nodes/edges are written only under BUILDING snapshot ID; status becomes READY only after all writes; malformed model output marks the new batch FAILED; `latestReady` remains the previous snapshot.

- [ ] **Step 5: Implement build orchestration and isolated executor**

Use `MdcAsyncSupport.wrap`, set/clear the recorded tenant explicitly, batch mapper inserts, and update the snapshot to READY last. Add a single-thread executor with a bounded queue and `CallerRunsPolicy` avoided—the request thread must never execute a full graph build.

- [ ] **Step 6: Implement stale BUILDING recovery and retention cleanup**

`@Scheduled(fixedDelayString="${enterprise-graph.reconcile-interval-ms:60000}")` marks expired claims FAILED, requeues current fingerprints, drains PENDING rows, and retains only the configured number of READY snapshots.

- [ ] **Step 7: Run focused tests**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphQueueServiceTest,EnterpriseGraphBuildServiceTest`

Expected: PASS.

- [ ] **Step 8: Commit orchestration**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph modules/data-server/src/main/java/com/jimeng/dataserver/web/StreamExecutorConfig.java modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph
git commit -m "feat(graph): build and recover versioned graph snapshots"
```

### Task 5: Source Change Triggers and Immediate Deletion Purge

**Files:**
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/ConnectorAdminController.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSchemaService.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/generation/SemanticGenerationFinalizer.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorSemanticDeriveService.java`
- Modify: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/service/ConnectorService.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphTriggerTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphDeletionPurgeTest.java`

**Interfaces:**
- Consumes: `EnterpriseGraphQueueService.request(trigger)`.
- Produces: `EnterpriseGraphQueueService.purgeConnector(long connectorId)` called after connector deletion commits.

- [ ] **Step 1: Write trigger tests**

Pin one request after successful schema refresh and semantic publication, no request after guarded/failed refresh, and no exception propagation when graph queueing fails.

- [ ] **Step 2: Write the deletion-leak integration test**

Seed a ready snapshot where connector A is the source of one edge and target of another. Delete/purge A and assert all A nodes plus both edges are physically absent while connector B nodes remain.

- [ ] **Step 3: Run tests and verify they fail**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphTriggerTest,EnterpriseGraphDeletionPurgeTest`

Expected: FAIL because triggers and purge do not exist.

- [ ] **Step 4: Add best-effort queue calls at successful change boundaries**

Queue failures must be logged and swallowed after the primary operation succeeds. Do not place graph build calls inside connector transactions.

- [ ] **Step 5: Add synchronous physical purge after connector deletion**

Delete edges by `source_connector_id = ? OR target_connector_id = ?`, then nodes by connector ID, across all snapshots in the current tenant. If purge fails, fail the delete request rather than knowingly leaving deleted metadata readable.

- [ ] **Step 6: Run focused tests**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphTriggerTest,EnterpriseGraphDeletionPurgeTest`

Expected: PASS.

- [ ] **Step 7: Commit triggers**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph
git commit -m "feat(graph): refresh snapshots when connector metadata changes"
```

### Task 6: Super-Admin Read API and Progressive Graph DTOs

**Files:**
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphController.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphQueryService.java`
- Create: `modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphDtos.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphQueryServiceTest.java`
- Create: `modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph/EnterpriseGraphControllerTest.java`

**Interfaces:**
- Produces: `/data/admin/enterprise-graph/{status,overview,objects,neighborhood,search,nodes/{nodeKey},edges/{edgeKey},rebuild}`.
- Produces DTO classes `GraphStatusView`, `GraphSliceView`, `GraphNodeView`, `GraphEdgeView`, `GraphSearchHit`, and `CursorPage<T>` with string IDs.

- [ ] **Step 1: Write query tests for latest-ready fallback, cursor order, and truncation**

Assert BUILDING/FAILED batches are not read, overview works from the previous READY batch, `limit` clamps to 500, neighborhood returns `truncated=true` when capped, and unknown node keys return a business not-found error without exposing another tenant.

- [ ] **Step 2: Run query tests and verify they fail**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphQueryServiceTest`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement query service and DTO conversion**

Overview returns DATABASE nodes, aggregate connector-to-connector edges, and representative objects ordered by importance. Object and neighborhood endpoints return only rows belonging to the selected READY snapshot. Convert every long ID with `String.valueOf`.

- [ ] **Step 4: Write controller permission and parameter tests**

Assert every endpoint invokes the super-admin guard, blank search is rejected, depth clamps to 1–2, cursor is opaque, and rebuild returns acknowledgement without waiting.

- [ ] **Step 5: Implement controller routes**

Use `@RequestMapping("/data/admin/enterprise-graph")`; do not add these endpoints to `ConnectorAdminController`.

- [ ] **Step 6: Run focused tests**

Run: `mvn -pl modules/data-server test -Dtest=EnterpriseGraphQueryServiceTest,EnterpriseGraphControllerTest`

Expected: PASS.

- [ ] **Step 7: Commit API**

```bash
git add modules/data-server/src/main/java/com/jimeng/dataserver/ai/connector/graph modules/data-server/src/test/java/com/jimeng/dataserver/ai/connector/graph
git commit -m "feat(graph): expose progressive enterprise graph API"
```

### Task 7: Frontend Contract, Route, and Navigation

**Files:**
- Create: `../jm-agent-front/src/features/data-graph/types.ts`
- Create: `../jm-agent-front/src/features/data-graph/api.ts`
- Modify: `../jm-agent-front/src/router/index.tsx`
- Modify: `../jm-agent-front/src/components/atlas/workbenchNav.ts`
- Modify: `../jm-agent-front/src/components/icons/AtlasIcons.tsx`
- Create: `../jm-agent-front/src/pages/console/data-graph/DataGraphPage.tsx`

**Interfaces:**
- Consumes: Task 6 API.
- Produces: `dataGraphApi.status/overview/objects/neighborhood/search/node/edge/rebuild`.
- Produces route `/console/data-graph` and super-admin-only nav item “数据星图”.

- [ ] **Step 1: Add exact wire types and API methods**

Run all Task 7 commands from `../jm-agent-front`.

Define discriminated unions for `DATABASE | OBJECT` and `CONTAINS | INTERNAL | CROSS_DB_AI`; keep `confidence` as `number | string | null` at the wire edge and normalize it in `api.ts`.

- [ ] **Step 2: Add a lazy route and guarded navigation item**

Place “数据星图” immediately after “数据连接”, set `superAdminOnly: true`, and use a dedicated SVG network icon rather than an emoji.

- [ ] **Step 3: Create a thin page shell with status/overview queries**

Render explicit states for no READY snapshot, BUILDING with old data, FAILED with old data, and READY. Preserve cached overview when a background refetch fails.

- [ ] **Step 4: Run frontend checks**

Run: `npm run typecheck && npm run lint`

Expected: PASS.

- [ ] **Step 5: Commit frontend contract**

```bash
git add src/features/data-graph src/pages/console/data-graph src/router/index.tsx src/components/atlas/workbenchNav.ts src/components/icons/AtlasIcons.tsx
git commit -m "feat(graph): add enterprise data graph route"
```

### Task 8: Canvas Knowledge-Graph Experience and Accessible Relationship List

**Files:**
- Create: `../jm-agent-front/src/features/data-graph/components/DataGraphCanvas.tsx`
- Create: `../jm-agent-front/src/features/data-graph/components/DataGraphToolbar.tsx`
- Create: `../jm-agent-front/src/features/data-graph/components/DataGraphInspector.tsx`
- Create: `../jm-agent-front/src/features/data-graph/components/DataGraphRelationList.tsx`
- Create: `../jm-agent-front/src/features/data-graph/model.ts`
- Create: `../jm-agent-front/src/pages/console/data-graph/data-graph.css`
- Modify: `../jm-agent-front/src/pages/console/data-graph/DataGraphPage.tsx`
- Create: `../jm-agent-front/e2e/data-graph-check.mjs`
- Modify: `../jm-agent-front/package.json`

**Interfaces:**
- Consumes: normalized Task 7 models.
- Produces: `toEChartsOption(slice, filters, positions)` and `mergeGraphSlice(current, incoming, nodeBudget=500)`.
- Produces: ECharts Canvas view plus table/list fallback with equivalent relationship details.

- [ ] **Step 1: Add a browser E2E fixture for a synthetic large graph**

Run all Task 8 commands from `../jm-agent-front`. Add `"test:data-graph": "node e2e/data-graph-check.mjs"` to `package.json`.

Intercept graph endpoints with 12 connectors and 2,000 object records. Assert initial overview renders, the DOM exposes “当前渲染 500 / 2000” or fewer live nodes, search can locate an unloaded table, selecting it loads its neighborhood, and switching to “关系列表” exposes source/target/confidence text.

- [ ] **Step 2: Run E2E and verify it fails on the missing page UI**

Run: `node e2e/data-graph-check.mjs`

Expected: FAIL because graph controls and canvas do not exist.

- [ ] **Step 3: Implement pure model merge, filters, and stable visual encoding**

Clamp live object nodes to 500, preserve selected/focused nodes, evict least-recently-used peripheral nodes, retain connector cluster nodes, and key saved positions by `snapshotId:nodeKey`. Use solid lines for INTERNAL, orange dashed lines for CROSS_DB_AI, and red dotted lines plus text for STALE.

- [ ] **Step 4: Implement dynamically imported ECharts Canvas**

Initialize with `{ renderer: 'canvas' }`, dispose on unmount, resize via `ResizeObserver`, use force layout only for the live slice, disable entrance animation under `prefers-reduced-motion`, and never call `setOption` after disposal.

- [ ] **Step 5: Implement search, filters, focus, inspector, and list view**

Include connection multi-select, edge type, confidence range, “仅看上下游”, fit-to-view, rebuild, node detail, edge explanation, and a semantic table of relationships. Icon-only buttons require `aria-label`; every interactive control must be keyboard reachable.

- [ ] **Step 6: Style the approved dark data-atlas direction**

Use a deep navy graph canvas, restrained cyan/blue node glow, orange AI edges, high-contrast text, visible 2px focus rings, and no scanline/glitch effects. Keep the surrounding Atlas console navigation unchanged.

- [ ] **Step 7: Run E2E, typecheck, and lint**

Run: `node e2e/data-graph-check.mjs && npm run typecheck && npm run lint`

Expected: PASS.

- [ ] **Step 8: Commit the graph experience**

```bash
git add src/features/data-graph src/pages/console/data-graph e2e/data-graph-check.mjs package.json
git commit -m "feat(graph): render scalable enterprise data atlas"
```

### Task 9: Release Documentation and Full Verification

**Files:**
- Modify: `docs/RELEASE-CHECKLIST-connector.md`
- Create: `docs/config-changes/2026-09-30-enterprise-data-graph.md`
- Modify: `../jm-agent-front/e2e/README.md`

**Interfaces:**
- Consumes: completed backend and frontend.
- Produces: exact DDL/config/rollback instructions and final evidence.

- [ ] **Step 1: Document rollout and rollback**

List the migration file, the seven `enterprise-graph.*` settings, enabled-default false behavior, schema self-check queries for all three tables, model configuration failure mode, staged enablement, and rollback order “disable config → hide frontend route → retain tables”. Never include real secrets.

- [ ] **Step 2: Run backend common build and focused graph suite**

Run: `mvn install -DskipTests && mvn -pl modules/data-server test -Dtest='EnterpriseGraph*'`

Expected: BUILD SUCCESS and all graph tests pass.

- [ ] **Step 3: Run broader connector regression tests**

Run: `mvn -pl modules/data-server test -Dtest='ConnectorSchemaServiceTest,ConnectorSemantic*Test,EnterpriseGraph*'`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run frontend checks**

Run: `npm run typecheck && npm run lint && node e2e/data-graph-check.mjs`

Expected: all commands exit 0.

- [ ] **Step 5: Inspect diffs and verify repository cleanliness**

Run in each repository: `git diff --check && git status --short --branch`

Expected: no whitespace errors; only intended commits differ from `origin/main`.

- [ ] **Step 6: Commit documentation**

Backend:

```bash
git add docs/RELEASE-CHECKLIST-connector.md docs/config-changes/2026-09-30-enterprise-data-graph.md
git commit -m "docs(graph): add data atlas rollout checklist"
```

Frontend:

```bash
git add e2e/README.md
git commit -m "docs(graph): document data atlas checks"
```
