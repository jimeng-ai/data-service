package com.jimeng.dataserver.ai.connector.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 说明书「是不是全本」的判据。
 *
 * <p>这个类本身很小，但它回答的问题是<b>整条语义层链路上唯一没有失败信号的那一类失败</b>：
 * 说明书缺了几张表，模型照样能答，答出来是个看着很正常的错数字。所以这里锁的不是「能跑」，
 * 是下面三条不能被后来的改动悄悄推翻的性质：
 *
 * <ul>
 *   <li><b>残缺永远不许被判成完整。</b>四种成因任意一种命中就是 PARTIAL，
 *       不做权重、不做「只少一张就算了」——少 1 张和少 40 张，对模型是同一件事：
 *       它都不知道自己少看了东西。</li>
 *   <li><b>完整必须把上一轮的成因码清掉。</b>gapCodes() 在完整时返回空串而不是 null，
 *       因为这两列跟 note 用同一个实体更新落库，而 MyBatis-Plus 的 NOT_NULL 策略写不了 null。
 *       返回 null 的后果是行上留着「COMPLETE + 上一轮的 TABLES_MISSING」，
 *       一行说自己完整却列着缺口的记录——比没有信号更坏，它让人相信一个错的答案。</li>
 *   <li><b>数不出分母时不许硬判。</b>total=0 只说明「这次算不出本该覆盖多少」，
 *       不说明「一张都没缺」，更不说明「全缺了」。它不能单独把结论推向任何一边。</li>
 * </ul>
 */
@DisplayName("语义层覆盖面判定（说明书是不是全本）")
class SemanticCoverageTest {

    /** 一次干干净净的全覆盖：14 张表全覆盖到、没放弃、快照没截、模型没截。 */
    private static SemanticCoverage.Facts clean() {
        return new SemanticCoverage.Facts(14, 14, 0, false, false);
    }

    @Nested
    @DisplayName("完整")
    class Complete {

        @Test
        @DisplayName("四条判据都不命中才算完整，且成因码被清成空串")
        void allClear() {
            SemanticCoverage.Verdict v = SemanticCoverage.assess(clean());

            assertEquals(SemanticCoverage.COMPLETE, v.coverage());
            assertFalse(v.partial());
            assertEquals(List.of(), v.gaps());
            // ★ 空串，不是 null。null 会让上一轮的成因码留在行上（见类注释第二条）。
            assertEquals("", v.gapCodes());
            assertEquals("COMPLETE", v.code());
        }

        @Test
        @DisplayName("覆盖数比总数还大（计数口径对不齐）不算残缺——它不是「有东西没进说明书」")
        void doneAboveTotalIsNotAGap() {
            assertEquals(SemanticCoverage.COMPLETE,
                    SemanticCoverage.assess(new SemanticCoverage.Facts(9, 14, 0, false, false)).coverage());
        }
    }

    @Nested
    @DisplayName("四种残缺成因，每一种单独都足以判残缺")
    class Gaps {

        @Test
        @DisplayName("14 张表只覆盖 9 张：TABLES_MISSING")
        void tablesMissing() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(14, 9, 0, false, false));

            assertTrue(v.partial());
            assertEquals(List.of(SemanticGap.TABLES_MISSING), v.gaps());
            assertEquals("TABLES_MISSING", v.gapCodes());
            assertEquals("PARTIAL", v.code());
        }

        @Test
        @DisplayName("有表反复失败后被放弃：TABLES_GAVE_UP（即使覆盖数看起来已经齐了）")
        void tablesGaveUp() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(14, 14, 2, false, false));

            assertTrue(v.partial());
            assertEquals(List.of(SemanticGap.TABLES_GAVE_UP), v.gaps());
        }

        @Test
        @DisplayName("结构快照自己就被截断：SNAPSHOT_TRUNCATED——缺口在语义层之前，重生成补不回来")
        void snapshotTruncated() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(200, 200, 0, true, false));

            assertTrue(v.partial());
            assertEquals(List.of(SemanticGap.SNAPSHOT_TRUNCATED), v.gaps());
        }

        @Test
        @DisplayName("模型输出被 max_tokens 截断：MODEL_OUTPUT_TRUNCATED——截断修复成功也必须出声")
        void modelOutputTruncated() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(14, 14, 0, false, true));

            assertTrue(v.partial());
            assertEquals(List.of(SemanticGap.MODEL_OUTPUT_TRUNCATED), v.gaps());
            assertEquals("MODEL_OUTPUT_TRUNCATED", v.gapCodes());
        }

        @Test
        @DisplayName("缺陷 B6 的原始现场：上一轮 9/14 + 本轮模型输出被截断，两条成因都留下，不互相盖掉")
        void theRealWorldCase() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(14, 9, 0, false, true));

            assertTrue(v.partial());
            assertEquals(List.of(SemanticGap.TABLES_MISSING, SemanticGap.MODEL_OUTPUT_TRUNCATED), v.gaps());
            assertEquals("TABLES_MISSING,MODEL_OUTPUT_TRUNCATED", v.gapCodes());
        }

        @Test
        @DisplayName("四条全中：成因码按枚举声明序拼，落库的串是稳定的（不随判定顺序抖动）")
        void allFour() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(14, 9, 3, true, true));

            assertEquals("TABLES_MISSING,TABLES_GAVE_UP,SNAPSHOT_TRUNCATED,MODEL_OUTPUT_TRUNCATED",
                    v.gapCodes());
        }
    }

    @Nested
    @DisplayName("数不出来的计数：不许把「不知道」推成任何一个结论")
    class UnknownCounts {

        @Test
        @DisplayName("总数为 null（这条路径算不出分母）不单独判残缺")
        void nullTotal() {
            assertFalse(SemanticCoverage.assess(
                    new SemanticCoverage.Facts(null, 9, 0, false, false)).partial());
        }

        @Test
        @DisplayName("总数为 0 不单独判残缺——0 是「算不出来」，不是「一张都没缺」也不是「全缺了」")
        void zeroTotal() {
            assertFalse(SemanticCoverage.assess(
                    new SemanticCoverage.Facts(0, 0, 0, false, false)).partial());
        }

        @Test
        @DisplayName("计数为 null 不会 NPE，按 0 处理；null 的放弃数也不会凭空判出一条缺口")
        void nullCountsAreZero() {
            SemanticCoverage.Facts facts = new SemanticCoverage.Facts(null, null, null, false, false);

            assertEquals(0, facts.total());
            assertEquals(0, facts.done());
            assertEquals(0, facts.gaveUp());
            assertFalse(SemanticCoverage.assess(facts).partial());
        }

        @Test
        @DisplayName("负数计数按 0 处理，不会把脏数据放大成一条假缺口")
        void negativeCountsAreClamped() {
            assertFalse(SemanticCoverage.assess(
                    new SemanticCoverage.Facts(-5, -1, -2, false, false)).partial());
        }

        @Test
        @DisplayName("分母数不出来，但模型输出被截断了——照样判残缺：这两件事互不依赖")
        void unknownTotalStillSeesOtherGaps() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(null, 0, 0, false, true));

            assertTrue(v.partial());
            assertEquals(List.of(SemanticGap.MODEL_OUTPUT_TRUNCATED), v.gaps());
        }
    }

    @Nested
    @DisplayName("成因码是落库的数据，不是显示文案")
    class Codes {

        @Test
        @DisplayName("每个成因都有中文短名和一句说后果的话，且都不为空")
        void everyGapExplainsItself() {
            for (SemanticGap gap : SemanticGap.values()) {
                assertFalse(gap.label().isBlank(), gap + " 缺中文短名");
                assertFalse(gap.consequence().isBlank(), gap + " 缺后果说明");
            }
        }

        @Test
        @DisplayName("成因码 = 枚举常量名。改名字等于改已落库的数据，这条用例就是那道闸")
        void codesAreFrozen() {
            assertEquals(List.of("TABLES_MISSING", "TABLES_GAVE_UP",
                            "SNAPSHOT_TRUNCATED", "MODEL_OUTPUT_TRUNCATED"),
                    java.util.Arrays.stream(SemanticGap.values()).map(Enum::name).toList());
        }

        @Test
        @DisplayName("gaps 列表是不可变的：判定结果要被两条写入路径共用，谁都不许就地改它")
        void verdictGapsAreImmutable() {
            SemanticCoverage.Verdict v =
                    SemanticCoverage.assess(new SemanticCoverage.Facts(14, 9, 0, false, false));

            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> v.gaps().add(SemanticGap.TABLES_GAVE_UP));
        }
    }
}
