package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.persistence.entity.ConnectorSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.col;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.ctx;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.field;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.join;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.key;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.object;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.run;
import static com.jimeng.dataserver.ai.connector.generation.consistency.RuleFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 6 {@link CodeValueMappingRule}：gloss / note 里的「码值 → 含义」必须在相关注释里有同一组对照。
 *
 * <p>正则判定一定有误报和漏报，这一组用表驱动把<b>已知</b>的两类边界钉住：误报（正常句子被当成码值）会逼 agent 删掉一句
 * 正确的话；漏报（码值含义没认出来）会让一句编出来的对照入库。表里标「已知误报 / 已知漏报」的行是现状的如实记录，
 * 不是期望——改正则时它们应该朝正确的方向翻，而不是被顺手改掉期望值。
 */
class CodeValueMappingRuleTest {

    private final CodeValueMappingRule rule = new CodeValueMappingRule();

    /** 一张表 t_x：列 c 的注释、表注释可控，另有一列 other 带着自己的注释（OBJECT 条目会看全部列注释）。 */
    private static RuleContext ctxWith(String columnComment, String tableComment, String otherColumnComment) {
        List<ConnectorSchema> snapshot = List.of(
                table("t_x", tableComment, List.of(key("PRIMARY", true, "id")),
                        col("id", "bigint", null), col("c", "tinyint", columnComment), col("other", "int", otherColumnComment)),
                table("t_y", null, List.of(key("PRIMARY", true, "id")), col("id", "bigint", null), col("st", "int", "3=已发货")));
        return ctx(snapshot, List.of());
    }

    private List<RuleViolation> checkField(String gloss, String columnComment, String tableComment) {
        return rule.check(field(0, "t_x", "c", "gloss", gloss, "evidence", "NAME"), ctxWith(columnComment, tableComment, null));
    }

    @Nested
    @DisplayName("判定")
    class Verdict {

        @Test
        @DisplayName("注释里有同一组对照就通过；gloss 没写码值不管；基数写法不算码值")
        void 通过() {
            assertTrue(run(rule, field(0, "t_ord", "st", "gloss", "订单状态，0=待支付，1=已支付", "evidence", "COMMENT")).isEmpty(),
                    "t_ord.st 的注释是「状态：0-待支付，1-已支付」");
            assertTrue(run(rule, field(0, "t_ord", "amt", "gloss", "订单金额，单位元", "evidence", "COMMENT")).isEmpty());
            assertTrue(run(rule, join(0, "cust_id", "t_cust", "id", "note", "N:1，一个订单一个客户")).isEmpty());
            assertTrue(run(rule, object("t_ord", "gloss", "订单主表，其中 st 取 0=待支付、1=已支付", "evidence", "COMMENT")).isEmpty(),
                    "OBJECT 看表注释加本表全部列注释");
        }

        @Test
        @DisplayName("★ 注释里没有这组对照：退回，文案举出 gloss 里的那一组并给出改写方向")
        void 退回且原因文案可读() {
            List<RuleViolation> vs = run(rule, field(0, "t_cust", "level", "gloss", "客户等级：1=普通，2=VIP", "evidence", "NAME"));
            assertEquals(1, vs.size());
            assertEquals(RuleViolation.Severity.REJECT, vs.get(0).severity());
            String m = vs.get(0).message();
            assertTrue(m.contains("gloss 里写了码值含义（如 1=普通）"), m);
            assertTrue(m.contains("快照注释里没有这组对照"), m);
            assertTrue(m.contains("改写成『取值含义未知，使用前必须确认』，或者删掉这句"), m);

            List<RuleViolation> partial = run(rule, field(0, "t_ord", "st", "gloss", "0=待支付，1=已支付，2=已取消", "evidence", "COMMENT"));
            assertEquals(1, partial.size(), "注释只有 0、1，gloss 多编了一个 2");
            assertTrue(partial.get(0).message().contains("（如 2=已取消）"), partial.get(0).message());

            List<RuleViolation> note = run(rule, join(0, "cust_id", "t_cust", "id", "note", "只有 type 为 3 表示企业客户时才关联"));
            assertEquals(1, note.size(), "关系看 note");
            assertTrue(note.get(0).message().startsWith("note 里写了码值含义（如 3表示企业客户"), note.get(0).message());
        }

        @Test
        @DisplayName("码值字母只认大写、注释一侧同样按大写认；全角等号照样认；反引号夹在码值与分隔符之间时两侧都不算对照形式")
        void 大小写与反引号边界() {
            assertEquals(1, checkField("A-启用，B-停用", null, null).size(), "大写字母码值");
            assertTrue(checkField("a-启用，b-停用", null, null).isEmpty(), "小写字母不认作码值（已知漏报的另一面：不误伤 a-z 这类写法）");
            assertTrue(checkField("A-启用，B-停用", "状态 A-启用 B-停用", null).isEmpty());
            assertEquals(1, checkField("A-启用", "状态 a-启用", null).size(), "注释写小写 a，不算同一组对照");
            assertEquals(1, checkField("0＝否", null, null).size(), "全角等号");
            assertTrue(checkField("`0`=否", null, null).isEmpty(),
                    "反引号夹在码值与等号之间，正则要求码值后紧跟分隔符，认不出（已知漏报）");
            assertEquals(1, checkField("取值为 `1=是`", null, null).size(),
                    "反引号不在码值前面的排除字符里：`1=是` 里的 1 照样抽出来");
            assertEquals(1, checkField("0=否", "取值 `0`=否", null).size(),
                    "注释里的 `0` 后面紧跟反引号，不是对照形式——宁可退回让 agent 改写，也不据此放行");
        }
    }

    // ================================================================ 表驱动：误报与漏报

    static Stream<Arguments> cases() {
        return Stream.of(
                // 名称, gloss, 列注释, 表注释, 期望退回
                // ── 误报防线：这些句子里没有码值含义，必须通过 ──
                Arguments.of("说码值未知的规范写法", "状态码。取值含义未知，注释里没有对照表，用于过滤前必须确认", null, null, false),
                Arguments.of("日期时间", "创建时间，格式 2024-01-01 10:00:00", null, null, false),
                Arguments.of("小数位数", "金额，单位元，保留 2 位小数", null, null, false),
                Arguments.of("基数 1:N", "1:N 关系，一个客户多个订单", null, null, false),
                Arguments.of("全角冒号基数", "N：1 指向客户", null, null, false),
                Arguments.of("UTF-8 与 ID 缩写", "UTF-8 编码的 JSON，ID：用户编号", null, null, false),
                Arguments.of("小数与百分比", "阈值 0.95 表示 95% 命中", null, null, false),
                Arguments.of("top_k=5", "检索参数 top_k=5 命中 4 个分片", null, null, false),
                Arguments.of("区间 0-100", "置信度 0-100 的整数", null, null, false),
                Arguments.of("注释里有同一组对照（横线写法）", "0=未删除，1=已删除", "逻辑删除：0-未删除，1-已删除", null, false),
                Arguments.of("注释里有同一组对照（紧凑写法）", "0=正常，1=停用", "帐号状态（0正常 1停用）", null, false),
                Arguments.of("注释里有同一组对照（表示）", "0 表示存在，2 表示删除", "删除标志（0代表存在 2代表删除）", null, false),
                Arguments.of("对照写在表注释里", "Y=是，N=否", null, "是否默认 Y=是 N=否", false),
                Arguments.of("注释用字母码值", "1=AES-GCM 加密", "1=AES-GCM。0 不允许写入", null, false),
                // ── 漏报防线：这些是编出来的码值含义，必须退回 ──
                Arguments.of("典型编造", "0=待支付，1=已支付，2=已取消", null, null, true),
                Arguments.of("表示", "状态码：0 表示正常，1 表示停用", null, null, true),
                Arguments.of("箭头", "类型，1→个人，2→企业", null, null, true),
                Arguments.of("ASCII 箭头", "类型，1->个人", null, null, true),
                Arguments.of("负数码值", "-1=已作废", null, null, true),
                Arguments.of("冒号", "等级 1:铜牌 2:银牌", null, null, true),
                Arguments.of("注释有别的码值", "3=已发货", "0-待支付，1-已支付", null, true),
                Arguments.of("注释里只是数字不是对照", "2=企业", "最多 2 个", null, true),
                // ── 已知误报：正则把正常写法当成了码值（现状如实记录） ──
                Arguments.of("已知误报：正则字符类 A-Z", "名称只允许 [A-Za-z0-9_-] 字符", null, null, true),
                Arguments.of("已知误报：1-N 写法", "1-N 关系", null, null, true),
                // ── 已知漏报：码值含义没被认出来（现状如实记录） ──
                Arguments.of("已知漏报：码值与含义之间只有空格", "1 是 0 否", null, null, false),
                Arguments.of("已知漏报：紧凑写法在 gloss 一侧不认", "0正常 1停用", null, null, false),
                Arguments.of("已知漏报：带字母前缀的码值", "优先级 P0-最高 P1-高", null, null, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("表驱动：误报与漏报")
    void 误报与漏报(String name, String gloss, String columnComment, String tableComment, boolean rejected) {
        List<RuleViolation> vs = checkField(gloss, columnComment, tableComment);
        assertEquals(rejected, !vs.isEmpty(), name + "：gloss=「" + gloss + "」 列注释=「" + columnComment
                + "」 表注释=「" + tableComment + "」 结果=" + vs);
    }
}
