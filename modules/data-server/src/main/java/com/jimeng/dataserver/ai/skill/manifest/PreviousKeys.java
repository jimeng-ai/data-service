package com.jimeng.dataserver.ai.skill.manifest;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 改进已有 skill 时，上一版的 key（设计文档 §3.4 第 4 条）。发布处理器是纯函数、不碰存储，要用上一版的东西，由调用方
 * （实现在 {@code SkillSecretService.previousKeys}）递过来。
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
 * <p><b>解不开的 key 按 key 处理（R27）</b>：没配加密密钥，整次拒绝（部署配置错了，新值没法加密，运行时也解不开）；
 * 配了密钥、但某个 key 的密文解不开（密钥换过、密文坏了），只有这一个 key 不能沿用——它在新的 secrets.json 里没写 value 时拒绝，
 * 给了新值或者去掉了就照常发布（不然这个 skill 就再也不能用「用 AI 改进」发布了，用户自己没有出路）。解不开的旧值
 * 平台已经不知道它是什么，所以不参与抹值和残留检查；它的名字仍在 {@link #names()} 里，「文件里还在用 {{X}} 却被去掉了」的核对要用。
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

    /** 上一版所有 key 的名字（不需要密钥）。 */
    Set<String> names();

    /**
     * 把这些名字在上一版的值解密出来；上一版没有的名字不出现在结果里（由 {@code SkillSecretService.prepare} 报「没有给值」）。
     * ★ 解密不了（没配密钥、密文解不开）必须抛 {@code ServiceException}（文案只带 key 名，不带值），不能返回空 map 冒充「没有」：
     * 调用方拿它核对要发布的文件，跳过核对就等于放行。
     */
    Map<String, String> values(Collection<String> names);

    /** 上一版所有<b>解得开</b>的 key 的值（名字 → 值）。没有上一版的 key 时不需要密钥。 */
    default Map<String, String> allValues() {
        return values(names());
    }

    /** 上一版里配了密钥、但密文解不开的 key 的名字（R27）。只有 {@link #decrypted(Map, Set)} 的结果带着它。 */
    default Set<String> undecryptable() {
        return Set.of();
    }

    /** 解密一遍的结果：解得开的值，和解不开的 key 的名字。 */
    record Opened(Map<String, String> values, Set<String> undecryptable) {}

    /**
     * 把上一版所有 key 解密一遍。没配密钥就抛 {@code ServiceException}；某个 key 的密文解不开不抛，记在
     * {@link Opened#undecryptable} 里，由发布处理器按 key 处理。
     */
    default Opened open() {
        return new Opened(allValues(), undecryptable());
    }

    /**
     * 已经解密好的一份：之后的 {@link #values} / {@link #allValues} 只是查表，不再解密。发布时在最前面解一次，
     * 草稿读取（报错抹值）和发布处理器共用同一份。
     */
    static PreviousKeys decrypted(Map<String, String> all) {
        return decrypted(all, Set.of());
    }

    /** 同上，另带着解不开的 key 的名字（{@link #names()} 包含它们，{@link #values} 里没有）。 */
    static PreviousKeys decrypted(Map<String, String> all, Set<String> undecryptable) {
        Map<String, String> copy = Map.copyOf(all);
        Set<String> failed = Set.copyOf(undecryptable);
        Set<String> names = new LinkedHashSet<>(copy.keySet());
        names.addAll(failed);
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

            @Override
            public Set<String> undecryptable() {
                return failed;
            }
        };
    }
}
