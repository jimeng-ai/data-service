package com.jimeng.dataserver.ai.connector.service;

import java.util.Locale;
import java.util.Optional;

/**
 * 表形态。照抄 Quick BI「数据集类型」的四个枚举：明细表 / 多指标周期表 / 键值对表 / 其他。
 *
 * <h3>为什么它必须是枚举，而不是模型随手写的一句话</h3>
 * 一个值直接决定模型<b>怎么聚合</b>。从前 {@code table_shape} 是自由文本（「主表」「流水表」「事实表」），
 * 读它的模型没法据此做任何确定的事；而且词表里压根没有「键值对表」——恰恰是最要命的那一种：
 * 键值对表一行是「某个指标的一个值」，指标名在一列、值在另一列，把它当明细表去 {@code SUM(value)}，
 * 就是把 GMV 和转化率加在一起。<b>所有聚合都是错的，而且不报错。</b>
 *
 * <h3>JSON 键名在这里集中登记</h3>
 * 写入方（推导、测量）和读出方（注入）必须是同一个字符串，分叉的表现是「写了但模型看不到」。
 */
public enum TableShape {

    /** 明细表：一行 = 一条业务记录 / 一次事件，SUM / COUNT 可以直接用。 */
    DETAIL("明细表"),

    /** 多指标周期表：一行 = 一个周期（可带维度），多列各是一个指标。 */
    MULTI_METRIC_PERIOD("多指标周期表"),

    /** 键值对表：一行 = 一个指标的一个值。聚合前必须先按指标名列过滤到单个指标。 */
    KEY_VALUE("键值对表"),

    /** 其他：维度表、配置表、关系表、日志等，没有统一的聚合规则。 */
    OTHER("其他");

    // ── OBJECT 行 detail_json 的键 ──
    public static final String KEY_SHAPE = "table_shape";
    public static final String KEY_SOURCE = "table_shape_source";
    /** 实测推翻了模型时，模型原来的判断留在这里。<b>分歧本身是信号</b>，丢了就没了。 */
    public static final String KEY_MODEL_GUESS = "table_shape_model_guess";
    public static final String KEY_KV_NAME_COLUMN = "kv_name_column";
    public static final String KEY_KV_VALUE_COLUMN = "kv_value_column";
    /**
     * 测量留痕（结论、用的哪两列、依据的数字）。不在共享契约里，是追加的键：
     * 它回答「凭什么说是 / 不是键值对表」，也是「这张表已经测过、下一轮不必再打客户库」的记号。
     */
    public static final String KEY_MEASUREMENT = "table_shape_measurement";

    // ── table_shape_source ──
    public static final String SOURCE_MODEL = "MODEL";
    public static final String SOURCE_MEASURED = "MEASURED";

    private final String label;

    TableShape(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 宽松解析模型产出：枚举名（忽略大小写、空白、连字符）或中文标签。
     *
     * <p>认不出来返回 empty，<b>不落成 {@link #OTHER}</b>：「模型说是其他」和「模型说了一句我们听不懂的话」
     * 是两件事，把后者写成前者等于替模型编了一个判断。
     */
    public static Optional<TableShape> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        String norm = s.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TableShape t : values()) {
            if (t.name().equals(norm) || t.label.equals(s)) {
                return Optional.of(t);
            }
        }
        return switch (s) {
            case "明细", "明细型" -> Optional.of(DETAIL);
            case "键值对", "键值表", "KV", "kv" -> Optional.of(KEY_VALUE);
            case "多指标周期", "周期表" -> Optional.of(MULTI_METRIC_PERIOD);
            default -> Optional.empty();
        };
    }
}
