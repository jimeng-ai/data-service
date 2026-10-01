# 数据星图设计（v3：给人看的业务对象关系图）

日期：2026-10-01（v2：2026-09-30）

关联仓库：`data-service`、`jm-agent-front`（`jm-admin` 不改代码，只做验收）

取代：本文件的 v2（提交 `7fc0e05`、`e7f843c`）。v2 已在两个仓库的 `feat/data-graph-v2` 分支上实现并验收（后端
`91bf5cb..9ccddb1`，前端 `4bd9d76..6cacb44`），本版在它上面继续做。§11 列出沿用和改动的部分。

> **2026-10-01 修订（v3 合进 main 之后、上线之前）：星图不再单独成菜单，收进「数据连接」。** 每个库的卡片上有
> 「查看星图」，一个库一页；和数据连接一样只给企业超管，v3 原来新增的「数据星图」模块撤掉。§1 第 4 条、§3、§7.2、
> §8.1、§8.4、§9、§12 已按此改。

## 1. 目标与验收

**语义层是写给 AI 的说明书；数据星图是写给人看的。** 用业务的说法讲清楚一个库，看的人要一眼看懂两件事：系统里有哪些业务对象（采购订单、供应商、凭证、会计科目……），以及这些对象之间怎样关联（每条采购订单明细
对应一个供应商，每条凭证行记在一个会计科目上）。

验收全部在本地 `test` 租户上逐条核对，前提是「补全链」（§4）已经跑完。租户里有两个系统：连接 `test`（klny_erp 库，
19 张表）和连接 `erp_real`（8 张表）。

1. **关系覆盖**：
   - klny_erp：附录 A1 里由命名规则推出的 33 条，除了被采样核对否掉的（数据不支持，§7.1 第 6 行不画），全部出现在
     星图上（实线或虚线都算）；被否掉的那几条连同包含率写进验收记录。2026-10-01 本地库上否掉 1 条
     （`EFI_ORIGINALTRANSDTL.VOUCHERDTLID`：采样 20 个取值、命中 0——本地库各表的样例行是分别抽的，彼此不连着）。
   - 记录项（不算通过与否）：模型那一遍这次找出了「采购订单明细 → 采购订单」「销售订单明细 → 销售订单」里的哪几对，
     写进验收记录。它们在语义层里，星图上要等采样核对通过或业务方确认才画（§7.1 第 7′ 行）；模型那一遍有随机性，
     2026-10-01 三次试跑分别找出了 0、1、1 对（见 §14 第 3 条）。
   - erp_real：v2 已有的 7 条仍在；附录 A2 里命名规则推出的 16 条（其中 5 条与已有的重合），除了被采样核对否掉的，
     全部出现，合计至少 18 条减去被否掉的条数。
   - 终点不是对方唯一键的关系，只有已确认的（数据核对通过或业务方确认）才出现。
2. **业务名称**：两个系统全部 27 个对象的标题都来自业务视图（`nameSource=BUSINESS_VIEW`）。
3. **默认视图里没有代码**：画布、概览、右侧面板默认展开的内容里，不出现该系统的任何物理表名，也不出现长度 ≥ 4 的
   物理字段名（不区分大小写，按完整单词匹配）。折叠的「技术信息」区除外。
4. **入口与权限**（2026-10-01 修订）：
   - 侧栏没有单独的「数据星图」。「数据连接」里能自描述的连接（数据库类）卡片上有「查看星图」，HTTP 接口这类没有表结构的
     连接没有；点进去是这个库自己的星图（`/console/connectors/:id/graph`）；
   - 只给企业超管。成员（包括以前被授予过「数据星图」模块的）侧栏没有入口；直接敲地址显示「仅企业超管可访问」；接口返回
     无权（HTTP 200 加业务码 4001，不会被踢回登录页）；
   - jm-admin 的角色授权里没有「数据星图」一项；角色上以前授过的这一项不再回显，保存角色不受影响。
5. **自动同步**：语义层生成完成后，补全链在后台自动跑完，本地不超过 10 分钟。跑的过程中页面照常可看，只多一行
   「业务名称整理中」的提示。
6. **说人话**：业务文字（名称、说明、领域、关系角色名）用附录 B 的禁用词和 §6.2 的代码扫描检查，命中数为 0。
7. **v2 的验收继续成立**：布局确定；点选时卡片不动；界面不出现运维用语和 AI 用语；语义层一改，下次打开即生效；
   语义层写给 AI 的原文永不出网。

## 2. 背景：为什么又改

1. **关系太少。** v2 在本地实测，klny_erp 19 张表只画出 2 条关系，其余的表都掉进了右侧清单。业务方的反馈是：「要体现
   对象与对象之间的关联关系，而不是单纯的列出来」。可单看字段命名就能推出 35 条关系（附录 A1）。
2. **语义层漏推关系的根因**（2026-09-30 排查，代码位置见附录 C）：
   - 推导按 6 万字符的摘要上限分批，按表名字母序装批。提示词要求「没出现的表不要写关系」，所以跨批次的关系在规则上
     就被排除了。
   - 输出顺序是 表用途 → 字段含义 → 关系。16000 的 max_tokens 一截断，最先丢的就是关系。
   - 一张表只要有表用途就算「已覆盖」，增量补写不会回头补它的关系；也没有「只重算关系」的入口；整体重新生成又会
     按同样的方式切批。
   - 没有任何确定性的关系发现：声明的外键只用来给表排序，命名规律和唯一键都没用上。
3. **已有的错关系会挡住对的关系。** 语义层每一列最多只能有一条关系（由唯一键决定）。klny_erp 的
   `EFI_VENDOR_CPYCODEDTL.COMPANYCODEID` 已被写成指向兄弟明细表的同名列：终点不是唯一键，也没核对出结论。如果只补
   空缺，正确的那条（指向公司代码）永远写不进去。
4. **v2 是给 IT 看的画法。** 卡片列字段，线上标 `VENDORID → OID`，端点标 N/1。看的人换成业务人员和产品后，这些都是噪音。

## 3. 定位与非目标

**定位：同一份理解，给人另做一种呈现。** 事实只有一份（结构快照 + 语义层）。给人看的文字是这份理解的「人话版」：
由模型按面向业务的要求改写，单独存放，不是语义层原文。人看到的就是 AI 理解的内容，只是换了说法，所以看这张图，
也是在检查 AI 有没有理解对业务。

**有意为之的连带影响：** 关系发现补的是语义层本身，所以 Agent 查数时看到的关系也会变多、变准。

**本版明确不做：**

- 把几张表合成一个业务对象（比如把采购订单表头和明细合并）。一张表就是一个对象，表头和明细之间用连线表示。
- 挑选关键字段、展示指标口径。
- 在星图上纠正 AI 的理解。这是下一版最值得做的，业务视图已为此留出 `source=HUMAN`。
- 给普通成员开放。星图在「数据连接」里，和数据连接一样只给企业超管（§9）。
- 跨系统的关系。
- 客户自己修改业务名称的界面（数据层面已支持 `HUMAN` 行优先）。

## 4. 架构

| 层 | 内容 | 由谁产出、何时产出 |
|---|---|---|
| 事实 | `connector_schema` + `connector_semantic`（含关系和核对结论） | 已有。本版新增「关系发现」（§5）补全 JOIN 行 |
| 给人看的文字 | 业务视图 `connector_business_view`：对象的业务名、一句说明、业务领域；关系的角色名 | 新增（§6）。补全链里异步生成，只补缺的和输入变了的 |
| 呈现 | 数据星图接口 + 前端对象地图 | 每次请求现算：关系判定沿用 v2（§7.1），文字取业务视图，缺了按 §6.3 兜底 |

**补全链**（每个连接同一时刻只跑一条，跑在 `semanticStageExecutor` 上）：

```text
触发：规则推导成功 / 增量补写成功 / agent 生成定稿 / 定时对账发现输入变了
  ① 关系发现：规则候选 + 整库模型一遍 → 新增或替换 JOIN 行
  ② 采样核对：派发原有的采样核对，范围 = 触发方原本要核对的表 ∪ ① 动过的表
  ③ 业务文字：业务领域 → 对象名称与说明 → 关系角色名；只补缺的和输入变了的
```

**触发与顺序：**
- ② 是异步派发的，沿用它原有的闸门和补跑机制；它和 ③ 同时进行：一个查客户库，一个调模型，互不依赖。核对范围为空时
  （比如定时对账触发、① 又没有动任何表）不派发。
- 规则推导成功、增量补写成功后，原来是直接派发采样核对；现在改为派发补全链，由 ② 接着派发，不会重复核对。
- agent 生成定稿后，原来就不做采样核对（`SemanticGenerationFinalizer` 明确不派发），补全链在这条路径上跳过 ②，保持现状。
- 已有的其他采样核对入口不变：手工「验证表关系」、连接变更后、刷新结构后补标形态。
- 运行中又来了触发：不排队。跑完后，定时对账会按输入指纹发现变化再补跑，最多晚 10 分钟。为此收尾记的是这一轮**开始时**
  的输入指纹：记跑完时的，运行中别处落下的改动（又重新生成了语义层、刷新了结构）就被一并吞掉，再也不会补跑。代价是
  这一轮自己写下的关系也算变化，紧跟着多一轮空跑（不问模型，业务文字也没有缺的）。

**定时对账：**
- 每 10 分钟一次，每次最多处理 1 个连接。
- 挑选条件：输入指纹与上次尝试时不同（从没跑过的也算）；或上次失败且距上次尝试已满 6 小时。
- 线程池满被拒绝时不报错，等下一次对账。
- 输入指纹包括：结构快照里每个对象的名称、`content_hash`、唯一键、外键；语义层 OBJECT 行的说明；JOIN 行的两端、
  状态、业务方结论，以及「是否已核对通过」这一位。不含核对结论（`verified`）本身，否则每次核对完都会触发一次重跑；
  只带「是否已核对通过」这一位：采样否掉的关系不起角色名，它后来若核对通过就会画出来，这一位让补全链再跑一轮给它起名。

**开关：** `connector.semantic.enrichment.enabled`，代码默认 true。关掉后：
- 三个触发点退回原来的行为（推导成功后直接派发采样核对）；
- 定时对账不跑；
- 页面照常用已有的数据。

## 5. 语义层：关系发现（新增）

### 5.1 输入

当前连接结构快照里的全部对象（表名、去掉平台后缀的表注释、唯一键、声明的外键）、每个对象语义层说明的第一句，以及现有的 JOIN 行。

### 5.2 规则候选（确定性、通用，不写死任何表名或业务词）

1. **声明的外键。** `MySqlSession.describe` 追加读取 `information_schema.KEY_COLUMN_USAGE` 中同库的
   `REFERENCED_TABLE_NAME` / `REFERENCED_COLUMN_NAME`，写入快照的 `detail_json.extra.foreign_keys`。
   - 它和 `unique_keys` 放在同一处，性质也相同：刷新结构后才会有；不进 `content_hash`，不会引起漂移。
   - 读不到时就当没有，不报错。Doris、StarRocks 这类自己实现 `information_schema` 的引擎就属于这种情况。
2. **命名规律。** 同时满足以下条件的列，提出一条候选：
   - 列名去掉 `_`、转小写后以 `id` 结尾，去掉这个 `id` 之后的「词干」至少 4 个字符；
   - 该列本身不是本表的单列唯一键；
   - 另一张表（不含本表）的表名按 `_` 切开后，某个后缀去掉 `_`、转小写后等于词干，或是词干的结尾：
     - 这个后缀至少 4 个字符；
     - 以 `s` 结尾的后缀，去掉 `s` 的形式也参与比较；
     - 例如词干 `receiptvendor` 以 `vendor` 结尾，对上 `D1_VENDOR`；
   - 多张表都对得上时，取对上的后缀最长的那张；最长的有并列就放弃，留给模型那一遍；
   - 目标列取那张表的单列主键；没有主键时，取它唯一的那个单列唯一键；都没有就放弃。

   **实测（2026-10-01，用本地两个系统的真实快照）：**
   - klny_erp 推出 33 条，恰好是附录 A1 的前 33 条，没有一条多余；
   - erp_real 推出 16 条（附录 A2）；
   - 规则不连本表。试过允许连本表，会多出 `EPS_PROJECT.PROJECTID → EPS_PROJECT.OID` 这类误报（项目表里的
     `PROJECTID` 是项目自己的编号，不是指向上级项目）。自关联只来自语义层和模型那一遍。

### 5.3 模型一遍（整库一次看全）

- **给模型三样东西：**
  - 「目标索引」：所有表都给，每张表只有表名、表注释、语义层一句话说明、唯一键；
  - 「待判列」：名字以 `id` / `code` / `no` / `num` / `key` 结尾、不是本表单列唯一键、还没有关系也没有规则候选的列，
    附带列注释；
  - 已知关系（现有 JOIN 行加规则候选），要求不要重复。
- **只让它输出关系**（不写表用途和字段含义），格式为 JSON，一列最多指向一个目标。证据只允许两种：NAME（命名规律，
  包括「明细 / 表头」这类表名成对出现的规律）和 COMMENT。不许写 GUESS。
- **装不下就分片：** 按「待判列」分片，每一片都带完整的目标索引，保证每一片都看得见所有表。每片摘要最多 60000 字符，
  max_tokens 为 8000。
- 模型和调用方式与语义层推导相同：`connector.semantic.infer-model`，经 `claudeService.messagesInternal`（不带工具，只调一轮）。
- **这一遍的产出只进语义层，给 Agent 当线索；星图要等采样核对通过或业务方确认之后才画**（§7.1 第 7′ 行）。
  2026-10-01 用本地两个真实库试跑了三次：
  - 模型会把找不到对象的列也写进来，随手指向公司代码表的主键，备注写着「库中无对应主数据表」「宁缺毋滥」，
    自报把握 0–50；
  - 提示词要求「把握不低于 70 才写」之后，它把错线（「币种 → 公司代码」「物料 → 销售订单明细」）也报成 70；
  - 它推对的也不少：「售达方、送达方、付款方 → 客户」「明细的来源单 → 单据头」「上级 → 自身」。

  所以只靠提示词和自报把握挡不住误报。这些终点又多是主键，不挡的话 §7.1 第 8 行会把它们画成虚线。
  保留两道过滤：自报把握低于 60 的不收（挡的是「占位条目」），每片最多问 200 列（答案要装得进 max_tokens）。

### 5.4 写入

新增一个只写 JOIN 行的方法，逐列按下表处理：

| 这一列现有的 JOIN 行 | 候选来自外键或命名规则 | 候选来自模型 |
|---|---|---|
| 没有 | 新增 | 新增 |
| 人工或导入的行（`HUMAN` / `IMPORTED`），或带业务方结论 | 不动 | 不动 |
| 推断的行，且已核对通过（`verified=CONFIRMED`） | 不动 | 不动 |
| 推断的行，终点列不是终点表的单列唯一键（或终点已不在快照里），且未核对通过 | **替换** | 不动 |
| 其余 | 不动 | 不动 |

- 新增和替换都走 `SemanticRowAssembler` 的同一套过滤：两端都存在且名字完全一致、丢弃 GUESS、每列去重。新写的行
  `status=DRAFT`、`verified=NONE`。
- 替换时整行按候选重写，并在 `detail_json.replaced_target` 里留下原来的终点，方便排查。§2 第 3 条那一例就是这样修好的。
- `detail_json.origin` 记录来源：`FK` / `NAME_RULE` / `RELATION_PASS`。
- 语义层整体重新推导会擦掉这些行，补全链会紧接着重跑 ①，缺失只存在于两步之间那一小段时间。

### 5.5 顺带修（v2 审查发现）

`annotateJoin`（业务方在对话里确认或否认一条关系）每次都新建 `detail_json`，只放终点、结论和依据，原来的
`join_kind`、`discriminator_column`、`composite_columns` 都丢了。改为保留这些结构键。否则一条业务方确认过的多态关系
会被画成无条件的实线，Agent 也会因此少掉判别条件。

## 6. 业务视图：给人看的文字（新增）

### 6.1 数据

新表 `connector_business_view`（DDL 见附录 D），每行是一个对象或一条关系的「人话」：

- **OBJECT 行：**
  - `display_name`：业务名，不超过 12 个字；
  - `summary`：一句话，不超过 50 个字，说清一条记录代表什么、主要记了什么；
  - `domain`：业务领域，不超过 8 个字。
- **RELATION 行：** 键是「起点表 + 起点列」，和 JOIN 行一一对应。`display_name` 是关系的角色名，不超过 10 个字，
  比如「总账科目」「所属订单」「收款供应商」。
- **`source`：** `MODEL` 或 `HUMAN`。`HUMAN` 行永远不会被模型覆盖。
- **`input_hash`：** 生成时所用输入的 SHA-256，输入变了才重新生成。
  - OBJECT 行的输入：表名、表注释、语义层说明。
  - RELATION 行的输入：起点对象的业务名、起点列名及其注释或语义层字段说明、终点对象的业务名。
- **不产生软删死行**，这是唯一键不含 `deleted` 的前提，做法同 `connector_semantic`：
  - `MODEL` 行需要删除时物理删除；
  - `HUMAN` 行只原地更新。

新表 `connector_enrichment_state`，每个连接一行，记录：
- 认领时间 `claim_at`，30 分钟过期；
- 上次结果 `last_status`（`READY` / `FAILED`）；
- 上次尝试时的输入指纹、上次尝试时间；
- 上次模型关系那一遍的输入指纹：输入没变就不再问模型（§5.3），免得每轮采样核对之后都重问一遍。指纹按这一遍**写完之后**的
  样子算——它自己写下的关系（规则候选、模型产出）不算输入变化，否则之后不管因为什么重跑，都会把模型答过的列和没把握不答的列
  再问一遍（真实库试跑时，一次定时刷新结构就引出了这样一轮）；模型那一遍失败时仍存上一次的，下次照样再问；
- 两段说明：关系发现一段，业务文字一段。

两张表都要加进 `JimengTenantLineHandler.TENANT_AWARE_TABLES`，并加单测断言。

### 6.2 生成

分三步，保证同一个连接内的叫法一致：

1. **业务领域：** 只给还没有领域的对象分配。第一次是全部对象；以后只给新对象，同时把已有的领域清单给模型，让它尽量
   归入已有领域。一个系统最多 8 个领域，用业务叫法，比如采购、销售、财务、主数据。
2. **对象名称与说明：** 每批最多 50 个对象，输入表名、表注释、语义层说明和所属领域。
3. **关系角色名：** 每批最多 150 条关系，输入见 §6.1。

**提示词面向「业务人员和产品」：**
- 用中文业务叫法；
- 不出现表名、字段名或代码；
- 不写「疑似」「未经验证」之类的保留措辞；
- 一句话说明写「一条记录代表什么」。

**校验：** 以下输出不合格：
- 超过长度上限；
- 含本系统任一物理表名，或长度 ≥ 4 的物理字段名（不区分大小写，按完整单词匹配），即「代码扫描」；
- 含附录 B 的禁用词。

不合格的条目在同一次运行里带着原因重试一次；仍不合格就退回兜底（§6.3），并把数量记进状态说明。

**范围：**
- 只生成缺的和 `input_hash` 变了的，`HUMAN` 行跳过；
- 对象或关系已经从快照或语义层消失的，对应的 `MODEL` 行随之删除；
- 模型和调用方式同 §5.3。

### 6.3 兜底

没有业务文字时，页面照常显示：

- 标题：先退回「像名称的表注释」（v2 §5.4 的规则），再退回表名；
- 说明：留空；
- 领域：「未分类」；
- 关系角色名：先退回列注释，再留空。用作兜底的注释同样要过代码扫描，命中就不用。

**「业务名称整理中」提示**的出现条件：有对象还没拿到业务名，并且补全链正在跑、或这个连接还从没跑完过而补全链会来跑
（开关开着，语义层已生成或正在生成）。跑完之后仍有对象没拿到业务名（重试后仍不合格，或这次失败了），就安静地用兜底，
不挂提示，等下次输入变化或失败重试时再补。补全链关着、或语义层没生成成功（定时对账只挑语义层已生成的连接）时，
补全链不会来跑，同样安静地用兜底——否则这条提示会一直挂着。

## 7. 投影与接口

### 7.1 关系判定（沿用 v2，按顺序判定，先命中者为准）

输入是该连接的 JOIN 行（`scope=JOIN`）。起点是 `object_name.field_name`，终点是 `detail_json.to_object.to_column`。

| # | 条件 | 结果 |
|---|---|---|
| 1 | 起点表、起点列、终点表、终点列任一不在当前结构快照里 | 丢弃 |
| 1′ | 起点和终点是同一张表的同一列 | 丢弃（v2 审查 #3：抽样时这种行的包含率永远是 100%） |
| 2 | `status = STALE` | 丢弃 |
| 3 | `detail_json.human_verdict = UNRELATED` | 丢弃 |
| 4 | `detail_json.human_verdict = RELATED` | `CONFIRMED`，`confirmedBy=BUSINESS` |
| 5 | `verified = CONFIRMED` | `CONFIRMED`，`confirmedBy=DATA` |
| 6 | `verified ∈ {REJECTED, WEAK}` | 丢弃 |
| 7 | `detail_json.join_kind = POLYMORPHIC` | 丢弃（未确认前画成无条件的线会误导人） |
| 7′ | `detail_json.origin = RELATION_PASS`（模型那一遍推出） | 丢弃（只给 Agent 当线索，见 §5.3；核对通过或业务方确认后由第 4、5 行画出） |
| 8 | 终点列单独构成终点表的一个唯一键 | `INFERRED` |
| 9 | 其余 | 丢弃 |

**两条语义层的既有约定，实现时不能读错：**
- 业务方的任何一次确认都会把 `status` 写成 `CONFIRMED`、`verified` 写成 `NONE`，否认只记在
  `human_verdict=UNRELATED`。所以 `status=CONFIRMED` 并不代表关系成立。
- `verified` 取决于采样包含率：≥ 0.9 为 `CONFIRMED`，0.5–0.9 为 `WEAK`，< 0.5 为 `REJECTED`。`UNDECIDABLE` 表示查了
  但判断不出，`NONE` 表示没查。

**唯一键、基数、排序也沿用 v2：**
- 唯一键用 `SemanticTableRenderer.namedUniqueKeys`；
- 终点列单独构成唯一键时，起点列也单独唯一则为一对一，否则为多对一；终点列不唯一时基数为 `null`；
- 表按 `importance_rank` 再按表名排序；关系按起点表、起点列、终点表、终点列排序。

**自关联**不进关系列表，记在对象的 `selfReferences` 上，现在带上 `tier`、`confirmedBy`、`role`（v2 审查 #4）。

### 7.2 接口

路径：`/data/admin/data-graph/systems/{connectorId}`、`/systems/{connectorId}/tables?name=`，只给企业超管（§9）。
原来的系统列表 `/systems` 随页面上的系统切换一起去掉（2026-10-01 修订：一个库一页，库由地址决定）。字段变化如下：

| 对象 | 变化 |
|---|---|
| SystemGraph | 新增 `truncated`（v2 审查 #2）和 `viewStatus`，见下 |
| TableCard | `displayName` 改为业务视图优先；新增 `summary`、`domain`、`nameSource`（`BUSINESS_VIEW` / `COMMENT` / `PHYSICAL`）；`keyColumns` 和 `relationColumns` 保留，给技术信息区用 |
| Relation | 新增 `role` |
| SelfReference | 新增 `tier`、`confirmedBy`、`role` |
| TableDetail | 新增 `summary`、`domain` |

**SystemGraph 新增的两个字段：**
- `truncated`：快照对象数是否达到上限 200。上限只有一个来源 `ConnectorSchemaService.MAX_OBJECTS`：把它改为 public，
  语义层那份镜像 `SCHEMA_SNAPSHOT_CAP` 改为引用它。
- `viewStatus`：认领未过期时为 `RUNNING`；否则取上次结果 `READY` / `FAILED`；从没跑完过时，补全链会来跑（开关开着，
  语义层 `READY` 或 `RUNNING`）也为 `RUNNING`，不会来跑为 `null`。只给前端判断提示用，不直接上屏。

**永不出网**的范围不变：语义层的原文说明、依据、置信度、判别值。另外新增：业务视图的 `input_hash`、`model_code`、
`prompt_version` 等内部字段也不出网。

### 7.3 代码位置（包前缀 `com.jimeng.dataserver.ai.connector`）

| 部分 | 位置 |
|---|---|
| 关系发现 | `service/SemanticRelationDiscovery`（规则候选 + 模型一遍 + 写入） |
| 业务视图 | `businessview/`（生成、校验、读写） |
| 补全链 | `service/SemanticEnrichmentService`（认领、顺序、触发、定时对账） |
| 星图 | `graph/`（投影时合并业务视图） |

## 8. 前端：对象地图

### 8.1 页面

- **入口（2026-10-01 修订）：** 「数据连接」里能自描述的连接卡片上有「查看星图」（按类型声明的能力判断，不按类型名写分支），
  进 `/console/connectors/:id/graph`。还没有任何结构的库也有入口，进来按 §8.4 说这个库自己的状态，不落到别的库上。
  侧栏没有单独的入口；在这一页时侧栏高亮「数据连接」。
- **页头：** 「返回数据连接」；标题「数据星图 · 库名」（连接显示名，没有就用连接名）；说明「看看业务系统里有哪些业务对象、
  它们之间怎样关联。内容由平台根据接入的系统自动整理，并随系统更新自动同步。」
- **一个库一页，没有系统切换。** 地址里的库变了而页面没有重新挂载（浏览器在两个库的星图之间前进后退）时，同步清掉选中
  状态（v2 审查 #11）。
- **概览：** 对象 N ｜ 已核对的关联 a ｜ 待核对的关联 b ｜ 暂未发现关联的对象 c。关联按条数计，不是线的根数。下面一行按情况显示提示：
  - 「业务名称整理中」（出现条件见 §6.3）；
  - 「这个系统表很多，只整理了按重要性排前 200 个对象」（`truncated`）。
- **领域筛选：** 「全部」加上各个领域，每个领域带颜色点。选中某个领域后，其他领域的对象变暗，布局不动。

### 8.2 画布

- **节点是对象卡片：**
  - 显示业务名（标题）、一句说明（最多两行）、领域色条；
  - 有自关联时带「内部关联」徽标；
  - 不列字段，不显示表名；所有卡片尺寸相同。
- **边是两个对象之间的一根线：**
  - 同一方向上的多条关系合并成一根。A→B 和 B→A 是两根，比如客户和供应商互相引用；
  - 箭头指向被引用的对象；
  - 线上写角色名；有多条时写「N 种关联」，悬停时列出全部角色；
  - 其中任一条已核对就画实线，否则画虚线。
- **布局：** dagre 从左到右排，结果确定。沿用 v2 的做法：整图一屏看不清时，放大到关联最多的对象附近，并显示小地图。
- **交互：** 点选只改样式，不动坐标（沿用 v2）。键盘聚焦到视野外的卡片时，画布自动平移过去（v2 审查 #6）。

### 8.3 右侧面板

- **未选中对象时：** 两个页签，「关联清单」（按对象两两分组的句子）和「暂未发现关联的对象」。记住上次停留的页签（v2 审查 #9）。
- **选中对象后，依次显示：**
  1. 它是什么：业务名、领域、一句说明；
  2. 和谁有关：按相关对象分组，句子里只用业务名和角色名；
  3. 技术信息：默认折叠，里面是表名、字段列表和字段搜索、每条关系的字段对应与核对状态。

  切换对象时，详情的状态（比如字段搜索词）全部重置（v2 审查 #1）。
- 句子模板见附录 E。

### 8.4 状态文案

| 情况 | 显示 |
|---|---|
| 语义层从未生成（含还没有任何结构的库） | 「这个系统的业务对象还在整理中，完成后会自动出现。」 |
| 正在生成，且还没有关联 | 「正在整理，完成后刷新页面即可看到。」 |
| 生成失败，且没有关联 | 「整理没有成功，请联系企业管理员。」 |
| 已完成但没有关联 | 「暂未发现可以确认的关联。」 |

- 画布区和页签里的空状态用同一句话（v2 审查 #10）。
- 能进这一页的都是企业超管，空状态里「去数据连接」的链接照常显示。

### 8.5 视觉

沿用 v2 的深色风格。领域颜色用固定的 8 色调色板，按领域顺序分配。新增的颜色要加进对比度清单，仍须满足 WCAG AA。
v3 起这份清单由前端 e2e 夹具检查直接读页面上实际渲染的颜色来核对（文字 4.5:1、大字 3:1；领域色条与色点按图形对象
3:1，描边算在内），不再手抄色值，改了样式会自动跟着核。

## 9. 权限

2026-10-01 修订：星图收进「数据连接」，和数据连接一样只给企业超管。v3 原来的做法（新增可授予成员的「数据星图」模块、
后端按模块把关）撤掉。

- **后端：** 两个接口先过 `SuperAdminGuard.requireSuperAdmin()`，与 `ConnectorAdminController` 一致：表结构本身就是
  敏感元数据。非超管返回 HTTP 200 加业务码 4001，前端不会把用户踢回登录页。
- **模块：** `DATA_GRAPH_MODULE` 从 `PlatformConstant.ALL_MODULES` 和 `GrantableResourceController` 的可授权列表里
  去掉，jm-admin 的授权弹窗里不再出现；`PermissionResolver.assertCurrentModule` 随之删除。
- **已经授出去的旧授权：** 角色授权回显（`RoleResourceService.getGrants`）只带仍然可授权的模块码。jm-admin 拿回显当勾选
  初值、保存时原样提交，而保存按 `ALL_MODULES` 校验：已下线的模块码（插件、数据星图）回显出去，弹窗里没有它的勾选框、
  去不掉，这个角色每次保存都会被「未知模块码」拒掉。「数据星图」模块没有上线过，生产上不会有这种行；插件的旧行可能有。
- **前端：** 侧栏去掉「数据星图」；路由 `/console/connectors/:id/graph` 用 `SuperAdminRoute`（非超管显示「仅企业超管
  可访问」）。前端这一层只是纵深防御，真正的关卡是后端。

## 10. v2 审查结论的处理

| # | 问题 | 处理 |
|---|---|---|
| 1 | 字段搜索词在切换表后残留 | 修（§8.3） |
| 2 | 只显示前 200 个对象却没有告诉用户 | 修（§7.2 `truncated`、§8.1） |
| 3 | 同一张表同一列的自关联 | 修（§7.1 第 1′ 行） |
| 4 | 自关联不带来源 | 修（§7.1） |
| 5 | 业务方确认时丢掉结构键 | 修（§5.5） |
| 6 | 键盘聚焦到视野外的卡片时画布不跟随 | 修（§8.2） |
| 7 | e2e 缺「加载后无报错」「没有任何系统」「接口失败」三项检查 | 补（§12）；「没有任何系统」随系统列表一起去掉（2026-10-01 修订） |
| 8 | 性能只测了投影 | 补服务层的 200 表性能测试（§12） |
| 9 | 从详情返回总回到第一个页签 | 修（§8.3） |
| 10 | 两处空状态文案互相矛盾 | 修（§8.4） |
| 11 | 切换系统时，上一个系统的选中状态会残留一瞬间 | 修（§8.1） |
| 12 | 新依赖的版本号带 `^` | 改为精确版本 |

## 11. 与 v2 实现的关系

- **沿用：** 关系判定规则、`RowEstimateNote`、接口骨架、dagre 布局和「一屏看不清就放大」的逻辑、React Flow 画布框架、
  右侧面板框架、e2e 夹具框架、深色视觉。
- **改动：**
  - 节点和边改为按对象画（卡片不再列字段，边按对象两两合并）；
  - 文字来源改为业务视图；
  - 权限改为模块授权（2026-10-01 修订撤回：收进「数据连接」，只给企业超管，见 §9）；
  - 按 §10 处理审查问题。
- 两个仓库的 `feat/data-graph-v2` 分支在上面继续提交，v2 的现状不单独合并。

## 12. 测试

**后端（`modules/data-server`，JUnit 5）：**
- 关系发现：
  - 命名规则：用和本地两个系统同形的夹具，断言 klny_erp 恰好 33 条、erp_real 恰好 16 条；并列时放弃；本列是单列唯一键
    时不算；不连本表；后缀不足 4 个字符不算；
  - 声明外键：能读出并转成候选；读不到时不报错；
  - 写入：按 §5.4 的表逐格断言，尤其是「弱推断被替换」「已核对的不动」「模型候选不替换」；
  - 模型输出的解析和过滤（模型用假实现代替）。
- 业务视图：
  - 校验能拦住长度超限、代码和禁用词；不合格的重试一次；
  - 只生成缺的和输入变了的；`HUMAN` 行不动；
  - 领域只给新对象分配；消失对象的 `MODEL` 行被物理删除。
- 补全链：
  - 三步的顺序，② 的核对范围；agent 路径跳过 ②；
  - 同一连接只跑一条；认领过期后能接手；
  - 定时对账按输入指纹触发，失败后 6 小时退避；
  - 开关关掉后退回原来的行为。
- 权限：非超管拦住（4001）、一行数据都不查，超管放行；可授权模块里没有「数据星图」；角色授权回显不带已下线的模块码。
- 投影：
  - 合并业务视图，兜底顺序（包括兜底注释的代码扫描），`nameSource`；
  - §7.1 第 1′、7′ 行；自关联带来源；
  - `truncated`、`viewStatus`。
- 性能：服务层 200 张表、200 条关系的 `/systems/{id}`，P95 < 300 ms。
- 两张新表都在租户白名单里；`MAX_OBJECTS` 只有一个来源。

**前端（夹具 e2e）：**
- 按对象画：边已按对象两两合并，「N 种关联」正确；
- 默认视图扫描不到代码；技术信息区默认折叠，展开后能看到表名和字段；
- 领域筛选时其他对象变暗，坐标不变；
- 入口：侧栏没有「数据星图」；能自描述的连接卡片上有「查看星图」，HTTP 接口没有；点进去是这个库的星图，没有系统切换，
  能返回数据连接；
- 成员（包括以前授过「数据星图」模块的）直接敲地址看到「仅企业超管可访问」，侧栏也没有数据连接；
- 页面加载后控制台无报错；
- 还没有任何结构的库说它自己的状态；接口失败有提示和重试；
- 切换表后字段搜索词清空；页签会被记住；前进后退回到原来的库不恢复旧选中；
- 文字与领域颜色的对比度达到 WCAG AA（§8.5）。

**全栈（真实数据）：** 在本地逐条核对 §1 的验收。补全链会真实调用模型，每个连接大约 4–6 次。

## 13. 上线

- **DDL：** 迁移脚本新增两张表（附录 D）。项目没有 Flyway，按 `docs/RELEASE-CHECKLIST-connector.md` 的顺序，先手工执行
  DDL 再部署；清单 ① 里加上这份 DDL，⑥ 的自检会自动扫到新文件（`modules/data-server/docs/mysql-schema.sql` 是插件下线前的
  全量导出，早已不随迁移更新，不改它）。生产库由你执行；另写一份变更文档到 `docs/config-changes/`，只写表名和用途。
- **配置：** 不需要改 Nacos。沿用 `connector.semantic.infer-model`；新增的 `connector.semantic.enrichment.enabled`
  在代码里默认 true，出问题时可以用它关掉补全链。
- **回填：** 部署后由定时对账给已有连接补跑，一次只跑一个连接。
- **部署顺序：** 先执行 DDL，再部署 data-service，最后部署 jm-agent-front；jm-admin 不用部署。两个仓库串行推送。
- **回滚：** 前端撤掉入口；后端关掉补全链开关。新表可以保留，不影响语义层和 Agent。

## 14. 待验证项

1. **业务名和领域划分准不准。** 先在本地两个系统上逐条看一遍，必要时调整提示词。
2. **命名规则在真实客户库上的误报率。** 本地两个库是零误报，但只有两个样本；要看采样核对否掉多少条。
3. **「明细 → 表头」靠模型那一遍推出，可能选错列，而且上图要等核对。**
   - klny_erp 的数据表明，指向表头的是 `SOID`：5 行全部落在表头 `OID` 里，`POID` 全为空；
   - 但语义层现有的字段说明认为是 `POID`，模型可能沿用这个判断，也可能选 `SRCSOID` 这类来源列；
   - 模型那一遍的产出要核对通过才上星图（§7.1 第 7′ 行）。本地库每张表只有几行，核对判不出，所以本地星图上看不到这两对；
     真实客户库数据量够，选对了列就会核对通过、画成实线；
   - 采样核对遇到全空的列会判为「判不出」，不会自动否掉它。这类错要靠业务方在对话里纠正，或者等下一版的纠错入口。
4. **200 个对象时，领域筛选加对象地图还读不读得清。**

## 15. 已知限制

1. 本地测试库数据太少，抽样核对大多判不出结论，新补出来的关系大多显示为虚线。
2. 每一列最多只能有一条关系（由语义层表的唯一键决定）。§5.4 的替换只处理「终点不是唯一键、也没核对通过」的推断。
3. 旧快照里没有 `unique_keys` 或 `foreign_keys`，这些表的规则候选和推断关系会受限，刷新一次结构后恢复。
4. 只能看到结构快照里的对象，每个连接最多 200 个。
5. 列名没有 `id` 后缀、用拼音缩写的库，规则帮不上，只能靠模型那一遍。比如 klny_erp 的 `F_KHBH`（客户编号）、
   `F_KMBH`（科目编号）：这两条关系语义层已经有了，但终点是编码列，不是唯一键，只有核对通过后才会出现在星图上。
6. 模型那一遍推出的关系（比如「售达方 → 客户」），核对通过之前不上星图。数据量太小的库（比如本地测试库）核对判不出，
   这类关系就一直只在语义层里。

## 附录 A：本地两个系统的预期关系

### A1. klny_erp（连接 `test`）

前 33 条由 §5.2 的命名规则推出（2026-10-01 实测，一条不多一条不少）。

```text
D1_CUSTOMER.INTERNALCOMPANYCODEID → D1_COMPANYCODE.OID
D1_CUSTOMER.VENDORID → D1_VENDOR.OID
D1_VENDOR.CUSTOMERID → D1_CUSTOMER.OID
D1_VENDOR.INTERNALCOMPANYCODEID → D1_COMPANYCODE.OID
EFI_CUSTOMER_CPYCODEDTL.COMPANYCODEID → D1_COMPANYCODE.OID
EFI_CUSTOMER_CPYCODEDTL.RECONACCOUNTID → D1_ACCOUNT.OID
EFI_ORIGINALTRANSDTL.VOUCHERDTLID → EFI_VOUCHERDTL.OID
EFI_VENDOR_CPYCODEDTL.COMPANYCODEID → D1_COMPANYCODE.OID      （现有一条错的推断占着这一列，靠 §5.4 的替换写入）
EFI_VENDOR_CPYCODEDTL.RECONACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.ACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.COUNTRYACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.CUSTOMERID → D1_CUSTOMER.OID
EFI_VOUCHERDTL.GLACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.HOUSEACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.NEWCOMPANYCODEID → D1_COMPANYCODE.OID
EFI_VOUCHERDTL.PARTNERPROFITCENTERID → D1_PROFITCENTER.OID
EFI_VOUCHERDTL.PROFITCENTERID → D1_PROFITCENTER.OID           （v2 已有）
EFI_VOUCHERDTL.RECONACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.VENDORID → D1_VENDOR.OID                       （v2 已有）
EMM_PURCHASEORDERDTL.COMPANYCODEID → D1_COMPANYCODE.OID
EMM_PURCHASEORDERDTL.CUSTOMERID → D1_CUSTOMER.OID
EMM_PURCHASEORDERDTL.DELIVERYCUSTOMERID → D1_CUSTOMER.OID
EMM_PURCHASEORDERDTL.FI_ACCOUNTID → D1_ACCOUNT.OID
EMM_PURCHASEORDERDTL.PROFITCENTERID → D1_PROFITCENTER.OID
EMM_PURCHASEORDERDTL.VENDORID → D1_VENDOR.OID
EMM_PURCHASEORDERHEAD.COMPANYCODEID → D1_COMPANYCODE.OID
EMM_PURCHASEORDERHEAD.VENDORID → D1_VENDOR.OID
EPS_PROJECT.COMPANYCODEID → D1_COMPANYCODE.OID
ESD_SALEORDERDTL.COMPANYCODEID → D1_COMPANYCODE.OID
ESD_SALEORDERDTL.PROFITCENTERID → D1_PROFITCENTER.OID
ESD_SALEORDERHEAD.CREDITACCOUNTID → D1_ACCOUNT.OID
ESD_SALEORDERHEAD.HEAD_COMPANYCODEID → D1_COMPANYCODE.OID
ESD_SALEORDERHEAD.RECEIPTVENDORID → D1_VENDOR.OID
```

最后两条是对象层面的记录项，由模型那一遍推出，验收时在语义层里查、只记录不判通过（星图上要等核对通过，见 §14 第 3 条）。
数据表明列是 `SOID`：

```text
EMM_PURCHASEORDERDTL → EMM_PURCHASEORDERHEAD（数据上是 SOID → OID）
ESD_SALEORDERDTL → ESD_SALEORDERHEAD（数据上是 SOID → OID）
```

### A2. erp_real（连接 `erp_real`）

命名规则推出 16 条，其中 5 条 v2 已有：

```text
D1_VENDOR.INTERNALCOMPANYCODEID → D1_COMPANYCODE.OID
EFI_VENDOR_CPYCODEDTL.COMPANYCODEID → D1_COMPANYCODE.OID      （v2 已有）
EFI_VENDOR_CPYCODEDTL.RECONACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.ACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.COUNTRYACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.GLACCOUNTID → D1_ACCOUNT.OID                   （v2 已有）
EFI_VOUCHERDTL.HOUSEACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.NEWCOMPANYCODEID → D1_COMPANYCODE.OID
EFI_VOUCHERDTL.PARTNERPROFITCENTERID → D1_PROFITCENTER.OID
EFI_VOUCHERDTL.PROFITCENTERID → D1_PROFITCENTER.OID           （v2 已有）
EFI_VOUCHERDTL.RECONACCOUNTID → D1_ACCOUNT.OID
EFI_VOUCHERDTL.VENDORID → D1_VENDOR.OID                       （v2 已有）
EFI_VOUCHERDTL.WBSELEMENTID → EPS_WBSELEMENT.OID
EFI_VOUCHERDTL_EXT.COMPANYCODEID → D1_COMPANYCODE.OID
EFI_VOUCHERDTL_EXT.SRCCOMPANYCODEID → D1_COMPANYCODE.OID
EPS_WBSELEMENT.COMPANYCODEID → D1_COMPANYCODE.OID             （v2 已有）
```

另有 2 条是 v2 已有、不靠命名规则的：

```text
EFI_VENDOR_CPYCODEDTL.OID → D1_VENDOR.OID
EFI_VOUCHERDTL_EXT.OID → EFI_VOUCHERDTL.OID
```

合计 18 条。

## 附录 B：业务文字禁用词

以下词出现在业务文字里即判为不合格。它们是 AI 口吻或平台内部用语：

`判不出`、`未经`、`验证`、`样本`、`采样`、`置信`、`估算`、`InnoDB`、`疑似`、`或许`、`大概`、`推测`、`语义层`、`说明书`、`主键`、`外键`、`字段`。

「可能」不禁用，因为「一张订单可能有多条明细」这类说法是正常的业务描述。

## 附录 C：语义层漏推关系的代码位置（以 `feat/data-graph-v2` @ `9ccddb1` 为准）

包前缀 `com.jimeng.dataserver.ai.connector`。

- **分批与摘要上限：**
  - `service/ConnectorSemanticDeriveService` 的 `buildDigest`：定义在 1764 行，全量调用在 593 行，增量调用在 1245 行；
  - `runtime/ConnectorProperties.Semantic.maxDigestChars = 60000`。
- **「没出现的表不要写关系」：** `service/SemanticPrompts`，全量在 208–210 行，增量在 264–265 行和 271 行。
- **输出顺序和截断：**
  - `SemanticPrompts` 141–160 行的输出格式，顺序是 objects → fields → joins；
  - `maxTokens = 16000`；
  - 截断后的修补在 `ConnectorSemanticDeriveService.repairTruncatedJson`（1942 行）。
- **覆盖判定与增量不回头：** `ConnectorSemanticDeriveService` 的 `coveredObjects`，定义在 1076 行，使用在 967 行。
- **外键只用来排序：** `impl/mysql/MySqlSession` 129–139 行的 `REF_N`。
- **agent 路径不做采样核对：** `generation/SemanticGenerationFinalizer` 的类注释。

## 附录 D：DDL

以 `modules/data-server/src/main/resources/db/migration/V20261001__data_graph_business_view.sql` 为准，全文如下：

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

## 附录 E：句子模板

句子里只用业务名（`{A}`、`{B}`）和关系角色名（`{角色}`），不出现代码：

| 情况 | 模板 |
|---|---|
| 多对一 | 每条「{A}」对应一个「{B}」 |
| 多对一，且角色名与 B 的业务名不同 | 每条「{A}」对应一个「{B}」（作为{角色}） |
| 一对一 | 「{A}」与「{B}」一一对应 |
| 基数未知（只会出现在已确认的关系上） | 「{A}」与「{B}」有关联 |
| 自关联 | 「{A}」内部有关联（{角色}）；没有角色名时去掉括号 |
| 已确认的多态关系 | 句末加「（只对部分类型成立）」 |

技术信息区另外列出每条关系的字段对应与核对状态，来源说法沿用 v2（按表结构推断，尚未核对 / 数据核对通过 / 业务方确认），
例如：`VENDORID → OID · 按表结构推断，尚未核对`。
