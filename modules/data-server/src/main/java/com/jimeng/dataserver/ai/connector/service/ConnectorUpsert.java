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
}
