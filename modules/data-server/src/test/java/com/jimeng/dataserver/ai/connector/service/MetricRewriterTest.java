package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.service.MetricRewriter.MetricContext;
import com.jimeng.persistence.entity.ConnectorSemantic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口径的确定性命中。
 *
 * <p><b>这个类里每一条失败都是静默的。</b>误把一条口径宣布成「唯一标准」不会抛异常、不会进审计，
 * 它只会让模型按一个错口径写出一条语法完全正确的 SQL，返回一个看起来很正常的错数字。
 * 所以这里测的不是「有没有返回值」，而是五类会悄悄发生的事故：
 *
 * <ol>
 *   <li><b>不该强制的被强制了</b>：机器推断（INFERRED）、还没确认（DRAFT）、锚点已失效（STALE）
 *       的口径混进来。口径的答案不在库里，一条编出来的口径被写成「唯一标准」比不给更糟。</li>
 *   <li><b>误命中</b>：{@code ROI} 落在 {@code trois} 里、口径词落在一段粘贴的 SQL 字面量里。</li>
 *   <li><b>换个部署地就不匹配</b>：土耳其语 locale 把 {@code "ROI"} 小写成 {@code "roı"}，
 *       于是库里的词条和用户敲的对不上——不报错，只是从此再也命中不了。</li>
 *   <li><b>静默截断</b>：命中五条只给了两条，而文本里看不出被砍过，模型把「没提到」读成「没有口径」。</li>
 *   <li><b>依据丢了</b>：管理台的删除入口只对企业超管开放，「9月13日由张三确认」是口径被改坏之后
 *       【业务方】唯一看得见它的地方，也是「去找超管删掉」这条唯一纠错链的起点。</li>
 * </ol>
 *
 * <p>纯单测：不起 Spring、不连库、不叫模型——匹配逻辑本身就是静态纯函数。
 */
class MetricRewriterTest {

    private static final String GLOSS = "订单实付金额减去退款金额";

    // ================================================================ 构造

    /** 一条有资格被强制执行的口径：HUMAN + CONFIRMED + METRIC，带人名和日期。 */
    private static ConnectorSemantic metric(String term, String gloss) {
        ConnectorSemantic m = new ConnectorSemantic();
        m.setScope(ConnectorSemanticService.SCOPE_METRIC);
        m.setObjectName("");
        m.setFieldName("");
        m.setTerm(term);
        m.setGloss(gloss);
        m.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        m.setStatus(ConnectorSemanticService.ST_CONFIRMED);
        m.setEvidence(ConnectorSemanticService.EV_GUESS);
        m.setVerified(ConnectorSemanticService.V_NONE);
        m.setAnsweredName("张三");
        m.setAnsweredAt(date(2025, Calendar.SEPTEMBER, 13));
        return m;
    }

    private static ConnectorSemantic metric(String term) {
        return metric(term, GLOSS);
    }

    private static Date date(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m, d);
        return c.getTime();
    }

    private static MetricContext detect(String text, ConnectorSemantic... rows) {
        return MetricRewriter.detectWithLimits(text, List.of(rows), 5, 4000);
    }

    private static List<String> terms(MetricContext ctx) {
        List<String> out = new ArrayList<>();
        ctx.getHits().forEach(h -> out.add(h.getTerm()));
        return out;
    }

    // ================================================================ 资格

    @Nested
    @DisplayName("只有人确认过的口径才有资格被强制摆出来")
    class Eligibility {

        @Test
        @DisplayName("HUMAN + CONFIRMED 命中")
        void humanConfirmed() {
            MetricContext ctx = detect("上个月销售额多少？", metric("销售额"));
            assertTrue(ctx.hasHits());
            assertEquals(List.of("销售额"), terms(ctx));
            assertTrue(ctx.getBlock().contains(GLOSS));
        }

        /**
         * 机器推断的口径<b>根本不该存在</b>：口径的答案不在数据库里，任何看起来推出来的都是编的。
         * 把它按「唯一标准」的措辞塞给模型，是这个功能最坏的失败方式。
         */
        @Test
        @DisplayName("INFERRED 的口径永远不注入")
        void inferredNeverForced() {
            ConnectorSemantic m = metric("销售额");
            m.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            assertFalse(MetricRewriter.isEligible(m));
            assertNull(detect("上个月销售额多少？", m).getBlock());
        }

        @Test
        @DisplayName("DRAFT 的口径不注入")
        void draftNotForced() {
            ConnectorSemantic m = metric("销售额");
            m.setStatus(ConnectorSemanticService.ST_DRAFT);
            assertNull(detect("上个月销售额多少？", m).getBlock());
        }

        /** STALE 的口径带着一段引用了已改列的 SQL 片段，而错的口径不会报错。 */
        @Test
        @DisplayName("STALE 的口径不注入")
        void staleNotForced() {
            ConnectorSemantic m = metric("销售额");
            m.setStatus(ConnectorSemanticService.ST_STALE);
            assertNull(detect("上个月销售额多少？", m).getBlock());
        }

        /** IMPORTED 给不出「谁在什么时候确认的」，也就兑现不了「引用时亮出依据」那条要求。 */
        @Test
        @DisplayName("IMPORTED 的口径不注入")
        void importedNotForced() {
            ConnectorSemantic m = metric("销售额");
            m.setSource(ConnectorSemanticService.SOURCE_IMPORTED);
            assertFalse(MetricRewriter.isEligible(m));
            assertNull(detect("上个月销售额多少？", m).getBlock());
        }

        /**
         * CAVEAT 行装的是<b>问题</b>不是答案（「status 取值含义未知，用前必须确认」）。
         * 把它按「已确认的唯一标准」摆出去，等于把一个待确认项宣布成结论。
         */
        @Test
        @DisplayName("scope 不是 METRIC 的行被挡在门外")
        void onlyMetricScope() {
            ConnectorSemantic m = metric("销售额");
            m.setScope(ConnectorSemanticService.SCOPE_CAVEAT);
            assertFalse(MetricRewriter.isEligible(m));
            assertNull(detect("上个月销售额多少？", m).getBlock());
        }

        @Test
        @DisplayName("gloss 或 term 是空的行被挡在门外")
        void blankRows() {
            assertFalse(MetricRewriter.isEligible(metric("销售额", "   ")));
            assertFalse(MetricRewriter.isEligible(metric("  ", GLOSS)));
            assertFalse(MetricRewriter.isEligible(null));
        }

        /**
         * 不拿 {@code verified} 当门槛：采样验证是给结构推断用的，口径压根不在数据库里，
         * {@code V_NONE} 就是它的正常值。拿它过滤会把所有口径挡光。
         */
        @Test
        @DisplayName("verified=NONE 不影响资格")
        void verifiedIsNotAGate() {
            ConnectorSemantic m = metric("销售额");
            m.setVerified(ConnectorSemanticService.V_NONE);
            assertTrue(MetricRewriter.isEligible(m));
        }
    }

    // ================================================================ 精确匹配

    @Nested
    @DisplayName("精确匹配：只认字面出现，不做模糊识别与推理")
    class ExactMatching {

        @Test
        @DisplayName("没出现就不命中")
        void noOccurrenceNoHit() {
            MetricContext ctx = detect("上个月订单量多少？", metric("销售额"));
            assertFalse(ctx.hasHits());
            assertNull(ctx.getBlock());
        }

        @Test
        @DisplayName("大小写与首尾空白都归一")
        void caseAndPaddingCollapse() {
            assertTrue(detect("今年的 roi 怎么样", metric(" ROI ", "投产比")).hasHits());
        }

        /**
         * ★ 土耳其语 locale 下 {@code "ROI".toLowerCase()} 是 {@code "roı"}（点不见了的那个 i），
         * 而用户敲的 {@code "roi"} 小写还是 {@code "roi"}——两边对不上，同一份代码换个部署地
         * 就悄悄再也命中不了，没有任何报错。所以归一必须显式用 {@code Locale.ROOT}。
         */
        @Test
        @DisplayName("土耳其语 locale 下 ROI 仍然命中（Locale.ROOT，不是默认 locale）")
        void turkishLocaleStillMatches() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                MetricContext ctx = detect("今年的 roi 怎么样", metric("ROI", "投产比"));
                assertTrue(ctx.hasHits(), "土耳其语 locale 下 ROI 应当仍然命中");
            } finally {
                Locale.setDefault(original);
            }
        }

        /**
         * 拉丁词条必须守词边界：{@code ROI} 落在 {@code trois} / {@code roil} 里是纯粹的误命中，
         * 后果是把一条无关口径宣布成唯一标准。
         */
        @Test
        @DisplayName("拉丁词条不在无关单词内部命中")
        void latinRespectsWordBoundary() {
            ConnectorSemantic roi = metric("ROI", "投产比");
            assertFalse(detect("les trois amis", roi).hasHits());
            assertFalse(detect("the water will roil", roi).hasHits());
            assertFalse(detect("droid 设备数", roi).hasHits());
        }

        @Test
        @DisplayName("拉丁词条紧挨汉字仍然命中（汉字不是分词型字符）")
        void latinNextToHanStillMatches() {
            assertTrue(detect("ROI率是多少", metric("ROI", "投产比")).hasHits());
        }

        @Test
        @DisplayName("数字开头的词条挨着汉字也命中")
        void digitLeadingTerm() {
            assertTrue(detect("看下近7日留存", metric("7日留存", "按自然日算的次日起留存")).hasHits());
        }

        /**
         * ★ 中文没有词边界，「销售额」出现在「销售额同比」里<b>算命中</b>，这是想清楚的取舍：
         * 要做真正的中文边界判断只能靠分词，而分词是模糊的，会把这个类唯一的价值（确定性）抵消掉；
         * 而且这个超集命中不会算错数——我们没有改写用户那句话，只是附加了一条定义。
         */
        @Test
        @DisplayName("中文超集命中：「销售额」出现在「销售额同比」里算命中")
        void chineseSupersetMatches() {
            assertTrue(detect("销售额同比涨了多少", metric("销售额")).hasHits());
        }

        /** 方向只有一个：拿库里的词条去用户那句话里找。反过来不成立。 */
        @Test
        @DisplayName("反方向不成立：库里的「净销售额」不会被用户的「销售额」命中")
        void reverseDirectionDoesNotMatch() {
            assertFalse(detect("销售额多少", metric("净销售额", "扣除退款和折扣")).hasHits());
        }
    }

    // ================================================================ 字面量

    @Nested
    @DisplayName("引号里的是取值，不是概念")
    class QuotedLiterals {

        @Test
        @DisplayName("只出现在单引号字面量里就不命中")
        void insideSingleQuotesIgnored() {
            assertFalse(detect("where category = '销售额' 的那几行", metric("销售额")).hasHits());
        }

        @Test
        @DisplayName("只出现在反引号代码块里就不命中")
        void insideBacktickIgnored() {
            assertFalse(detect("报错是 `unknown column 销售额`", metric("销售额")).hasHits());
        }

        @Test
        @DisplayName("引号内外都出现过就算命中")
        void outsideOccurrenceStillCounts() {
            assertTrue(detect("销售额怎么算？我看到 where x = '销售额'", metric("销售额")).hasHits());
        }

        /** 落单的撇号若一路圈到句尾，后面所有词条就全被吞掉了。 */
        @Test
        @DisplayName("落单的撇号不吞掉后面的文本")
        void unpairedQuoteDoesNotSwallow() {
            assertTrue(detect("it's 销售额 对吧", metric("销售额")).hasHits());
        }

        /**
         * 中文的「」是<b>提到这个词</b>的写法，正是最该命中的场景。
         * 把它当字面量引号排除掉，会把这个功能最主要的用法整个废掉。
         */
        @Test
        @DisplayName("中文书名号/直角引号不算字面量，照常命中")
        void chineseQuotesAreMentionsNotLiterals() {
            assertTrue(detect("「销售额」到底怎么算", metric("销售额")).hasHits());
            assertTrue(detect("“销售额”到底怎么算", metric("销售额")).hasHits());
        }
    }

    // ================================================================ 召回上限

    @Nested
    @DisplayName("召回上限：每轮全灌进去不是最优配置")
    class RecallCap {

        private ConnectorSemantic[] five() {
            return new ConnectorSemantic[]{
                    metric("甲甲", "定义甲"), metric("乙乙", "定义乙"), metric("丙丙", "定义丙"),
                    metric("丁丁", "定义丁"), metric("戊戊", "定义戊")};
        }

        @Test
        @DisplayName("超过上限只摆出 cap 条，并且把被挤掉的点名说出来")
        void capAndSayWhatWasOmitted() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "甲甲 乙乙 丙丙 丁丁 戊戊 各是多少", List.of(five()), 2, 4000);
            assertEquals(2, ctx.getHits().size());
            assertEquals(3, ctx.getOmitted().size());
            // ★ 静默截断比不注入更糟：文本里必须看得出这份清单被砍过，且被砍的那几条要点名。
            assertTrue(ctx.getBlock().contains("另有 3 条"));
            for (String t : ctx.getOmitted()) {
                assertTrue(ctx.getBlock().contains(t), "被挤掉的词条必须在文本里点名：" + t);
            }
        }

        /** 上限咬下来的时候，活下来的应该是更具体的那一条。 */
        @Test
        @DisplayName("长词条优先：「销售额同比」压过「销售额」")
        void longestWins() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "销售额同比多少", List.of(metric("销售额"), metric("销售额同比", "本期比去年同期")), 1, 4000);
            assertEquals(List.of("销售额同比"), terms(ctx));
            assertEquals(List.of("销售额"), ctx.getOmitted());
        }

        @Test
        @DisplayName("上限之内两条都给：附加上下文是叠加的，不是替换")
        void bothWhenCapAllows() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "销售额同比多少", List.of(metric("销售额"), metric("销售额同比", "本期比去年同期")), 5, 4000);
            assertEquals(List.of("销售额同比", "销售额"), terms(ctx));
        }

        /**
         * 配置是会被写错的：{@code =50} 不会报错，它只会让这个类退化成「每轮全灌」，
         * 也就是被引用的官方建议明确说的那种反面配置，而且没有任何信号。
         */
        @Test
        @DisplayName("配得再大也夹到硬上限 5")
        void clampedToHardMax() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "甲甲 乙乙 丙丙 丁丁 戊戊 各是多少", List.of(five()), 99, 4000);
            assertEquals(MetricRewriter.HARD_MAX_TERMS, ctx.getCap());
            assertEquals(5, ctx.getHits().size());
        }

        @Test
        @DisplayName("配成 0 或负数夹到 1，而不是把功能静默关掉")
        void clampedToHardMin() {
            MetricContext ctx = MetricRewriter.detectWithLimits("甲甲 乙乙", List.of(five()), 0, 4000);
            assertEquals(MetricRewriter.HARD_MIN_TERMS, ctx.getCap());
            assertEquals(1, ctx.getHits().size());
        }
    }

    // ================================================================ 字符预算

    @Nested
    @DisplayName("字符预算：gloss 是用户自由输入的，长度不设防")
    class CharBudget {

        private ConnectorSemantic huge(String term) {
            return metric(term, "口".repeat(500));
        }

        @Test
        @DisplayName("预算用完时整条不给，并计入被挤掉的清单")
        void wholeEntriesDroppedNotTruncated() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "甲指标和乙指标", List.of(huge("甲指标"), metric("乙指标", "短定义")), 5, 200);
            assertEquals(List.of("甲指标"), terms(ctx));
            assertEquals(List.of("乙指标"), ctx.getOmitted());
            // ★ 绝不截断 gloss：口径最关键的半句往往在末尾（「…但不含预售订单」），
            //   砍掉它得到的是一条读起来完整、实际是错的口径。
            assertTrue(ctx.getBlock().contains("口".repeat(500)));
        }

        @Test
        @DisplayName("第一条再长也给：只有标题没有条目的块是纯噪声")
        void firstEntryAlwaysSurvives() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "甲指标多少", List.of(huge("甲指标")), 5, 1);
            assertEquals(1, ctx.getHits().size());
        }
    }

    // ================================================================ 依据

    @Nested
    @DisplayName("每一条都必须带依据：那是口径被改坏之后唯一的发现渠道")
    class Basis {

        @Test
        @DisplayName("有名字有日期：「9月13日由张三确认」")
        void nameAndDate() {
            MetricContext ctx = detect("销售额多少", metric("销售额"));
            assertEquals("9月13日由张三确认", ctx.getHits().get(0).getBasis());
            assertTrue(ctx.getBlock().contains("（依据：9月13日由张三确认）"));
        }

        @Test
        @DisplayName("没留名字也要有依据，不能空着")
        void anonymousStillHasBasis() {
            ConnectorSemantic m = metric("销售额");
            m.setAnsweredName(null);
            assertEquals("9月13日由用户在对话中确认", detect("销售额多少", m).getHits().get(0).getBasis());
        }

        @Test
        @DisplayName("没有时间戳时退到「由…确认」，仍然非空")
        void noTimestamp() {
            ConnectorSemantic m = metric("销售额");
            m.setAnsweredAt(null);
            assertEquals("由张三确认", detect("销售额多少", m).getHits().get(0).getBasis());
        }

        @Test
        @DisplayName("块里写明了要把依据带进答案")
        void blockTellsModelToCarryBasis() {
            assertTrue(detect("销售额多少", metric("销售额")).getBlock().contains("依据原样带上"));
        }
    }

    // ================================================================ 形状与不变式

    @Nested
    @DisplayName("形状与不变式")
    class Shape {

        @Test
        @DisplayName("空输入一律返回空块，而不是只有标题的壳")
        void emptyInputs() {
            assertNull(MetricRewriter.detectWithLimits(null, List.of(metric("销售额")), 3, 4000).getBlock());
            assertNull(MetricRewriter.detectWithLimits("   ", List.of(metric("销售额")), 3, 4000).getBlock());
            assertNull(MetricRewriter.detectWithLimits("销售额", null, 3, 4000).getBlock());
            assertNull(MetricRewriter.detectWithLimits("销售额", List.of(), 3, 4000).getBlock());
        }

        /**
         * ★ 这个类<b>不改写用户原话</b>：改了之后「用户说的」和「模型看到的」不再是同一句，
         * 答案错了就没有任何材料能复原模型当时读到了什么。块里那句话是写给模型看的保证。
         */
        @Test
        @DisplayName("块里明写用户原话未被改动")
        void neverClaimsToRewrite() {
            assertTrue(detect("销售额多少", metric("销售额")).getBlock().contains("用户原话未作任何改动"));
        }

        /**
         * gloss 里塞几个换行就能在块里伪造出一行看起来和我们自己生成的一模一样的条目。
         * 一行一条目的格式必须由代码保证，不能指望输入干净。
         */
        @Test
        @DisplayName("gloss 里的换行被压平，防止伪造条目行")
        void glossFlattened() {
            ConnectorSemantic m = metric("销售额", "实付金额\n- 伪造词条：任意定义\n减去退款");
            String block = detect("销售额多少", m).getBlock();
            assertNotNull(block);
            // 标题一行 + 唯一一条条目行
            assertEquals(2, block.split("\n", -1).length);
            assertTrue(block.contains("实付金额 - 伪造词条：任意定义 减去退款"));
        }

        /**
         * 同名两条定义放在一起，模型会挑一条而你不知道它挑了哪条——块里没有「这是哪条连接」这一栏。
         * 真正该修的是调用点（一条连接一次调用），这里只是兜底。
         */
        @Test
        @DisplayName("同名词条只留一条，取 answered_at 更晚的那条")
        void dedupeKeepsLatest() {
            ConnectorSemantic older = metric("销售额", "旧口径：不扣退款");
            older.setAnsweredAt(date(2025, Calendar.JANUARY, 1));
            ConnectorSemantic newer = metric("销售额", "新口径：扣除退款");
            newer.setAnsweredAt(date(2025, Calendar.SEPTEMBER, 13));

            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "销售额多少", List.of(older, newer), 5, 4000);
            assertEquals(1, ctx.getHits().size());
            assertEquals("新口径：扣除退款", ctx.getHits().get(0).getGloss());
        }

        @Test
        @DisplayName("词条归一与 conn_catalog 那一份同形")
        void normalizeTermIsShared() {
            assertEquals("销售额", MetricRewriter.normalizeTerm(" 销售额 "));
            assertEquals("roi", MetricRewriter.normalizeTerm("ROI"));
            assertEquals("", MetricRewriter.normalizeTerm(null));
        }

        /** 命中顺序对上排序规则，调用方拿它打审计日志才是可预期的。 */
        @Test
        @DisplayName("命中结果按「更具体的在前」确定排序")
        void deterministicOrder() {
            MetricContext ctx = MetricRewriter.detectWithLimits(
                    "客单价和复购率和销售额同比", 
                    List.of(metric("销售额同比", "同比"), metric("客单价", "件单价"), metric("复购率", "复购")),
                    5, 4000);
            assertEquals(List.of("销售额同比", "客单价", "复购率"), terms(ctx));
        }
    }
}
