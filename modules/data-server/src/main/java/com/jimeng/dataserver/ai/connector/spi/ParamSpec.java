package com.jimeng.dataserver.ai.connector.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一种连接器类型需要哪些参数。由连接器实现类声明，框架据此校验录入、前端据此渲染表单。
 *
 * <p>本类<b>只负责判定，不负责抛异常</b>——{@link #validate} 返回违规清单，由调用方决定怎么报。
 * 这样它可以脱离 Spring 与 Web 层被单测覆盖。
 */
public record ParamSpec(List<ParamField> fields) {

    public ParamSpec {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static ParamSpec of(ParamField... fs) {
        return new ParamSpec(List.of(fs));
    }

    public ParamField field(String name) {
        for (ParamField f : fields) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }

    /** 敏感字段名（这些不进 config_json，只进凭据密文）。 */
    public List<String> secretNames() {
        List<String> out = new ArrayList<>();
        for (ParamField f : fields) {
            if (f.secret()) out.add(f.name());
        }
        return out;
    }

    /**
     * 校验一份录入值。
     *
     * @param values 录入的原始值（含敏感字段）
     * @return 违规说明；空列表表示通过
     */
    public List<String> validate(Map<String, Object> values) {
        Map<String, Object> v = values == null ? Map.of() : values;
        List<String> errs = new ArrayList<>();
        for (ParamField f : fields) {
            Object raw = v.get(f.name());
            boolean blank = raw == null || (raw instanceof String s && s.isBlank())
                    || (raw instanceof List<?> l && l.isEmpty());
            if (blank) {
                if (f.required() && f.defaultValue() == null) {
                    errs.add(f.label() + "（" + f.name() + "）不能为空");
                }
                continue;
            }
            switch (f.type()) {
                case INT -> {
                    Integer n = asInt(raw);
                    if (n == null) {
                        errs.add(f.label() + "（" + f.name() + "）必须是整数");
                    } else {
                        if (f.min() != null && n < f.min()) errs.add(f.label() + " 不能小于 " + f.min());
                        if (f.max() != null && n > f.max()) errs.add(f.label() + " 不能大于 " + f.max());
                    }
                }
                case BOOL -> {
                    if (!(raw instanceof Boolean) && !"true".equalsIgnoreCase(String.valueOf(raw))
                            && !"false".equalsIgnoreCase(String.valueOf(raw))) {
                        errs.add(f.label() + "（" + f.name() + "）必须是 true / false");
                    }
                }
                case ENUM -> {
                    List<String> opts = f.options() == null ? List.of() : f.options();
                    if (!opts.contains(String.valueOf(raw))) {
                        errs.add(f.label() + "（" + f.name() + "）只能是 " + String.join(" / ", opts));
                    }
                }
                case STRING_LIST -> {
                    if (!(raw instanceof List<?>)) {
                        errs.add(f.label() + "（" + f.name() + "）必须是数组");
                    }
                }
                default -> {
                    String s = String.valueOf(raw);
                    if (f.pattern() != null && !s.matches(f.pattern())) {
                        errs.add(f.label() + "（" + f.name() + "）格式不合法，需匹配 " + f.pattern());
                    }
                }
            }
        }
        // 未声明的参数一律拒绝：悄悄忽略一个拼错的字段名，等于让客户以为自己配上了。
        for (String k : v.keySet()) {
            if (field(k) == null) {
                errs.add("未知参数 " + k + "（该连接器类型不接受这个参数）");
            }
        }
        return errs;
    }

    /**
     * 按定义把录入值归一：填默认值、类型转换、去掉敏感字段。
     * 返回的 Map 就是要写进 {@code config_json} 的内容。
     */
    public Map<String, Object> normalizeNonSecret(Map<String, Object> values) {
        Map<String, Object> v = values == null ? Map.of() : values;
        Map<String, Object> out = new LinkedHashMap<>();
        for (ParamField f : fields) {
            if (f.secret()) continue;
            Object raw = v.get(f.name());
            boolean blank = raw == null || (raw instanceof String s && s.isBlank());
            if (blank) {
                if (f.defaultValue() != null) out.put(f.name(), coerce(f, f.defaultValue()));
                continue;
            }
            out.put(f.name(), coerce(f, raw));
        }
        return out;
    }

    private static Object coerce(ParamField f, Object raw) {
        return switch (f.type()) {
            case INT -> asInt(raw);
            case BOOL -> raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
            case STRING_LIST -> raw instanceof List<?> l ? l : List.of(String.valueOf(raw));
            default -> String.valueOf(raw);
        };
    }

    private static Integer asInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            // 注意：本项目全局 write_numbers_as_strings=true，前端回传的数字很可能是字符串。
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 供前端表单渲染的可序列化形状。 */
    public List<Map<String, Object>> toFormSchema() {
        List<Map<String, Object>> out = new ArrayList<>(fields.size());
        for (ParamField f : fields) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("label", f.label());
            m.put("type", f.type().name().toLowerCase(Locale.ROOT));
            m.put("required", f.required());
            m.put("secret", f.secret());
            if (f.defaultValue() != null) m.put("default", f.defaultValue());
            if (f.placeholder() != null) m.put("placeholder", f.placeholder());
            if (f.help() != null) m.put("help", f.help());
            if (f.options() != null) m.put("options", f.options());
            if (f.pattern() != null) m.put("pattern", f.pattern());
            if (f.min() != null) m.put("min", f.min());
            if (f.max() != null) m.put("max", f.max());
            m.put("group", f.group() == null ? "基本" : f.group());
            out.add(m);
        }
        return out;
    }
}
