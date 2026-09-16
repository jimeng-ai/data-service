package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.fieldAnchor;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.sha256;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.structureStamp;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.tableStamp;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.tableStamps;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按表的结构指纹（设计文档 8.8）。
 *
 * <p>语义层生成 agent 逐表提交、按表对账、续跑时按表判「已覆盖」，全靠 {@code tableStamp}：
 * 它说没变，这张表上已写入的 FIELD / JOIN 行就被当成仍然成立、不再重生成；它说变了，这张表就回到待生成。
 * 所以这一组测的是<b>它和锚点是不是同一把尺子</b>、<b>一张表的变化会不会波及别的表</b>，而不是「哈希能不能算出来」。
 */
class ConnectorSemanticServiceStampTest {

    private static FieldDetail col(String name, String type, boolean nullable, String comment, String extra) {
        return new FieldDetail(name, type, nullable, comment, extra);
    }

    private static Map<String, FieldDetail> cols(FieldDetail... fs) {
        Map<String, FieldDetail> m = new LinkedHashMap<>();
        for (FieldDetail f : fs) {
            m.put(f.name(), f);
        }
        return m;
    }

    @Nested
    @DisplayName("单表指纹")
    class TableStamp {

        /**
         * ★ 同源：指纹就是由每列的 {@link ConnectorSemanticService#fieldAnchor} 按快照列顺序拼出来的。
         *
         * <p>第一段钉死组成方式；第二段用矩阵钉死「判据一致」——任意两种列形态，
         * 锚点相同 ⇔ 指纹相同。只有 extra（默认值、自增这类补充）不同的两列，锚点不变，指纹也必须不变；
         * 否则「只是 extra 抖了一下」会让这张表整张重新生成，而它的 FIELD 行锚点其实全都还成立。
         * 反过来，类型、可空、注释任一变了锚点就变，指纹也必须变，否则会留下一批锚着旧结构、却被当成「已覆盖」的行。
         */
        @Test
        @DisplayName("tableStamp 由 fieldAnchor 拼成，与锚点对「什么算结构变了」判断一致")
        void tableStamp与fieldAnchor同源() {
            FieldDetail id = col("id", "bigint", false, "主键", "自增");
            FieldDetail amt = col("amt", "decimal(12,2)", true, "订单金额", null);
            assertEquals(sha256("t_ord_mst|" + fieldAnchor(id) + "," + fieldAnchor(amt) + ","),
                    tableStamp("t_ord_mst", cols(id, amt)));
            assertEquals(64, tableStamp("t_ord_mst", cols(id, amt)).length(), "完整 64 位 sha256，不截短");

            List<FieldDetail> variants = List.of(
                    col("amt", "decimal(12,2)", true, "订单金额", "默认 0"),
                    // 只有 extra 不同：锚点不看它，指纹也不该看
                    col("amt", "decimal(12,2)", true, "订单金额", "无默认值"),
                    col("amt", "decimal(14,4)", true, "订单金额", "默认 0"),
                    col("amt", "decimal(12,2)", false, "订单金额", "默认 0"),
                    col("amt", "decimal(12,2)", true, "订单金额（不含税）", "默认 0"),
                    // 注释 null 与空串锚点相同，指纹也相同
                    col("amt", "decimal(12,2)", true, null, "默认 0"),
                    col("amt", "decimal(12,2)", true, "", "默认 0"));
            for (FieldDetail a : variants) {
                for (FieldDetail b : variants) {
                    boolean sameAnchor = fieldAnchor(a).equals(fieldAnchor(b));
                    boolean sameStamp = tableStamp("t_ord_mst", cols(id, a)).equals(tableStamp("t_ord_mst", cols(id, b)));
                    assertEquals(sameAnchor, sameStamp, "指纹与锚点对这两列的判断分叉了：" + a + " / " + b);
                }
            }
        }

        /**
         * 列顺序算进指纹：同样几列换了顺序，{@code SELECT *} 与按位置的写法结果就变了。
         * 顺带钉住：没有列的表也有确定的指纹，而且与有列时不同。
         */
        @Test
        @DisplayName("同样的列换了顺序，指纹就变")
        void 列顺序变化指纹变化() {
            FieldDetail id = col("id", "bigint", false, null, null);
            FieldDetail amt = col("amt", "decimal(12,2)", true, null, null);

            String before = tableStamp("t_ord_mst", cols(id, amt));
            String after = tableStamp("t_ord_mst", cols(amt, id));
            assertNotEquals(before, after);

            Map<String, Map<String, FieldDetail>> snapBefore = new LinkedHashMap<>();
            snapBefore.put("t_ord_mst", cols(id, amt));
            Map<String, Map<String, FieldDetail>> snapAfter = new LinkedHashMap<>();
            snapAfter.put("t_ord_mst", cols(amt, id));
            assertNotEquals(structureStamp(snapBefore), structureStamp(snapAfter), "整份快照指纹同样感知列顺序");

            assertEquals(tableStamp("t_empty", cols()), tableStamp("t_empty", cols()));
            assertNotEquals(tableStamp("t_empty", cols()), tableStamp("t_empty", cols(id)));
        }
    }

    @Nested
    @DisplayName("整份快照指纹")
    class StructureStamp {

        /**
         * ★ 整份快照的指纹由按表指纹拼成（表名排序后 {@code name=tableStamp;}），而且：
         * <ul>
         *   <li>与表的迭代顺序无关——同一份快照按字母序读和按重要性读，必须是同一个值；</li>
         *   <li>一张表的列变了，整份指纹变（增量补写靠它发现「推导期间结构变了」），
         *       但<b>别的表的单表指纹一个都不变</b>——这正是按表比较存在的理由：无关表的刷新不作废整轮。</li>
         * </ul>
         */
        @Test
        @DisplayName("structureStamp 由按表指纹拼成，与表顺序无关；一张表变了不波及别的表")
        void structureStamp由按表指纹拼成() {
            Map<String, FieldDetail> ord = cols(col("id", "bigint", false, "主键", null),
                    col("amt", "decimal(12,2)", true, "订单金额", null));
            Map<String, FieldDetail> dict = cols(col("code", "varchar(16)", false, null, null));

            Map<String, Map<String, FieldDetail>> byImportance = new LinkedHashMap<>();
            byImportance.put("t_ord_mst", ord);
            byImportance.put("a_dict", dict);
            Map<String, Map<String, FieldDetail>> alphabetical = new LinkedHashMap<>();
            alphabetical.put("a_dict", dict);
            alphabetical.put("t_ord_mst", ord);

            assertEquals(sha256("a_dict=" + tableStamp("a_dict", dict) + ";"
                            + "t_ord_mst=" + tableStamp("t_ord_mst", ord) + ";"),
                    structureStamp(byImportance));
            assertEquals(structureStamp(alphabetical), structureStamp(byImportance), "与表的迭代顺序无关");

            Map<String, String> stamps = tableStamps(byImportance);
            assertEquals(List.of("t_ord_mst", "a_dict"), List.copyOf(stamps.keySet()), "tableStamps 保持入参顺序");
            assertEquals(tableStamp("t_ord_mst", ord), stamps.get("t_ord_mst"));
            assertEquals(tableStamp("a_dict", dict), stamps.get("a_dict"));

            // a_dict 的注释被客户改了
            Map<String, Map<String, FieldDetail>> refreshed = new LinkedHashMap<>(byImportance);
            refreshed.put("a_dict", cols(col("code", "varchar(16)", false, "字典编码", null)));
            Map<String, String> after = tableStamps(refreshed);
            assertNotEquals(structureStamp(byImportance), structureStamp(refreshed), "有表变了，整份指纹必须变");
            assertNotEquals(stamps.get("a_dict"), after.get("a_dict"));
            assertEquals(stamps.get("t_ord_mst"), after.get("t_ord_mst"), "无关表的刷新不能作废 t_ord_mst 的指纹");
            assertTrue(after.values().stream().allMatch(v -> v.length() == 64));
        }
    }
}
