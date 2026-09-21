package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这张表有没有行」落进 {@code detail_json} 的那几个字节（缺陷 B19）。
 *
 * <p>这一组钉的全是<b>坏了不报错</b>的事：
 * <ul>
 *   <li>「没探到」被写成「是空的」→ 模型照着告诉用户「这张表里没有数据」，一句凭空编出来的结论；</li>
 *   <li>写方和读方的字面量分叉 → 读方永远捞不到任何空表，标记在模型面前静默消失；</li>
 *   <li>全局 ObjectMapper 哪天开了缩进 → 同上，而且没有任何报错。</li>
 * </ul>
 */
class RowPresenceTest {

    private static Map<String, Object> detail() {
        return ConnectorSchemaService.toDetailMap(new ObjectDetail("D1_COMPANYCODE", "BASE TABLE", "公司代码",
                List.of(new FieldDetail("code", "varchar(16)", false, null, null)), Map.of()));
    }

    // ================================================================ 三态

    @Test
    @DisplayName("★ 没探到（null）什么都不写——「不知道」绝不能落成「是空的」")
    void 不知道时不写键() {
        Map<String, Object> m = detail();

        RowPresence.stamp(m, null);

        assertFalse(m.containsKey(RowPresence.KEY),
                "探测失败必须留成「键不在」。写成 EMPTY 等于凭空告诉模型这张表是空的，比不说更糟");
        assertFalse(RowPresence.isEmptyStamped(m));
    }

    @Test
    @DisplayName("确认零行 → EMPTY")
    void 确认空时写EMPTY() {
        Map<String, Object> m = detail();

        RowPresence.stamp(m, false);

        assertEquals(RowPresence.EMPTY, m.get(RowPresence.KEY));
        assertTrue(RowPresence.isEmptyStamped(m));
    }

    @Test
    @DisplayName("至少一行 → NON_EMPTY，且它不会被当成空")
    void 有行时写NON_EMPTY() {
        Map<String, Object> m = detail();

        RowPresence.stamp(m, true);

        assertEquals(RowPresence.NON_EMPTY, m.get(RowPresence.KEY));
        assertFalse(RowPresence.isEmptyStamped(m));
    }

    // ================================================================ 落库的那几个字节

    /**
     * ★ 读方（{@link ConnectorRowPresenceService}）把过滤下推给 MySQL 做，靠的就是这个子串。
     * 拿<b>真实序列化结果</b>核对，而不是拿常量拼常量：这条断言同时钉住了
     * 「键名没被改掉」和「全局 ObjectMapper 还是紧凑输出」两件事——后者一旦变了，
     * 读方会一个空表都捞不到，而且不报错。
     */
    @Test
    @DisplayName("★ EMPTY 的 JSON 子串与读方的 LIKE 模式逐字一致")
    void 序列化后的子串与LIKE模式一致() throws Exception {
        Map<String, Object> m = detail();
        RowPresence.stamp(m, false);

        String json = CommonUtil.getObjectMapper().writeValueAsString(m);

        assertTrue(json.contains(RowPresence.EMPTY_JSON_MARKER),
                "读方按这个子串做 LIKE 预筛，对不上就等于这个特性整条静默失效。实际 JSON: " + json);
    }

    /** ★ {@code "NON_EMPTY"} 里也有 EMPTY 四个字母，两个状态绝不能互相串味。 */
    @Test
    @DisplayName("★ NON_EMPTY 的 JSON 不会被空表的 LIKE 模式命中")
    void 非空表不会被误捞成空表() throws Exception {
        Map<String, Object> m = detail();
        RowPresence.stamp(m, true);

        String json = CommonUtil.getObjectMapper().writeValueAsString(m);

        assertFalse(json.contains(RowPresence.EMPTY_JSON_MARKER),
                "有数据的表被标成空表，模型会告诉用户「这张表里一行都没有」。实际 JSON: " + json);
    }

    /** 没盖章的旧快照同样不该被捞出来——本列上线之前的每一行都是这样。 */
    @Test
    @DisplayName("没盖章的快照不会被捞成空表")
    void 旧快照不会被误捞成空表() throws Exception {
        String json = CommonUtil.getObjectMapper().writeValueAsString(detail());

        assertFalse(json.contains(RowPresence.EMPTY_JSON_MARKER));
    }

    /**
     * 结构指纹只拼对象名、类型和每列的 {@code name:type:nullable:comment}。
     * 一张表从空变成非空<b>不是结构漂移</b>——算进指纹会让挂在它上面的说明书整批被标过期，
     * 和「约 N 行」那个估算值进指纹是同一类错。
     */
    @Test
    @DisplayName("★ 空/非空不进结构指纹：表被灌进数据不算结构变化")
    void 空表标记不影响结构指纹() {
        ObjectDetail d = new ObjectDetail("D1_COMPANYCODE", "BASE TABLE", "公司代码",
                List.of(new FieldDetail("code", "varchar(16)", false, null, null)), Map.of());

        Map<String, Object> empty = ConnectorSchemaService.toDetailMap(d);
        RowPresence.stamp(empty, false);
        Map<String, Object> filled = ConnectorSchemaService.toDetailMap(d);
        RowPresence.stamp(filled, true);

        assertEquals(ConnectorSchemaService.structureFingerprint(d), ConnectorSchemaService.structureFingerprint(d));
        assertFalse(ConnectorSchemaService.structureFingerprint(d).contains(RowPresence.KEY),
                "指纹里一旦出现这个键，客户往空表里插一行就会被报成结构漂移");
        assertEquals(RowPresence.EMPTY, empty.get(RowPresence.KEY));
        assertEquals(RowPresence.NON_EMPTY, filled.get(RowPresence.KEY));
    }
}
