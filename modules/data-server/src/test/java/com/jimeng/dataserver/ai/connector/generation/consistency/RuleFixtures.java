package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一致性规则测试共用的快照夹具。
 *
 * <p>detail_json 按 {@code ConnectorSchemaService.toDetailMap} 的形状手工拼（那个方法是 service 包的包级可见，这里调不到），
 * 唯一键写在 {@code extra.unique_keys}，形状与 {@code MySqlSession.describe} 写入的一致：{@code [{"name","primary","columns"}]}，主键在前。
 * 三态分开造：传 {@code null} = 旧快照没有这个键（未知），传空列表 = 确实没有唯一键。
 */
final class RuleFixtures {

    private RuleFixtures() {
    }

    static FieldDetail col(String name, String type, String comment) {
        return new FieldDetail(name, type, true, comment, null);
    }

    static Map<String, Object> key(String name, boolean primary, String... columns) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("name", name);
        k.put("primary", primary);
        k.put("columns", List.of(columns));
        return k;
    }

    /** @param uniqueKeys {@code null} = 快照里没有 unique_keys 这个键（未知） */
    static ConnectorSchema table(String name, String comment, List<Map<String, Object>> uniqueKeys, FieldDetail... cols) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", "TABLE");
        m.put("comment", comment);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDetail f : cols) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("type", f.type());
            fm.put("nullable", f.nullable());
            fm.put("comment", f.comment());
            fm.put("extra", f.extra());
            fields.add(fm);
        }
        m.put("fields", fields);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("database", "shop");
        if (uniqueKeys != null) {
            extra.put(SemanticJoinValidator.DETAIL_UNIQUE_KEYS, uniqueKeys);
        }
        m.put("extra", extra);
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(1L);
        s.setObjectName(name);
        s.setObjectType("TABLE");
        s.setObjectComment(comment);
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(m));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /**
     * 常用快照：
     * <ul>
     *   <li>t_ord：订单主表（注释末尾带平台追加的估算行数），PRIMARY(id)。含码值注释列 st、多态外键 buyer_type + buyer_id。</li>
     *   <li>t_cust：PRIMARY(id)、uk_code(code)。</li>
     *   <li>t_shop：没有表注释，PRIMARY(id)、uk_tenant_code(tenant_id, code)——code 只是组合键成员。</li>
     *   <li>t_legacy：旧快照，唯一键未知。</li>
     *   <li>t_nokey：唯一键是空列表。</li>
     *   <li>t_nocols：只有表名，没有列。</li>
     *   <li>t_rowonly：表注释只有平台追加的估算行数。</li>
     * </ul>
     */
    static List<ConnectorSchema> shop() {
        return List.of(
                table("t_ord", "订单主表（约 1200 行，InnoDB 估算值，不可当作准确计数）",
                        List.of(key("PRIMARY", true, "id")),
                        col("id", "bigint(20) unsigned", "主键"),
                        col("cust_id", "bigint", null),
                        col("shop_code", "varchar(32)", null),
                        col("st", "tinyint", "状态：0-待支付，1-已支付"),
                        col("amt", "decimal(12,2)", "金额"),
                        col("flag", "bit(1)", null),
                        col("created_at", "datetime", "  "),
                        col("buyer_type", "varchar(16)", null),
                        col("buyer_id", "bigint", null)),
                table("t_cust", "客户表",
                        List.of(key("PRIMARY", true, "id"), key("uk_code", false, "code")),
                        col("id", "bigint", "客户主键"),
                        col("name", "varchar(64)", null),
                        col("code", "varchar(32)", null),
                        col("level", "int", null)),
                table("t_shop", null,
                        List.of(key("PRIMARY", true, "id"), key("uk_tenant_code", false, "tenant_id", "code")),
                        col("id", "bigint", null),
                        col("tenant_id", "varchar(64)", null),
                        col("code", "varchar(32)", null)),
                table("t_legacy", "旧表", null,
                        col("id", "bigint", null),
                        col("ref", "varchar(32)", null)),
                table("t_nokey", "流水", List.of(),
                        col("id", "bigint", null),
                        col("val", "int", null)),
                table("t_nocols", "只有表名", null),
                table("t_rowonly", "（约 5 行，InnoDB 估算值，不可当作准确计数）", List.of(key("PRIMARY", true, "id")),
                        col("id", "bigint", null)));
    }

    static RuleContext ctx(List<ConnectorSchema> snapshot, List<ProposedEntry> submission) {
        return RuleContext.of("t_ord", snapshot, Set.of(), submission);
    }

    static RuleContext ctx(ProposedEntry... submission) {
        return ctx(shop(), List.of(submission));
    }

    /** 键值对拼成条目原文：{@code map("column", "cust_id", "to_object", "t_cust")}。值为 null 的键不放。 */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    /** 一条从 t_ord 出发的关系（提交流水线 C 步已补 object）。{@code more} 追加 cardinality / evidence / note 等。 */
    static ProposedEntry join(int index, String column, String toObject, String toColumn, Object... more) {
        Map<String, Object> m = map("object", "t_ord", "column", column, "to_object", toObject, "to_column", toColumn);
        m.putAll(map(more));
        return ProposedEntry.of(SemanticRowAssembler.KIND_JOINS, index, m);
    }

    static ProposedEntry field(int index, String object, String name, Object... more) {
        Map<String, Object> m = map("object", object, "name", name);
        m.putAll(map(more));
        return ProposedEntry.of(SemanticRowAssembler.KIND_FIELDS, index, m);
    }

    static ProposedEntry object(String name, Object... more) {
        Map<String, Object> m = map("name", name);
        m.putAll(map(more));
        return ProposedEntry.of(SemanticRowAssembler.KIND_OBJECTS, 0, m);
    }

    static ProposedEntry ambiguity(int index, String term) {
        return ProposedEntry.of(SemanticRowAssembler.KIND_AMBIGUITIES, index, map("term", term, "question", "怎么算？"));
    }

    /** 跑一条规则：上下文的 submission 就是这一个条目。 */
    static List<RuleViolation> run(SemanticConsistencyRule rule, ProposedEntry entry) {
        return rule.check(entry, ctx(entry));
    }
}
