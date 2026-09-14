package com.jimeng.dataserver.ai.connector.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 连接器实例的录入/编辑载荷。
 *
 * <p><b>刻意只有一个 {@code params} 口袋，而不是每种类型一批字段。</b>这是「加一种新类型，
 * 前端零改动」这条验收标准在录入侧的落点：字段清单由 {@code Connector.paramSpec()} 声明，
 * 前端按 {@code GET /data/admin/connectors/kinds} 返回的表单 schema 渲染，
 * 后端按同一份定义校验（{@code ParamSpec.validate}）。这里若为 MySQL 加一个 {@code host} 字段，
 * 下次加 Elasticsearch 就得再加一批——那正是抽象切错了的信号。
 *
 * <p>敏感参数（{@code ParamSpec.secretNames()}）也走 {@code params}，它们不进 {@code config_json}，
 * 只进 AES-GCM 密文，且<b>永不回读</b>。编辑时某个敏感参数留空 = 沿用原值，
 * 与 {@code ConnectionService.apply} 的语义一致。
 */
@Schema(description = "连接器实例录入")
@Data
public class ConnectorUpsert {

    @Schema(description = "实例名，租户内唯一；^[A-Za-z0-9_-]{1,64}$。它是 egress 的 URL 路径段，也是模型调工具时的寻址键")
    private String name;

    @Schema(description = "给人看的名字")
    private String displayName;

    @Schema(description = "连接器类型标识，如 MYSQL / HTTP。新建必填；编辑时不可改（config_json 的形状是按类型定的）")
    private String kind;

    @Schema(description = "该类型声明的全部参数（含敏感参数）。编辑时敏感参数留空表示沿用原值")
    private Map<String, Object> params;

    @Schema(description = "direct | tunnel，默认 direct。tunnel（内网隧道）尚未实现，填了会被拒绝")
    private String transport;

    /**
     * 写操作开放程度：{@code FORBIDDEN}（默认）| {@code REQUIRE_APPROVAL} | {@code AUTO}。
     *
     * <p><b>留空 = FORBIDDEN。</b>这一项决定 Agent 能不能改客户的生产业务数据，
     * 「授权的默认值只能是否」——不填、填错、填了个将来才有的值，一律落到只读。
     */
    @Schema(description = "写操作开放程度：FORBIDDEN(默认) | REQUIRE_APPROVAL | AUTO")
    private String writePolicy;

    /**
     * 数据出库档位：{@code METADATA_ONLY} | {@code DERIVED_STATS}（默认）| {@code SAMPLE_VALUES}。
     * 语义见 {@link SemanticDataTier}。
     *
     * <h3>★ 留空 = 默认档（{@code DERIVED_STATS}），<b>不是</b>「保持原样」</h3>
     * 这一点必须写清楚，因为它有一个反直觉的后果：<b>编辑一条已经开了第 3 档的连接时，
     * 如果这个字段没传，它会被降回第 2 档。</b>与 {@link #writePolicy} 同一条规则，
     * 也是同一个理由——
     * <ul>
     *   <li>本接口是整体覆盖（PUT），不是局部打补丁。让「省略」等于「沿用」，
     *       会造出一个没人审计得到的粘性状态：谁也说不清这条连接当初是<b>谁</b>把第 3 档打开的，
     *       因为此后每一次改显示名的保存都在默默替它续期。</li>
     *   <li>两种错的代价不对称。降档错了，表现是关系推断变差、有人来问为什么——吵闹、能被发现；
     *       留档错了，表现是真实取值继续出库而谁都没有再决定过一次——安静、发现不了。</li>
     * </ul>
     * 所以前端的编辑表单<b>必须把当前档位回填进去再提交</b>（连接详情接口的
     * {@code semanticDataTier} 就是给这个用的）。漏了它，用户会看到自己没动过的档位被改掉。
     *
     * <h3>填错了会报错，不会悄悄降档</h3>
     * 非空但认不出来的值（拼错、旧前端传的老名字）一律 400 拒绝。
     * 库里的脏值必须兜底（没人可问），接口入参必须报错（有人可问）——这是两种场景，
     * 混成一种就会出现「界面显示第 3 档、实际存的是第 1 档」这样不报错的配置失效。
     */
    @Schema(description = "数据出库档位——为了看懂客户的库，允许什么东西离开它。"
            + "METADATA_ONLY=第1档，只有表名/列名/类型/索引/客户自己写的注释出库，不做任何聚合（代价：仅凭名字推表关系，真实生产库上精确率约 0.49）；"
            + "DERIVED_STATS=第2档【默认】，允许在客户库内聚合、只带走统计量（distinct 数、NULL 率、min/max、字符形状、包含率、基数、minhash sketch=K 个哈希值而非原始值），"
            + "逐行记录不出库，但如实说：min/max 本身就是两个真实取值；"
            + "SAMPLE_VALUES=第3档【默认关闭，须企业超管显式开启】，把【真实取值】本身带出库（top-k 实际值、低基数列的全量维值索引）——"
            + "「地区」列的维值索引就是贵司所有地区名进入我们的库，这件事不做委婉表述。"
            + "★ 留空 = 第2档，不是「保持原样」：编辑已开第3档的连接时不传这个字段，会把它降回第2档；"
            + "编辑表单必须回填当前档位。非空但认不出来的值一律拒绝（400），不会悄悄降档")
    private String semanticDataTier;
}
