package com.jimeng.dataserver.ai.connector.service;

import java.util.function.Predicate;

/**
 * 说明书<b>缺在哪</b>。每个常量 = 一种「整块内容没进说明书」的成因，自带判据。
 *
 * <p>判据写在常量上而不是散在两条写入路径里：那两条路径（单次推导 / agent 分片生成）
 * 产出的事实不完全一样（分片才有「放弃的表」，单次推导才有「模型输出被截断」），
 * 但<b>「算不算残缺」必须是同一把尺子</b>。加一种新的残缺成因 = 在这里加一个常量，
 * {@link SemanticCoverage#assess} 不用改，两条路径也不用改——漏判在结构上不可能发生。
 *
 * <p>常量名就是落库的原因码（{@code connection.semantic_gaps}），所以<b>改名字等于改数据</b>：
 * 已经落库的行不会跟着改。要退掉一种成因，宁可留着常量并把判据改成恒假。
 */
public enum SemanticGap {

    /**
     * 该覆盖的表没覆盖全。最常见、也最容易被读成「没事」的一种——
     * 「14 张表本次只覆盖 9 张」，剩下 5 张对模型来说等于不存在。
     */
    TABLES_MISSING("有表没进说明书",
            "本次该覆盖的表没覆盖全，没覆盖到的那些表，模型完全看不到。",
            f -> f.total() > 0 && f.done() < f.total()),

    /**
     * 有表反复失败后被放弃。它和上一条的区别在于<b>已经试过了</b>：
     * 重跑一次多半还是这个结果，要先去看那几张表本身出了什么问题。
     */
    TABLES_GAVE_UP("有表反复失败后被放弃",
            "有表试过几次都没生成出来，已被放弃。重跑大概率还是同样结果，要先看那几张表本身。",
            f -> f.gaveUp() > 0),

    /**
     * 结构快照<b>自己</b>就被截断了（200 个对象上限）。
     * 这一条的缺口在语义层<b>之前</b>：那些表根本没进过 {@code connector_schema}，
     * 所以重新生成语义层不会把它们补回来，得先解决快照那一层。
     */
    SNAPSHOT_TRUNCATED("结构快照本身就不全",
            "客户库的对象数超过了结构快照的上限，超出的表没有进过快照，因此也不会有语义。"
                    + "只重新生成语义层补不回来。",
            SemanticCoverage.Facts::snapshotTruncated),

    /**
     * 模型输出被 {@code max_tokens} 截断，已截到最后一个完整条目。
     * 截断修复是刻意保留的（一次推导几十秒，为最后半条丢整份太亏），但<b>修复必须出声</b>：
     * 把残缺当完整正是这套设计从头到尾在防的事。
     */
    MODEL_OUTPUT_TRUNCATED("模型输出被截断",
            "模型这次的输出超了长度上限，尾部被截掉，已保留到最后一个完整条目。"
                    + "可调大 connector.semantic.max-tokens 后重新生成。",
            SemanticCoverage.Facts::modelOutputTruncated);

    private final String label;
    private final String consequence;
    private final Predicate<SemanticCoverage.Facts> predicate;

    SemanticGap(String label, String consequence, Predicate<SemanticCoverage.Facts> predicate) {
        this.label = label;
        this.consequence = consequence;
        this.predicate = predicate;
    }

    /** 给界面直接展示的中文短名。 */
    public String label() {
        return label;
    }

    /** 这条缺口意味着什么。说后果，不复述现象。 */
    public String consequence() {
        return consequence;
    }

    boolean presentIn(SemanticCoverage.Facts facts) {
        return predicate.test(facts);
    }
}
