package com.jimeng.dataserver.ai.connector.service;

import java.util.List;

/**
 * 语义层推导的两段提示词。
 *
 * <h3>这份提示词里最要紧的不是"让模型多写"，而是"让模型敢空着"</h3>
 * 模型面对 {@code t_ord_mst} / {@code st tinyint} 这种结构，产出一份读起来很完整的说明书是它最
 * 擅长的事——"0=待支付，1=已支付"那句话完全合理、完全可能错，而且<b>没有任何验证能发现它错</b>：
 * SQL 跑得通，报表出得来，数字是错的。所以这里每一条约束的方向都是<b>减法</b>：
 * 把"猜得出但验不了"的那一类明确划到线外，宁可留白。
 *
 * <h3>刻意<b>没有</b>加的一条约束：不要求"机器可验证的格式"</h3>
 * 试过，被否掉了。要求每条断言都能被 SQL 验证，模型最省力的满足方式是<b>复述 DDL</b>
 * （"该列类型为 bigint 且非空"——验证 100% 通过，信息量 0），
 * 同时把最需要人帮忙的那几类（字段业务含义、易误用的告诫）整个挤了出去。
 * 分界线因此定在<b>依据的来源</b>（COMMENT / DATA / NAME / GUESS），不在可验证性上。
 */
public final class SemanticPrompts {

    private SemanticPrompts() {
    }

    /**
     * 系统提示词。四条硬约束逐条对应设计里四个具体的失败：
     * 编枚举含义、编业务口径、把低置信度写成确定、以及无依据的臆测混进有依据的断言里。
     */
    public static String deriveSystem() {
        return """
                你在为一个**只读数据库账号**写一份「说明书」。

                读这份说明书的不是人，是另一个模型：它要靠它把业务问题翻译成 SQL。它自己能看到的只有
                表名、列名、类型，以及可能有也可能没有的注释。信息不够的时候它**不会报错，它会猜**，
                然后给出一个看起来完全正常的错数字。你的工作是把「能确定的」写清楚，把「不能确定的」
                明确标出来——而不是把两者混在一起写成一份读起来很完整的东西。

                # 一、分界线是「有没有外部依据」，不是「你有多确信」

                每一条断言都必须带一个 evidence 标签，只能取这四个值之一：

                - `COMMENT`：客户库自己的表注释 / 列注释里写了。这是一手事实，优先级最高，可以直接采信。
                - `DATA`：从给你的结构本身读得出来（类型、可空、主键、唯一性、自增这类）。
                - `NAME`：名字本身就说明了含义（`created_at` 是创建时间，`order_id` 是订单号）。
                - `GUESS`：以上都没有，只能靠行业常识猜。

                **标了 `GUESS` 的条目会被直接丢弃，不会写进说明书。**所以不要为了多产出而把 `GUESS`
                标成 `NAME`。那不是多给了一条信息，那是给了一条不知真假、且**没有任何办法发现它假**
                的信息。一条留白只是让读它的模型知道自己不知道；一条错的会让它确信自己知道。
                后者的代价大得多。

                判断标准很简单：**这句话的依据，是不是写在我看到的材料里？**
                写在注释里 → COMMENT；从类型/约束推得到 → DATA；名字直白到不需要解释 → NAME；
                「一般电商系统都是这么设计的」→ GUESS。

                # 二、禁止推断枚举值的含义

                这是上面那条最容易破功的地方，所以单列一条。

                看到 `status tinyint`，你会非常自然地写出「0=待支付，1=已支付，2=已发货，3=已完成」。
                这句话读起来专业、合理、有帮助，**而且可能整个是错的**——客户的系统里 0 也许是「已取消」。
                没有任何验证能发现它错：SQL 语法对、能跑、返回一个数。

                所以除非**列注释里写明了对照关系**，否则码值列只能这样写：

                    「状态码。取值含义未知，注释里没有给对照表。用它做过滤或分组前必须先向业务方确认，
                      不要假定 0 是初始态。」

                同一条纪律适用于：

                - `type` / `kind` / `flag` / `level` / `source` 这类码值列；
                - `is_xxx` 的 0/1 到底哪个方向为真；
                - 软删列到底是 1 表示已删还是未删；
                - 金额列的单位是元还是分，时间列是秒还是毫秒；
                - 状态列之间的先后顺序（哪个状态算"最终完成"）。

                这些都属于「猜得出一个答案但验证不了」。一律**只描述现象、不给结论**，
                并在 gloss 里点出「必须确认」这件事本身——那句提醒才是这一条的价值所在。

                # 三、业务口径只提问题，绝不给答案

                「销售额」扣不扣退款？「活跃用户」按登录还是按下单算？「订单量」算不算已取消的单？

                这类答案**根本不在数据库里**。它在某份财务口径文档里，或者在某个人的脑子里。
                你不可能从表结构推出来，任何看起来推出来的都是编的。而口径编错一次，
                之后所有基于它的数字会一直错下去，没人会察觉——因为每个数字单看都很正常。

                所以：口径一律只写进 `ambiguities`，形式是**一个必须由人回答的问题**，
                外加它会影响到哪些表。**绝不要输出任何一条口径的定义。**
                你的产出是一张待确认清单，不是一本词典。

                好的 ambiguity：
                    term: 销售额
                    question: 销售额是否扣除退款？t_ord_mst 里有 refund_amt 列，是否应从 pay_amt 中减去，
                             结构本身无法回答，需要业务方确认。
                差的（绝对不要）：
                    term: 销售额
                    question: 销售额 = SUM(pay_amt) - SUM(refund_amt)

                # 四、不确定就明说，并且说清楚下一步怎么验

                置信度低不丢人，**假装确定才危险**。下面这句话是一条好描述：

                    「这一列很可能是关联 t_usr 的外键（依据：列名 + 类型一致）。置信度中等——
                      库里没有外键约束可以佐证，join 前建议先看该列的取值分布，
                      确认它确实落在 t_usr.id 的范围内。」

                它同时做到了三件事：给出了可用的判断、亮出了判断的依据、告诉读者怎么把它证实或推翻。
                每条 gloss / note 都应该往这个样子靠。特别是关系（joins）：本次推导**没有任何数据采样**，
                所有关系都只是结构上的推测，不要写成已经验证过的样子。

                # 五、写什么、不写什么

                - 不必为每一列都写一条。名字已经完全自明、也没有任何容易误用之处的列（`id`、`create_time`）
                  可以跳过——一条零信息量的条目只会稀释真正有用的那几条。
                - 但凡有一点可能被误读的列，都要写：同义不同名（`amt` 到底是应付还是实付）、
                  单位不明、有隐含过滤条件（有些表要带 `deleted=0` 才是有效数据）、
                  两张表里同名列含义不同。这些正是模型自己看不出来、又最容易出错的地方。
                - 表很多时，**先写最重要、最容易被误用的**。输出如果被长度截断，靠前的内容才留得下来。

                # 六、表形态（table_shape）只能取四个值之一

                它不是一句描述，是一个**决定怎么聚合**的开关，所以只能写下面四个值里的一个（原样写英文）：

                - `DETAIL`（明细表）：一行 = 一条业务记录或一次事件。SUM / COUNT 可以直接用。
                - `MULTI_METRIC_PERIOD`（多指标周期表）：一行 = 一个周期（可带维度），多个指标各占一列。
                  注意快照型指标（余额、库存、在册人数）不能跨周期相加。
                - `KEY_VALUE`（键值对表）：一行 = **某一个指标的一个值**，指标名在一列、指标值在另一列
                  （如 `metric_code` + `metric_value`）。**把它当明细表去 SUM 值列，等于把不同单位的指标加在一起，
                  所有聚合都是错的。**
                - `OTHER`（其他）：维度表、配置表、关系表、日志表等，或者你判断不了。

                判断不了就写 `OTHER`，不要为了「看起来有判断」而硬选一个。
                平台之后会对疑似键值对表做数据测量，测出来与你不一致时以测量为准，并保留你的判断作对照。

                # 输出格式

                只输出一个 JSON 对象，不要 markdown 代码围栏，不要任何解释性文字。形状如下：

                {
                  "objects": [
                    {
                      "name": "表名，必须与给你的结构里的表名完全一致",
                      "gloss": "这张表是干什么的，一两句话",
                      "table_shape": "DETAIL | MULTI_METRIC_PERIOD | KEY_VALUE | OTHER",
                      "typical_questions": ["这张表能回答的典型业务问题，业务语言不是 SQL，2-4 条"],
                      "evidence": "COMMENT | DATA | NAME | GUESS",
                      "confidence": 85
                    }
                  ],
                  "fields": [
                    {
                      "object": "所属表名",
                      "name": "列名，必须与结构里完全一致",
                      "gloss": "这个字段是什么意思、有什么坑",
                      "evidence": "COMMENT | DATA | NAME | GUESS",
                      "confidence": 70
                    }
                  ],
                  "joins": [
                    {
                      "object": "左表名",
                      "column": "左表上的列",
                      "to_object": "右表名",
                      "to_column": "右表上的列",
                      "cardinality": "1:1 | 1:N | N:1 | N:N",
                      "evidence": "COMMENT | DATA | NAME | GUESS",
                      "confidence": 60,
                      "note": "凭什么这么认为，以及怎么验证它"
                    }
                  ],
                  "ambiguities": [
                    {
                      "term": "业务词条，如 销售额",
                      "question": "必须由人回答的那个问题（只提问，不给答案）",
                      "applies_to": ["会受这个口径影响的表名"]
                    }
                  ]
                }

                confidence 是 0-100 的整数。四个数组都可以为空数组，但键必须都在。
                表名和列名必须原样照抄给你的结构，**不要改大小写、不要补前缀、不要发明结构里没有的表或列**——
                对不上的条目会被整条丢弃。
                """;
    }

    /**
     * 用户提示词：结构摘要 + <b>如实交代这份摘要不完整在哪</b>。
     *
     * <p>最后一件事不能省。摘要被截断却不说，模型会把它当成"这就是全部的表"，
     * 于是给出一条指向它没见过的表的关系，或者断言某个概念在库里不存在。
     * 截断本身不可怕，<b>把截断读成"已覆盖全部"才可怕</b>。
     */
    public static String deriveUser(String connName, String kind,
                                    int includedObjects, int totalObjects,
                                    List<String> notes, String digest) {
        StringBuilder s = new StringBuilder();
        s.append("下面是一条数据连接的结构快照。\n\n");
        s.append("连接名：").append(connName == null ? "(未命名)" : connName);
        if (kind != null && !kind.isBlank()) {
            s.append("    类型：").append(kind);
        }
        s.append('\n');
        s.append("本次给你的对象数：").append(includedObjects).append(" / 快照中共 ").append(totalObjects).append("\n");

        if (includedObjects < totalObjects || (notes != null && !notes.isEmpty())) {
            s.append("\n【这份材料不完整，请照此行事】\n");
            if (includedObjects < totalObjects) {
                s.append("- 下面只列出了其中一部分表。**没有出现在下面的表，不要为它写任何条目，"
                        + "也不要断定它不存在**；需要它才能说清楚的关系，就不要写。\n");
            }
            if (notes != null) {
                for (String n : notes) {
                    s.append("- ").append(n).append('\n');
                }
            }
        }

        s.append("\n格式：每张表一段，首行是 `## 表名 [类型] 表注释`，其后每行一列，"
                + "以 `|` 分隔：列名 | 类型 | 可空 | 列注释 | 补充。列注释为空表示客户没写注释。\n\n");
        s.append("----------------------------------------\n");
        s.append(digest);
        s.append("----------------------------------------\n\n");
        s.append("请按系统提示里的约束产出那个 JSON。再强调一遍：没有依据的条目标成 GUESS 或者干脆不写，"
                + "枚举值含义不要猜，业务口径只提问题。");
        return s.toString();
    }

    /**
     * 增量推导的用户提示词：<b>只为这一批表</b>写说明书，其余已有的表只作参照。
     *
     * <h3>这一批表不全是「新出现的」，所以提示词里不这么说</h3>
     * 这一批有两种来源：刷新时新出现的表，以及一直在库里、却还没有说明书的表（上一次全量推导时超出了摘要上限，
     * 或者模型当时对它只给得出 GUESS）。在稳定运行的连接上，后一种才是常态。
     * 对它们说「库里新出现了这张表」是一句假话，而材料里的每一句话都会被模型当成依据——
     * 它完全可能据此写出「这是一张新建的表」。所以统一说「还没有说明书的表」，这句话对两种来源都成立。
     *
     * <h3>为什么已有的表也要给，而且要明说「不要为它们写条目」</h3>
     * 新表最有价值的那几条恰恰是指向老表的关系（{@code t_new.cust_id → t_customer.id}），
     * 不给老表的列，这些关系一条都写不出来。但老表的说明书上已经挂着采样验证的结论和值域，
     * 为它们重写条目在落库时会被丢弃——提示词里说清楚，是为了别让模型把输出预算花在会被扔掉的东西上。
     *
     * @param includedNew     本次给出完整结构的新表数
     * @param totalNew        本批新表总数（摘要放不下的不在 {@code newDigest} 里）
     * @param gaps            材料缺了什么，原样告诉模型
     * @param newDigest       新表的完整结构，格式同 {@link #deriveUser}
     * @param context         已有表的精简列表（表名 + 列名 + 类型）
     * @param contextComplete 已有表是否全部列出
     */
    public static String deriveAddedUser(String connName, String kind,
                                         int includedNew, int totalNew,
                                         List<String> gaps, String newDigest,
                                         String context, boolean contextComplete) {
        StringBuilder s = new StringBuilder();
        s.append("这条数据连接的说明书已经生成过，但下面「待补写的表」里的 ").append(totalNew)
                .append(" 张表**还没有说明书**，请只为它们补写。\n\n");
        s.append("连接名：").append(connName == null ? "(未命名)" : connName);
        if (kind != null && !kind.isBlank()) {
            s.append("    类型：").append(kind);
        }
        s.append('\n');

        s.append("\n【这次的范围，请照此行事】\n");
        s.append("- objects / fields：**只写下面「待补写的表」里的表**。已有的表不要写，写了也会被丢弃。\n");
        s.append("- joins：只写**至少一端是待补写的表**的关系；两端都是已有表的关系不要写。\n");
        s.append("- ambiguities：只写和待补写的表有关的口径问题，applies_to 里要包含那张表。\n");
        if (includedNew < totalNew) {
            s.append("- 待补写的表里只有 ").append(includedNew).append(" 张给出了结构，其余的这次不要为它写任何条目。\n");
        }
        if (!contextComplete) {
            s.append("- 「已有表」只列出了一部分。没列出的表不要断定它不存在，需要它才能说清的关系就不要写。\n");
        }
        if (gaps != null) {
            for (String g : gaps) {
                s.append("- ").append(g).append('\n');
            }
        }

        s.append("\n格式：每张表一段，首行是 `## 表名 [类型] 表注释`，其后每行一列，"
                + "以 `|` 分隔：列名 | 类型 | 可空 | 列注释 | 补充。列注释为空表示客户没写注释。\n\n");
        s.append("======== 待补写的表（为它们写说明书）========\n");
        s.append(newDigest);
        s.append("======== 已有表（仅供写关系时参照，不要为它们写条目）========\n");
        s.append(context == null || context.isBlank() ? "(无)\n" : context);
        s.append("========================================\n\n");
        s.append("请按系统提示里的约束产出那个 JSON。没有依据的条目标成 GUESS 或者干脆不写，"
                + "枚举值含义不要猜，业务口径只提问题。");
        return s.toString();
    }
}
