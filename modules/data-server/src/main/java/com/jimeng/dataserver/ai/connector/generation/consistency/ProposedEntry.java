package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * agent 提交里的一个条目，交给一致性规则判定。纯内部形状，不出 HTTP（jackson 2.11.1 序列化不了 record）。
 *
 * @param kind     {@code SemanticRowAssembler.KIND_OBJECTS} 等
 * @param index    在提交的原数组里的下标，与 {@code EntryOutcome#index()} 同一口径（非对象元素占位）
 * @param raw      条目原样（已经过提交流水线的 C 步：fields / joins 补了 {@code object}）
 * @param evidence 归一后的依据标签，缺省口径与 toRows 同源（{@link SemanticRowAssembler#evidenceOf}）；ambiguities 为 {@code null}
 */
public record ProposedEntry(String kind, int index, Map<String, Object> raw, String evidence) {

    public static ProposedEntry of(String kind, int index, Map<String, Object> raw) {
        return new ProposedEntry(kind, index, raw, SemanticRowAssembler.evidenceOf(kind, raw));
    }

    /**
     * 把一份 toRows 形状的输入（objects / fields / joins / ambiguities 四个数组）摊成条目，四个种类按这个顺序、
     * 各自按数组下标。非对象元素跳过但占位，理由见 {@code SemanticRowAssembler.toRows} 的「逐条结果里的下标」。
     */
    @SuppressWarnings("unchecked")
    public static List<ProposedEntry> listOf(Map<String, Object> parsed) {
        List<ProposedEntry> out = new ArrayList<>();
        if (parsed == null) {
            return out;
        }
        for (String kind : List.of(SemanticRowAssembler.KIND_OBJECTS, SemanticRowAssembler.KIND_FIELDS,
                SemanticRowAssembler.KIND_JOINS, SemanticRowAssembler.KIND_AMBIGUITIES)) {
            if (!(parsed.get(kind) instanceof List<?> list)) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Map<?, ?> m) {
                    out.add(of(kind, i, (Map<String, Object>) m));
                }
            }
        }
        return out;
    }

    /** trim 后的字符串值，空串算没有。与 toRows 读条目的口径一致。 */
    public String str(String key) {
        return SemanticRowAssembler.str(raw, key);
    }

    /** 表 / 表.列 / 左表.左列 / 口径词，与逐条结果的 key 一致。 */
    public String key() {
        return SemanticRowAssembler.entryKey(kind, raw);
    }

    /** {@code joins[1]}。 */
    public String path() {
        return kind + "[" + index + "]";
    }

    /** 归一后是 GUESS：toRows 会以 EVIDENCE_GUESS 丢掉，规则不必再判。 */
    public boolean guess() {
        return ConnectorSemanticService.EV_GUESS.equals(evidence);
    }

    /**
     * 关系声明的基数，按 toRows 的口径归一：转大写，只认 {@code 1:1 / 1:N / N:1 / N:N}，其余（含没写）为 {@code null}。
     * 规则必须用这一份——toRows 会把认不出的基数落成 null，规则若按原文判，就会去审一个根本不会入库的声明。
     */
    public String cardinality() {
        String c = str("cardinality");
        if (c == null) {
            return null;
        }
        String up = c.toUpperCase(Locale.ROOT);
        return SemanticRowAssembler.CARDINALITIES.contains(up) ? up : null;
    }
}
