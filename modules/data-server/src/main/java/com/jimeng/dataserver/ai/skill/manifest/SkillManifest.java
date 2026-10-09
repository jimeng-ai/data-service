package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 存进 {@code ai_skill.manifest} / {@code ai_skill_version.manifest} 的解析结果（设计文档 §3.6）：
 * {@code metadata.jm} 的字段，加上 key 的声明（名字和发送范围，<b>不含值</b>）。运行时只读它，不再重新解析 SKILL.md。
 *
 * <p>key 能不能发往某处，以 {@code skill_secret} 行上的范围为准（§3.4 第 2 条）；这里的 {@link #keys} 只给人和模型看。
 *
 * <p>JSON 用自己的 ObjectMapper：Spring 的那个全局开了 write_numbers_as_strings，会把 timeoutSeconds 写成字符串。
 *
 * <p>★ data-server 的 Jackson 是 2.11.1（redisson 的 BOM 钉住的），不认 record：每个组件都写明 {@code @JsonProperty}，
 * 规范构造器标 {@code @JsonCreator}。漏了，写出是空对象或抛「no properties discovered」，读回抛「no Creators」。
 * {@code SkillManifestTest} 用真实 ObjectMapper 往返兜着。
 *
 * <h3>读取口 {@link #fromJson} 是失败即关闭的</h3>
 * 这是授权相关的数据（谁能访问哪些域名），读不出来就当作没有声明（{@link #EMPTY}），不会读一半。读不出来包括：
 * JSON 语法错误；<b>出现了未知字段</b>（Jackson 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES}，有意不放开）；
 * requires 认不出来；binding 不是 always / optional。每一种都记一条 ERROR，带原文的前 200 个字符。
 *
 * <p>★ 以后给 manifest 加字段、或给 requires / binding 加新取值，都要想到这一点：部署回滚到旧版本时，新代码写进去的行
 * 在旧代码里会读成 EMPTY，这些 skill 的外部接口声明（hosts、keys 一起）在回滚期间<b>全部失效</b>。
 * 而且不一定只是「暂时」：回滚期间有人对没有附件包的 skill（单文件上传、导入）点「用 AI 改进」再发布，
 * {@code seedFromPublished} 会按 EMPTY 重建 SKILL.md，发布时把 EMPTY 写回库，声明就永久丢了（有附件包的原样复制 SKILL.md，不受影响）。
 * 所以要分两步发：先发一个认得新字段 / 新取值（只读不写）的版本，再发开始写它的版本。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillManifest(@JsonProperty("hosts") List<String> hosts,
                            @JsonProperty("web") Boolean web,
                            @JsonProperty("timeoutSeconds") Integer timeoutSeconds,
                            @JsonProperty("requires") String requires,
                            @JsonProperty("binding") String binding,
                            @JsonProperty("keys") List<KeyDecl> keys) {

    private static final Logger log = LoggerFactory.getLogger(SkillManifest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 读取失败时，ERROR 日志里带的原文长度（字符数）。 */
    private static final int LOG_CHARS = 200;

    /**
     * key 的声明。hosts 没有就是空列表（不会是 null）；methods / paths 为 null = 不限，非 null 时拷贝一份。
     * 和 {@link SkillManifest}、{@link JmMetadata} 一样，构造时把列表拷贝成不可变的：manifest 会被缓存、放进运行登记，
     * 传进来的列表之后再被谁改了，不能连带改掉这里。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeyDecl(@JsonProperty("name") String name,
                          @JsonProperty("hosts") List<String> hosts,
                          @JsonProperty("methods") List<String> methods,
                          @JsonProperty("paths") List<String> paths) {
        @JsonCreator
        public KeyDecl {
            hosts = hosts == null ? List.of() : List.copyOf(hosts);
            methods = methods == null ? null : List.copyOf(methods);
            paths = paths == null ? null : List.copyOf(paths);
        }
    }

    /** 「什么都没声明」。库里无声明的 skill 存的是 {@code of(JmMetadata.EMPTY, List.of())}，两者相等（见紧凑构造器对 binding 的归一）。 */
    public static final SkillManifest EMPTY = new SkillManifest(List.of(), false, null, null, null, List.of());

    @JsonCreator
    public SkillManifest {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        web = Boolean.TRUE.equals(web);
        timeoutSeconds = timeoutSeconds == null || timeoutSeconds <= 0
                ? SkillManifestParser.DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeoutSeconds, SkillManifestParser.MAX_TIMEOUT_SECONDS);
        // 与 JmMetadata 一致：没写 = optional。不归一的话 EMPTY（binding 为 null）和 of(JmMetadata.EMPTY…)（"optional"）会是
        // 两种「无声明」，将来谁写 manifest.equals(EMPTY) 来判断，对库里新存的行会判错。
        binding = binding == null ? JmMetadata.BINDING_OPTIONAL : binding;
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    public static SkillManifest of(JmMetadata jm, List<KeyDecl> keys) {
        JmMetadata j = jm == null ? JmMetadata.EMPTY : jm;
        return new SkillManifest(j.hosts(), j.web(), j.timeoutSeconds(),
                j.requires() == null ? null : j.requires().token(), j.binding(), keys);
    }

    public JmMetadata toJm() {
        return new JmMetadata(hosts, web, timeoutSeconds,
                requires == null ? null : SkillRequirement.parse(requires), binding);
    }

    /** 有没有 HTTP 声明：hosts、key、web 三者之一（设计文档 §4.1）。有才把 http_request 并入工具列表。 */
    public boolean hasHttp() {
        return !hosts.isEmpty() || web || !keys.isEmpty();
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest 序列化失败", e);
        }
    }

    /**
     * 读库里的 manifest。NULL / 空串（老数据）返回 {@link #EMPTY}；读不出来的（坏 JSON、未知字段）、读出来内容不合法的
     * （requires 认不出来、binding 不是 always / optional）也返回 EMPTY 并记 ERROR——按「没有 HTTP 声明」处理是失败即关闭的方向。
     * 返回的内容，{@link #toJm()} 保证不抛。
     */
    public static SkillManifest fromJson(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        SkillManifest m;
        try {
            m = JSON.readValue(json, SkillManifest.class);
        } catch (Exception e) {
            // getOriginalMessage 不带 Jackson 自己拼上去的「at [Source: …]」，免得和日志里的原文重复
            return unreadable(json, e instanceof JsonProcessingException jpe ? jpe.getOriginalMessage() : e.getMessage());
        }
        String problem = m == null ? "内容是 JSON null" : m.problem();
        return problem == null ? m : unreadable(json, problem);
    }

    /**
     * 读出来的内容能不能用：null = 能用，否则是一句原因。能用的内容，{@link #toJm()} 保证不抛。
     * requires 必须是认得出来的值（空白也不行：我们写入时 null 就不写这个键）；binding 只能是 always / optional。
     */
    private String problem() {
        if (requires != null && SkillRequirement.tryParse(requires) == null) {
            return "requires「" + requires + "」不是认得出来的值";
        }
        if (!JmMetadata.BINDING_ALWAYS.equals(binding) && !JmMetadata.BINDING_OPTIONAL.equals(binding)) {
            return "binding「" + binding + "」只能是 " + JmMetadata.BINDING_ALWAYS + " 或 " + JmMetadata.BINDING_OPTIONAL;
        }
        return null;
    }

    /**
     * 记一条带原文前 200 个字符的 ERROR，返回 EMPTY。manifest 里只有 key 的名字和域名、没有值，可以记；
     * 坏 manifest 的表现是「这个 skill 的外部接口声明消失」，运维靠这段原文才找得到是哪一行数据。
     */
    private static SkillManifest unreadable(String json, String reason) {
        log.error("skill manifest 读不出来，按没有扩展字段处理：{}；原文前 {} 个字符：{}", excerpt(reason), LOG_CHARS, excerpt(json));
        return EMPTY;
    }

    /** 写进日志的库里数据：控制字符换成空格（防止伪造日志行），最多 {@link #LOG_CHARS} 个字符（按码点数），截断处加「…」。 */
    private static String excerpt(String s) {
        String t = String.valueOf(s).replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        return t.codePointCount(0, t.length()) <= LOG_CHARS ? t : t.substring(0, t.offsetByCodePoints(0, LOG_CHARS)) + "…";
    }
}
