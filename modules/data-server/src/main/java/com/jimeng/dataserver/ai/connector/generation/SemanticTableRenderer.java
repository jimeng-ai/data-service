package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一张表的结构快照渲染成给语义层生成 agent 看的文本（设计文档 7.7；切片器按同一份文本计长度，6.7）。
 *
 * <p>C2 先落了唯一键那一行，C4 补齐整张表的 {@link #render}：一致性规则的退回文案要引用「B 的唯一键：PRIMARY(id)」，
 * 设计要求它和 table-metadata 端点给模型看的那一行<b>出自同一段代码</b>——两处各写一遍，模型读到的唯一键和被退回时
 * 听到的唯一键就可能是两句话。列摘要复用 {@link SemanticRowAssembler#renderObject}，切片器也复用本类计算文本长度。
 *
 * <h3>唯一键的三态必须分开</h3>
 * 与 {@link SemanticJoinValidator#uniqueKeysByObject} 同一约定：{@code detail_json.extra.unique_keys} <b>不在</b> = 不知道
 * （本功能上线前拉的旧快照、或那次读索引失败）；<b>空列表</b> = 这张表确实没有主键或唯一键；非空 = 那些键，主键在前。
 * 把「不知道」说成「没有」，一致性规则会把一条指向旧快照表主键的正确关系退回；反过来说，会放过一条指向无键表的 N:1。
 */
@Slf4j
@Component
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
     * 完整渲染一张快照表。列段委托 {@link SemanticRowAssembler#renderObject}，这里只在末尾补对象级唯一键。
     * 切片器和回调端点都必须调这一方法，避免两边按不同文本估算长度。
     */
    public String render(ConnectorSchema row) {
        Map<String, FieldDetail> fields = fieldsOf(row);
        return renderWithFields(row, fields);
    }

    /**
     * 把单张表收口到 {@code maxChars}。只有第一张表可以走这个分支；后续表若整张放不下应由调用方放进 deferred。
     * 截断只保留列的前缀，并把明确的 N/K 提示留在文本末尾，模型不会把没看到的列当作不存在。
     */
    public RenderedTable renderWithin(ConnectorSchema row, int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须大于 0");
        }
        Map<String, FieldDetail> fields = fieldsOf(row);
        String full = renderWithFields(row, fields);
        if (full.length() <= maxChars) {
            return new RenderedTable(full, false, fields.size(), fields.size());
        }
        if (fields.isEmpty()) {
            return new RenderedTable(hardClip(full, maxChars), false, 0, 0);
        }

        List<String> renderedFields = fields.values().stream()
                .map(SemanticRowAssembler::renderField)
                .toList();
        String header = SemanticRowAssembler.renderObjectHeader(row);
        String uniqueKeys = uniqueKeyLine(row) + "\n";
        int fixedLength = header.length() + uniqueKeys.length();
        int prefixLength = 0;
        int kept = -1;
        for (int i = 0; i < renderedFields.size(); i++) {
            String marker = truncationMarker(renderedFields.size(), i);
            if (fixedLength + prefixLength + marker.length() <= maxChars) {
                kept = i;
            }
            prefixLength += renderedFields.get(i).length();
        }
        // 循环只累计长度，不反复建 Map、拼大字符串或解析整份 detail_json；最后只组装一次命中的前缀。
        if (kept >= 0) {
            StringBuilder candidate = new StringBuilder(maxChars);
            candidate.append(header);
            for (int i = 0; i < kept; i++) {
                candidate.append(renderedFields.get(i));
            }
            candidate.append(uniqueKeys).append(truncationMarker(renderedFields.size(), kept));
            return new RenderedTable(candidate.toString(), true, renderedFields.size(), kept);
        }

        // 极端坏快照（例如表注释或唯一键行自身就超过 16k）：仍严格守住协议上限，并让截断提示完整留在末尾。
        String marker = "\n" + truncationMarker(renderedFields.size(), 0);
        String prefix = renderWithFields(row, Map.of(), false);
        return new RenderedTable(fitWithSuffix(prefix, marker, maxChars), true, renderedFields.size(), 0);
    }

    /** detail_json 是否明确带了 extra.unique_keys；空列表也算「已知」。 */
    public static boolean uniqueKeysKnown(ConnectorSchema row) {
        return namedUniqueKeys(row) != null;
    }

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
                out.add(new NamedKey(display, cols.stream().map(o -> String.valueOf((Object) o)).toList(), primary));
            }
            // 写入方通常已经主键在前，但协议不能把正确性寄托在 JSON 数组顺序上；稳定排序保留同类键的原顺序。
            out.sort((a, b) -> Boolean.compare(!a.primary(), !b.primary()));
            return out;
        } catch (Exception e) {
            // 与 uniqueKeysByObject 同一处置：这张表的快照 JSON 坏了，就是「不知道唯一键」，不影响别的表。
            log.debug("解析快照里的唯一键失败 objectName={}", row.getObjectName(), e);
            return null;
        }
    }

    private static Map<String, FieldDetail> fieldsOf(ConnectorSchema row) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Map<String, FieldDetail>> parsed = SemanticRowAssembler.parseFields(List.of(row));
        return parsed.getOrDefault(row.getObjectName(), Map.of());
    }

    private static String renderWithFields(ConnectorSchema row, Map<String, FieldDetail> fields) {
        return renderWithFields(row, fields, true);
    }

    /**
     * {@code actualFields=false} 只用于「真实表有列但预算连一列也放不下」：删掉 renderObject 对空 map 写的
     * 「字段未取到」，因为这里不是没取到，而是明确截断为 0 列。
     */
    private static String renderWithFields(ConnectorSchema row, Map<String, FieldDetail> fields, boolean actualFields) {
        String base = SemanticRowAssembler.renderObject(row, fields == null ? Map.of() : fields);
        if (!actualFields && (fields == null || fields.isEmpty())) {
            base = base.replace("(本表字段未取到，不要为它写字段或关系)\n", "");
        }
        // renderObject 以空行收尾；唯一键属于同一张表摘要，插在那个空行之前。
        if (base.endsWith("\n")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + uniqueKeyLine(row) + "\n";
    }

    private static String truncationMarker(int total, int kept) {
        return "本表共 " + total + " 列，只列出前 " + kept + " 列；未列出的列不要写说明\n";
    }

    private static String hardClip(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars == 1) {
            return "…";
        }
        return text.substring(0, maxChars - 1) + "…";
    }

    private static String fitWithSuffix(String prefix, String suffix, int maxChars) {
        if (suffix.length() >= maxChars) {
            return hardClip(suffix, maxChars);
        }
        int prefixBudget = maxChars - suffix.length();
        String fitted = prefix.length() <= prefixBudget ? prefix : hardClip(prefix, prefixBudget);
        return fitted + suffix;
    }

    /** 内部值对象，不直接跨 HTTP；DTO 由回调服务组装。 */
    public record RenderedTable(String text, boolean columnsTruncated, int totalColumns, int renderedColumns) {
    }

    private record NamedKey(String name, List<String> columns, boolean primary) {
    }
}
