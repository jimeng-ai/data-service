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
