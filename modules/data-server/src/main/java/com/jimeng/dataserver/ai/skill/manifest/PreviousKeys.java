package com.jimeng.dataserver.ai.skill.manifest;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 改进已有 skill 时，上一版的 key（设计文档 §3.4 第 4 条）。发布处理器是纯函数、不碰存储，要用上一版的东西，由调用方
 * （实现在 {@code SkillSecretService.previousKeys}：发布时最先调，一次把上一版所有 key 解密成一份快照）递过来。
 *
 * <p>发布时<b>上一版所有 key 的值</b>都要解密出来（{@link #allValues}），不看 secrets.json 在不在、合不合规、这一条写没写 value：
 * <ul>
 *   <li>所有报错文案的抹值：作者写的 hosts / paths、SKILL.md 的 YAML 报错片段里，旧值随时可能出现，
 *       这些文案会进异常、ERROR 日志和响应，secrets.json 校验不过也一样要抹；</li>
 *   <li>沿用：这一版声明了、没写 value 的条目，文件里的原文换成 {@code {{名字}}}；</li>
 *   <li>残留检查：当前的值替换完之后，文件内容、文件路径、任何 key 的 hosts / methods / paths 里还有上一版的值，拒绝发布
 *       （key 删了但旧值又被打进文件；key 换了新值但旧值还留在文件里）；</li>
 *   <li>文件里还在用 {@code {{X}}}、上一版有 X、这一版的 secrets.json 却没有 X 了：拒绝（否则新版本静默丢 key）。</li>
 * </ul>
 * 每轮草稿（预览、同步）不解密，传 {@link #NONE}：上一版的值只在发布时于内存里解密，模型和工作区都拿不到。
 *
 * <p><b>解不开的 key 按 key 处理（R27）</b>：没配加密密钥，整次拒绝（部署配置错了，新值没法加密，运行时也解不开）——实现<b>必须抛</b>，
 * 不能返回空 map 冒充「没有」；配了密钥、但某个 key 的密文解不开（密钥换过、密文坏了），只有这一个 key 不能沿用——它在新的
 * secrets.json 里没写 value 时拒绝，给了新值或者去掉了就照常发布（不然这个 skill 就再也不能用「用 AI 改进」发布了，用户自己没有出路）。
 *
 * <p>★ <b>「解不开」不由实现单独声明，而是由 {@link #names()} 和值推出来</b>（{@link #undecryptable()}）：在 names() 里、却拿不到值的
 * key 就是解不开的。以前它是一个可以单独覆写的方法，key 服务的实现没覆写它，对解不开的 key 又返回空 map——处理器把它当成
 * 「上一版没有」放行，随后 {@code prepare} 把解不开的旧密文原样复制进新版本，网关永远解不开它。现在两者对不上的状态不存在。
 * 解不开的旧值平台已经不知道它是什么，所以不参与抹值和残留检查；它的名字仍在 {@link #names()} 里，「文件里还在用 {{X}} 却被去掉了」的核对要用。
 */
public interface PreviousKeys {

    /** 新建 skill、导入、每轮草稿：没有上一版，或者不解密。 */
    PreviousKeys NONE = new PreviousKeys() {
        @Override
        public Set<String> names() {
            return Set.of();
        }

        @Override
        public Map<String, String> values(Collection<String> names) {
            return Map.of();
        }
    };

    /** 上一版所有 key 的名字，解得开的、解不开的都在。 */
    Set<String> names();

    /**
     * 这些名字在上一版的值；上一版没有的名字、以及解不开的，都不出现在结果里（解不开的见 {@link #undecryptable()}）。
     * ★ 没配加密密钥（一个都解不了）必须抛 {@code ServiceException}（文案只带 key 名，不带值），不能返回空 map 冒充「没有」：
     * 调用方拿它核对要发布的文件，跳过核对就等于放行。
     */
    Map<String, String> values(Collection<String> names);

    /** 上一版所有<b>解得开</b>的 key 的值（名字 → 值）。没有上一版的 key 时不需要密钥。 */
    default Map<String, String> allValues() {
        return values(names());
    }

    /**
     * 上一版里解不开的 key 的名字（R27）：在 {@link #names()} 里、却拿不到值的。由 names() 和值推出来，<b>不要覆写</b>
     * ——发布处理器也不调它，而是用同一个 {@link #withoutValue} 从它自己拿到的那一份值推，不信任何实现单独给的说法。
     */
    default Set<String> undecryptable() {
        return withoutValue(names(), allValues());
    }

    /** 在 names 里、却不在 values 里的名字（保持 names 的顺序）。 */
    static Set<String> withoutValue(Set<String> names, Map<String, String> values) {
        Set<String> out = new LinkedHashSet<>(names);
        out.removeAll(values.keySet());
        return Collections.unmodifiableSet(out);
    }

    /**
     * 已经解密好的一份：之后的 {@link #values} / {@link #allValues} 只是查表，不再解密。发布时在最前面解一次，
     * 草稿读取（报错抹值）和发布处理器共用同一份。
     */
    static PreviousKeys decrypted(Map<String, String> all) {
        return decrypted(all, Set.of());
    }

    /** 同上，另带着解不开的 key 的名字：{@link #names()} 包含它们，{@link #values} 里没有，于是 {@link #undecryptable()} 就是它们。 */
    static PreviousKeys decrypted(Map<String, String> all, Set<String> undecryptable) {
        Map<String, String> copy = Map.copyOf(all);
        Set<String> names = new LinkedHashSet<>(copy.keySet());
        names.addAll(undecryptable);
        Set<String> allNames = Set.copyOf(names);
        return new PreviousKeys() {
            @Override
            public Set<String> names() {
                return allNames;
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                Map<String, String> out = new LinkedHashMap<>();
                for (String n : wanted) if (copy.containsKey(n)) out.put(n, copy.get(n));
                return out;
            }
        };
    }
}
