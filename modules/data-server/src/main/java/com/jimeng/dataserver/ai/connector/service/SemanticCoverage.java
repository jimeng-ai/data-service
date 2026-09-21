package com.jimeng.dataserver.ai.connector.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次语义层生成<b>覆盖全了没有</b>，以及没覆盖全的时候缺在哪。
 *
 * <h3>为什么要有这个类（它解决的不是「没显示」）</h3>
 * 残缺这件事一直都被如实记下来了，只是记在 {@code connection.semantic_note} 那一段中文散文里——
 * 「模型输出疑似被 max_tokens 截断，已截到最后一个完整条目，说明书不完整」，后面还接着上一轮的结论。
 * 那句话<b>没有形状</b>：它不显眼，更要命的是<b>查不出来</b>——
 * 「哪些连接的说明书是残缺的」这个问题，在散文上只能靠 LIKE 一段可能随时改掉的文案去猜。
 *
 * <p>所以这里<b>不替换那段散文</b>（前端和运维都在读它），只在它旁边多落一个结构化信号：
 * 一个可以进 WHERE 的枚举，加一串原因码。散文回答「这次发生了什么」，这两列回答
 * 「这份说明书现在能不能当全本用」。
 *
 * <h3>为什么这件事值得一列</h3>
 * 说明书残缺的失败方式和这套设计从头到尾在防的那一类一模一样：<b>模型看不见那些表和字段，
 * 它不会报错，只会答得不对</b>。少一张订单明细表，模型照样给出一个看起来很正常的数字。
 * 没有任何一层会为此报警——除非「不完整」本身是一个可以被人和 SQL 问到的事实。
 *
 * <h3>三态，NULL 是其中一态</h3>
 * {@code null}（未生成过 / 存量行）、{@link #COMPLETE}、{@link #PARTIAL}。
 * <b>「没跑过」和「残缺」必须分得开</b>：把存量连接一律显示成残缺，等于上线当天把所有连接
 * 标成红的，然后所有人一起学会忽略这个标记——那比没有这个标记更糟。
 */
public enum SemanticCoverage {

    /** 本次生成把该覆盖的都覆盖了。它<b>不保证内容对</b>，只保证没有整块缺失。 */
    COMPLETE("完整"),

    /** 本次生成有整块内容缺失，缺在哪见 {@link SemanticGap}。 */
    PARTIAL("不完整");

    private final String label;

    SemanticCoverage(String label) {
        this.label = label;
    }

    /** 给界面直接展示的中文短名。 */
    public String label() {
        return label;
    }

    /**
     * 判残缺所需的全部事实，由两条写入路径各自填。
     *
     * <p>刻意是一个<b>扁平的事实包</b>而不是各路径自己写 if：判据必须只有一份。
     * 两条路径各判各的，迟早会出现「agent 那条算残缺、单次推导那条算完整」的分叉，
     * 而分叉之后这一列就不能再用来做那条 SQL 查询了。
     *
     * @param totalTables          本该覆盖多少张表（含快照自己没覆盖到的那些）；null/0 = 数不出来
     * @param doneTables           实际覆盖了多少张
     * @param gaveUpTables         反复失败后被放弃的表数（agent 分片生成才有）
     * @param snapshotTruncated    结构快照<b>自己</b>就被截断了（200 个对象上限）
     * @param modelOutputTruncated 模型输出被 max_tokens 截断，已截到最后一个完整条目
     */
    public record Facts(Integer totalTables, Integer doneTables, Integer gaveUpTables,
                        boolean snapshotTruncated, boolean modelOutputTruncated) {

        public int total() {
            return nonNegative(totalTables);
        }

        public int done() {
            return nonNegative(doneTables);
        }

        public int gaveUp() {
            return nonNegative(gaveUpTables);
        }

        private static int nonNegative(Integer value) {
            return value == null ? 0 : Math.max(0, value);
        }
    }

    /**
     * 判定结果：落库的就是这两个值。
     *
     * @param coverage 进 {@code connection.semantic_coverage}
     * @param gaps     进 {@code connection.semantic_gaps}，完整时是空列表
     */
    public record Verdict(SemanticCoverage coverage, List<SemanticGap> gaps) {

        public Verdict {
            gaps = List.copyOf(gaps);
        }

        public boolean partial() {
            return coverage == PARTIAL;
        }

        /** 落库的状态值。 */
        public String code() {
            return coverage.name();
        }

        /**
         * 落库的原因码串（逗号分隔）。
         *
         * <p><b>完整时返回空串而不是 null</b>：这两列跟着 semantic_note 用同一个实体更新落库，
         * 而 MyBatis-Plus 的 NOT_NULL 策略<b>不会把字段写成 null</b>。返回 null 的后果是
         * 上一次残缺留下的原因码<b>留在行上</b>，配着新写进去的 COMPLETE——
         * 那是一行自相矛盾的记录，而且矛盾的方向是「说自己完整，却列着缺口」。
         */
        public String gapCodes() {
            StringBuilder codes = new StringBuilder();
            for (SemanticGap gap : gaps) {
                if (codes.length() > 0) {
                    codes.append(',');
                }
                codes.append(gap.name());
            }
            return codes.toString();
        }
    }

    /**
     * 按事实判一次。<b>命中任何一条缺口即 PARTIAL</b>——缺口之间不做权重、不做「小缺口忽略」：
     * 「少了 1 张表」和「少了 40 张表」对模型是同一件事，它都不知道自己少看了东西。
     *
     * <p>判据挂在 {@link SemanticGap} 的常量上，这里只做遍历：新增一种残缺=加一个枚举常量，
     * 不改本方法，也就不可能出现「加了新缺口但某条路径忘了判」的分叉。
     */
    public static Verdict assess(Facts facts) {
        List<SemanticGap> hit = new ArrayList<>();
        for (SemanticGap gap : SemanticGap.values()) {
            if (gap.presentIn(facts)) {
                hit.add(gap);
            }
        }
        return new Verdict(hit.isEmpty() ? COMPLETE : PARTIAL, hit);
    }
}
