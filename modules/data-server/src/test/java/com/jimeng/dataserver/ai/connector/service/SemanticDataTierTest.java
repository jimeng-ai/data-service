package com.jimeng.dataserver.ai.connector.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据出库档位。
 *
 * <p>这个类只有三个常量和几个谓词，但它决定「客户的什么东西可以离开他的数据库」，
 * 所以真正要锁的不是功能，是<b>兜底方向</b>和<b>第 3 档的可达性</b>：
 *
 * <ul>
 *   <li><b>两条兜底方向不同，且都不能反。</b>空值往<b>默认档</b>落（列是后加的，
 *       NULL 只说明「没选过」）；认不出来的值往<b>最严档</b>落（库里有个不认识的字符串，
 *       那是未知，未知必须当最严的处理）。把前者写成最严档，是在一次升级里静默把所有存量客户的
 *       关系推断精确率打对折；把后者写成默认档或更松，是让一个脏值换来数据出库。</li>
 *   <li><b>{@code SAMPLE_VALUES} 不可由任何兜底到达。</b>这是本文件里最要紧的一条：
 *       第 3 档出去的是客户的真实取值，它只能由一个原样匹配的显式取值到达。
 *       下面那组穷举用例就是拦这个的——将来谁把 {@code orElse(MOST_RESTRICTIVE)} 顺手改成
 *       「回落到上一档」，它立刻红。</li>
 * </ul>
 */
class SemanticDataTierTest {

    /** 库里可能出现、但一个都不该被认成合法档位的脏值。集中放一份，两处用例共用。 */
    private static final String[] JUNK = {
            "   ", "\t\n",
            "null", "undefined", "NaN",
            // ★ 数字形式必须认不出来。存数字这件事本身就是这个设计要避免的（会长出写反的 >=），
            // 所以「3」绝不能解析成第 3 档——哪怕有人在库里手工写了个 3。
            "0", "1", "2", "3", "4", "-1",
            "TIER_3", "TIER3", "LEVEL_3",
            "true", "false",
            // 差几个字母、看着像对的
            "SAMPLE_VALUE", "SAMPLE-VALUES", "SAMPLE VALUES", "SAMPLEVALUES",
            "DERIVED_STAT", "DERIVED-STATS", "METADATA", "METADATA_ONLY_",
            // 别处的命名习惯串过来
            "FULL", "ALL", "NONE", "OFF", "ON", "RAW", "TOP_K", "SAMPLING",
            // 将来可能新增、但这个版本还不认识的档位
            "SAMPLE_VALUES_V2", "DERIVED_STATS_PLUS",
            // 中文文案被当成值传了上来
            "样本值", "派生统计", "纯元数据"
    };

    @Nested
    @DisplayName("兜底：空值 → 默认档")
    class BlankFallsBackToDefault {

        /**
         * 这一列是后加的，加列之前建的连接这一格是 NULL。NULL <b>不</b>表示客户选过最严的那档，
         * 只表示他没选过。把没选过一律压到第 1 档，等于在一次升级里静默把所有存量客户的
         * 关系推断精确率从约 1.00 打到约 0.49，而没有任何人会收到通知。
         */
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", "  \t \n "})
        void 空值落到默认档而不是最严档(String raw) {
            assertEquals(SemanticDataTier.DEFAULT, SemanticDataTier.parse(raw),
                    "空值是「没选过」，不是「选了最严的」——落到默认档");
            assertTrue(SemanticDataTier.parse(raw).allowsDerivedStats(),
                    "存量连接必须还能算包含率，否则一次升级就把 S3 采样验证全关了，且没人知道");
        }

        /** 默认档本身也要锁死：它<b>永远不能</b>是第 3 档。 */
        @Test
        void 默认档是第2档且绝不是第3档() {
            assertEquals(SemanticDataTier.DERIVED_STATS, SemanticDataTier.DEFAULT);
            assertFalse(SemanticDataTier.DEFAULT.allowsSampleValues(),
                    "默认档一旦能出真实取值，「默认关闭」这句承诺就是假的");
        }

        @Test
        void 空值不会拿到样本值许可() {
            assertFalse(SemanticDataTier.parse(null).allowsSampleValues());
            assertFalse(SemanticDataTier.parse("").allowsSampleValues());
            assertFalse(SemanticDataTier.parse("   ").allowsSampleValues());
        }
    }

    @Nested
    @DisplayName("兜底：认不出来 → 最严档")
    class UnknownFallsBackToMostRestrictive {

        @ParameterizedTest
        @ValueSource(strings = {
                "null", "undefined", "0", "1", "2", "3", "TIER_3", "true", "FULL", "ALL",
                "SAMPLE_VALUE", "SAMPLE-VALUES", "SAMPLE VALUES", "TOP_K", "RAW",
                "SAMPLE_VALUES_V2", "样本值"
        })
        void 认不出来的值一律判为纯元数据(String raw) {
            SemanticDataTier t = SemanticDataTier.parse(raw);
            assertEquals(SemanticDataTier.METADATA_ONLY, t,
                    "兜底方向错了：库里有个不认识的字符串是「未知」，未知必须当最严的处理");
            assertFalse(t.allowsDerivedStats());
            assertFalse(t.allowsSampleValues());
        }

        /**
         * ★ 这条是给「以后往枚举里加档位的人」准备的。
         *
         * <p>新加一档若忘了处理解析，parse 会把它落到最严档——那是安全的、也是预期的。
         * 但若有人为了省事把兜底改成「回落到上一档」或「回落到默认档」，这条立刻红。
         */
        @Test
        void 未来新增的未知档位也落到最严档() {
            assertEquals(SemanticDataTier.METADATA_ONLY, SemanticDataTier.parse("SOME_FUTURE_TIER_V9"));
            assertEquals(SemanticDataTier.METADATA_ONLY, SemanticDataTier.MOST_RESTRICTIVE);
        }

        /**
         * ★ 本文件最要紧的一条：<b>第 3 档不可由任何兜底到达</b>。
         *
         * <p>第 3 档出去的是客户库里一行行的真实取值。它只能由一个原样匹配的显式取值到达，
         * 不能由空值到达、不能由脏值到达、不能由数字 3 到达。
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "   ", "null", "undefined", "0", "1", "2", "3", "4", "TIER_3", "TIER3", "LEVEL_3",
                "true", "FULL", "ALL", "RAW", "TOP_K", "SAMPLING",
                "SAMPLE_VALUE", "SAMPLE-VALUES", "SAMPLE VALUES", "SAMPLEVALUES",
                "SAMPLE_VALUES_V2", "样本值"
        })
        void 任何兜底都到不了样本值档(String raw) {
            assertNotEquals(SemanticDataTier.SAMPLE_VALUES, SemanticDataTier.parse(raw),
                    "真实取值出库只能是一个显式动作，绝不能由兜底到达：" + raw);
            assertFalse(SemanticDataTier.parse(raw).allowsSampleValues());
        }

        @Test
        void 连空值也到不了样本值档() {
            assertNotEquals(SemanticDataTier.SAMPLE_VALUES, SemanticDataTier.parse(null));
        }

        @Test
        void 穷举脏值都到不了样本值档() {
            for (String raw : JUNK) {
                assertFalse(SemanticDataTier.parse(raw).allowsSampleValues(),
                        "这个值不该换来样本值许可：" + raw);
            }
        }
    }

    @Nested
    @DisplayName("parse 能认出的写法")
    class ParseAccepts {

        @ParameterizedTest
        @EnumSource(SemanticDataTier.class)
        void 每个枚举名都能原样解析回自己(SemanticDataTier t) {
            // 漏了它就意味着那一档配置上去也生效不了，而界面显示的还是它——一个不报错的配置失效。
            assertEquals(t, SemanticDataTier.parse(t.name()));
        }

        @ParameterizedTest
        @EnumSource(SemanticDataTier.class)
        void 小写与首尾空白也能解析(SemanticDataTier t) {
            // 库里的值是人手工改过的、也可能来自不同版本的前端，大小写与空白都不可控。
            assertEquals(t, SemanticDataTier.parse(" " + t.name().toLowerCase() + " "));
        }

        @Test
        void 大小写混写能解析() {
            assertEquals(SemanticDataTier.SAMPLE_VALUES, SemanticDataTier.parse("Sample_Values"));
            assertEquals(SemanticDataTier.DERIVED_STATS, SemanticDataTier.parse("dErIvEd_StAtS"));
            assertEquals(SemanticDataTier.METADATA_ONLY, SemanticDataTier.parse("metadata_only"));
        }
    }

    /**
     * 严格解析是给<b>接口入参校验</b>用的：库里的脏值必须兜底（没人可问），
     * 入参必须报错（有人可问）。混成一种，就会出现「界面显示第 3 档、实际存的是第 1 档」
     * 这种不报错的配置失效。
     */
    @Nested
    @DisplayName("tryParse 严格，不做任何兜底")
    class TryParseIsStrict {

        @ParameterizedTest
        @EnumSource(SemanticDataTier.class)
        void 合法值返回它自己(SemanticDataTier t) {
            assertEquals(t, SemanticDataTier.tryParse(" " + t.name().toLowerCase() + " ").orElse(null));
        }

        @Test
        void 脏值一律空() {
            for (String raw : JUNK) {
                assertTrue(SemanticDataTier.tryParse(raw).isEmpty(),
                        "严格解析不该认这个值：" + raw);
            }
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void 空值也是空_留空由调用方按_省略_处理(String raw) {
            // 留空在接口上是合法的（= 默认档），但那是 ConnectorService 的判断，不是解析的判断。
            assertTrue(SemanticDataTier.tryParse(raw).isEmpty());
        }
    }

    @Nested
    @DisplayName("谓词：按意图问许可，不比大小")
    class Predicates {

        @Test
        void 结构永远可读() {
            // 不许读结构等于不许接入，那种连接不该存在，而不该配成一个档位。
            for (SemanticDataTier t : SemanticDataTier.values()) {
                assertTrue(t.allowsMetadata(), t + " 必须允许读结构");
            }
        }

        @Test
        void 派生统计从第2档起() {
            assertFalse(SemanticDataTier.METADATA_ONLY.allowsDerivedStats());
            assertTrue(SemanticDataTier.DERIVED_STATS.allowsDerivedStats());
            // 容易写反的一处：第 3 档是在第 2 档【之上】叠加，不是替代它。
            // 写成 this == DERIVED_STATS，S3 的包含率计算会在开了第 3 档的连接上反而跑不了。
            assertTrue(SemanticDataTier.SAMPLE_VALUES.allowsDerivedStats(),
                    "第 3 档是叠加在第 2 档之上的，不是替代");
        }

        @Test
        void 样本值只有第3档() {
            assertFalse(SemanticDataTier.METADATA_ONLY.allowsSampleValues());
            assertFalse(SemanticDataTier.DERIVED_STATS.allowsSampleValues());
            assertTrue(SemanticDataTier.SAMPLE_VALUES.allowsSampleValues());
        }

        /**
         * 只有一档能出真实取值。写成 {@code >=} 而不是 {@code ==} 的那类错，
         * 在这里表现为「不止一档返回 true」。
         */
        @Test
        void 能出真实取值的只有一档() {
            long n = java.util.Arrays.stream(SemanticDataTier.values())
                    .filter(SemanticDataTier::allowsSampleValues).count();
            assertEquals(1, n, "能把真实取值带出客户库的档位必须有且只有一个");
        }
    }

    @Nested
    @DisplayName("给人看的文案：安全说明的落点")
    class Statements {

        @ParameterizedTest
        @EnumSource(SemanticDataTier.class)
        void 每一档都有非空短名与出库说明(SemanticDataTier t) {
            // label()/egressStatement() 是 switch 穷举的，新增枚举值不补分支会编译失败——
            // 这条只兜住「补了个空串」。界面上这一格空着，客户就看不出自己选的是什么。
            assertNotNull(t.label());
            assertFalse(t.label().isBlank());
            assertNotNull(t.egressStatement());
            assertFalse(t.egressStatement().isBlank());
        }

        /**
         * ★ 第 3 档的说明<b>必须说出「真实取值」</b>。
         *
         * <p>这不是文案洁癖。同类产品（Quick BI 的「维值学习」）索引的就是真实取值，
         * 而它的官方数据安全页回避了这件事——客户于是在不知情的前提下点了同意，那不是同意。
         * 这条用例把「不照搬那种叙事」变成一个会红的断言：谁把这句话改软了，测试立刻拦下。
         */
        @Test
        void 第3档必须明说索引的是真实取值() {
            String s = SemanticDataTier.SAMPLE_VALUES.egressStatement();
            assertTrue(s.contains("真实取值"),
                    "第 3 档的出库说明必须出现「真实取值」四个字，不许回避：" + s);
            assertTrue(s.contains("默认关闭"), "必须说清这一档默认是关的");
        }

        /** 第 2 档也不许说成「完全不碰数据」：min/max 本身就是两个真实数字。 */
        @Test
        void 第2档要如实说明聚合结果也带信息() {
            String s = SemanticDataTier.DERIVED_STATS.egressStatement();
            assertTrue(s.contains("min/max"), "第 2 档要点名 min/max 这类会漏出真实数字的统计量：" + s);
            assertTrue(s.contains("哈希"), "minhash sketch 是哈希值不是原始值，这一点要说清：" + s);
        }

        /** 第 1 档不是免费的：仅凭名字推关系精确率约 0.49，这个代价要写在明面上。 */
        @Test
        void 第1档要写清代价() {
            assertTrue(SemanticDataTier.METADATA_ONLY.egressStatement().contains("0.49"),
                    "选最严档的客户应当同时知道自己付的是什么");
        }
    }
}
