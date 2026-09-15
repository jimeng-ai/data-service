package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ★ 唯一键采集上线后，第一次结构刷新不能把全库标成 CHANGED。
 *
 * <p>MySQL 连接器的 describe 现在会往 {@code ObjectDetail.extra} 放 {@code unique_keys}、
 * 往组合键成员列的 {@code FieldDetail.extra} 追加一句说明。只要这两处有任何一处进了
 * {@code content_hash} 或列锚点，上线后第一次刷新就会让<b>每一张</b>有主键的表报 CHANGED，
 * 挂在上面的 FIELD / JOIN 语义成片标 STALE——一次部署把整个语义层打成「结构已变，说明可能过时」，
 * 而客户的库一个字节都没动。这里把「extra 不进指纹」钉成回归测试：改指纹的人会先看到它红。
 */
class StructureFingerprintIgnoresKeysTest {

    private static final String NOTE = "主键 / 组合键成员（order_id, line_no），单独这一列可能重复";

    private static List<FieldDetail> fields(boolean withKeyNotes) {
        return List.of(
                new FieldDetail("order_id", "bigint(20)", false, "订单", withKeyNotes ? NOTE : "主键"),
                new FieldDetail("line_no", "int(11)", false, null, withKeyNotes ? NOTE : "主键"),
                new FieldDetail("sku", "varchar(32)", true, "商品编码", "有索引"));
    }

    private static Map<String, Object> baseExtra() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("database", "shop");
        extra.put("hint", "字段注释来自数据库本身，可能过时或为空。口径不明确时请向用户确认，不要自行假定。");
        return extra;
    }

    /** 上线前的快照形状：没有唯一键，列级 extra 只有 COLUMN_KEY 那个词。 */
    private static ObjectDetail before() {
        return new ObjectDetail("t_ord_dtl", "TABLE", null, fields(false), baseExtra());
    }

    /** 上线后的快照形状：同一张表、结构一个字节没动，只是多采了唯一键。 */
    private static ObjectDetail after() {
        Map<String, Object> extra = baseExtra();
        extra.put(SemanticJoinValidator.DETAIL_UNIQUE_KEYS, List.of(Map.of(
                "name", "PRIMARY", "primary", true, "columns", List.of("order_id", "line_no"))));
        return new ObjectDetail("t_ord_dtl", "TABLE", null, fields(true), extra);
    }

    @Test
    @DisplayName("★ 多了 unique_keys 与列级组合键说明，content_hash 的输入一个字不变")
    void 唯一键不进结构指纹() {
        assertEquals(ConnectorSchemaService.structureFingerprint(before()),
                ConnectorSchemaService.structureFingerprint(after()));
    }

    @Test
    @DisplayName("★ 列锚点同样不看 extra：FIELD / JOIN 语义不会因此标 STALE")
    void 唯一键不进列锚点() {
        List<FieldDetail> a = before().fields();
        List<FieldDetail> b = after().fields();
        for (int i = 0; i < a.size(); i++) {
            assertEquals(ConnectorSemanticService.fieldAnchor(a.get(i)), ConnectorSemanticService.fieldAnchor(b.get(i)));
        }
        assertEquals(ConnectorSemanticService.joinAnchor(a.get(0), a.get(2)),
                ConnectorSemanticService.joinAnchor(b.get(0), b.get(2)));
    }

    /** 反向对照：指纹不是对什么都不敏感——真改了一列（这里改注释），它必须变。否则上面两条等于没测。 */
    @Test
    @DisplayName("对照：真改一列的注释，指纹会变")
    void 对照组() {
        List<FieldDetail> f = before().fields();
        ObjectDetail changed = new ObjectDetail("t_ord_dtl", "TABLE", null, List.of(
                new FieldDetail("order_id", "bigint(20)", false, "订单号", "主键"), f.get(1), f.get(2)), baseExtra());
        assertNotEquals(ConnectorSchemaService.structureFingerprint(before()),
                ConnectorSchemaService.structureFingerprint(changed));
    }
}
