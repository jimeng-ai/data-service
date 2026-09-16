package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.persistence.entity.ConnectorSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一张表的结构快照渲染成给语义层生成 agent 看的文本（设计文档 7.7；切片器按同一份文本计长度，6.7）。
 *
 * <p>本类目前只落下<b>唯一键那一行</b>（13.4 的 C2 先用到）：一致性规则的退回文案要引用「B 的唯一键：PRIMARY(id)」，
 * 设计要求它和 table-metadata 端点给模型看的那一行<b>出自同一段代码</b>——两处各写一遍，模型读到的唯一键和被退回时
 * 听到的唯一键就可能是两句话。整张表的 {@code render}（复用 {@code SemanticRowAssembler.renderObject} 再补这一行）在 C4 加进来。
 *
 * <h3>唯一键的三态必须分开</h3>
 * 与 {@link SemanticJoinValidator#uniqueKeysByObject} 同一约定：{@code detail_json.extra.unique_keys} <b>不在</b> = 不知道
 * （本功能上线前拉的旧快照、或那次读索引失败）；<b>空列表</b> = 这张表确实没有主键或唯一键；非空 = 那些键，主键在前。
 * 把「不知道」说成「没有」，一致性规则会把一条指向旧快照表主键的正确关系退回；反过来说，会放过一条指向无键表的 N:1。
 */
@Slf4j
public class SemanticTableRenderer {

    /** 键不在：旧快照没读到索引。 */
    public static final String UNIQUE_KEYS_UNKNOWN = "唯一键未知（旧快照，未读到索引）";

    /** 键在、但是空列表。 */
    public static final String UNIQUE_KEYS_NONE = "本表没有主键或唯一键";

    /** 有键时那一行的前缀，后面接 {@link #uniqueKeyText}。 */
    public static final String UNIQUE_KEYS_PREFIX = "唯一键（主键在前）：";

    /** 多个键之间用全角分号：键内的列用半角逗号分隔，两级分隔符不能是同一个字符。 */
    private static final String KEY_SEP = "；";

    /**
     * 一张表唯一键的文本，三态之一：{@code PRIMARY(id)；uk_tenant_code(tenant_id, code)} / {@link #UNIQUE_KEYS_NONE} /
     * {@link #UNIQUE_KEYS_UNKNOWN}。一致性规则的文案里原样引用它（{@code RuleContext.uniqueKeyTextByObject}）。
     */
    public static String uniqueKeyText(ConnectorSchema row) {
        List<NamedKey> keys = namedUniqueKeys(row);
        if (keys == null) {
            return UNIQUE_KEYS_UNKNOWN;
        }
        if (keys.isEmpty()) {
            return UNIQUE_KEYS_NONE;
        }
        List<String> parts = new ArrayList<>(keys.size());
        for (NamedKey k : keys) {
            parts.add(k.name() + "(" + String.join(", ", k.columns()) + ")");
        }
        return String.join(KEY_SEP, parts);
    }

    /** 给模型看的那一整行：有键时带 {@link #UNIQUE_KEYS_PREFIX}，另外两态就是那句话本身。 */
    public static String uniqueKeyLine(ConnectorSchema row) {
        List<NamedKey> keys = namedUniqueKeys(row);
        String text = uniqueKeyText(row);
        return keys == null || keys.isEmpty() ? text : UNIQUE_KEYS_PREFIX + text;
    }

    /** 表名 → {@link #uniqueKeyText}。快照里的每一张表都有一项（不知道也是一项），保持快照顺序。 */
    public static Map<String, String> uniqueKeyTextByObject(List<ConnectorSchema> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (ConnectorSchema r : rows) {
            if (r != null && r.getObjectName() != null) {
                out.put(r.getObjectName(), uniqueKeyText(r));
            }
        }
        return out;
    }

    /**
     * 带键名的唯一键。{@link SemanticJoinValidator#uniqueKeysByObject} 只给列清单、不给键名，文案要键名，所以这里自己读——
     * 但<b>跳过规则与它逐条一致</b>（没有 columns、columns 为空或含 null 的键整条跳过），否则同一张表在规则判定里「没有唯一键」、
     * 在文案里却列出一个键。
     *
     * @return {@code null} = 不知道
     */
    @SuppressWarnings("unchecked")
    private static List<NamedKey> namedUniqueKeys(ConnectorSchema row) {
        if (row == null || row.getDetailJson() == null || row.getDetailJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
            if (!(m.get("extra") instanceof Map<?, ?> extra)
                    || !(extra.get(SemanticJoinValidator.DETAIL_UNIQUE_KEYS) instanceof List<?> keys)) {
                return null;
            }
            List<NamedKey> out = new ArrayList<>(keys.size());
            for (Object k : keys) {
                if (!(k instanceof Map<?, ?> km) || !(km.get("columns") instanceof List<?> cols)
                        || cols.isEmpty() || cols.stream().anyMatch(Objects::isNull)) {
                    continue;
                }
                boolean primary = Boolean.TRUE.equals(km.get("primary"));
                Object name = km.get("name");
                String display = name != null && !String.valueOf(name).isBlank() ? String.valueOf(name)
                        : (primary ? "PRIMARY" : "（未命名唯一键）");
                out.add(new NamedKey(display, cols.stream().map(o -> String.valueOf((Object) o)).toList()));
            }
            return out;
        } catch (Exception e) {
            // 与 uniqueKeysByObject 同一处置：这张表的快照 JSON 坏了，就是「不知道唯一键」，不影响别的表。
            log.debug("解析快照里的唯一键失败 objectName={}", row.getObjectName(), e);
            return null;
        }
    }

    private record NamedKey(String name, List<String> columns) {
    }
}
