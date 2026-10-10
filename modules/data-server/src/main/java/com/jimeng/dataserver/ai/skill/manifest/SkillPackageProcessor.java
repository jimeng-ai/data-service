package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 发布前把一个 skill 目录处理成可以入库的样子（设计文档 §3.4 第 1、3 步）。所有入口共用这一份，纯函数、不碰存储：
 * <ol>
 *   <li><b>最先</b>把上一版所有 key 的值解密出来（{@link PreviousKeys#allValues}），不看 secrets.json 在不在、合不合规、这一条写没写
 *       value；没配密钥就整次拒绝。它们用在后面每一步：报错抹值、沿用条目的替换、残留检查。上一版有、却拿不到值的 key 是解不开的（R27）：
 *       不能沿用，给了新值或者去掉了照常发布；</li>
 *   <li>读 {@code secrets.json} 并校验（平台 skill 不允许带；格式、值↔声明、重名的检查都在 {@link SkillSecretsFile#parse}）；
 *       读不出来 / 越界（太大、key 太多……）就只报它自己那句固定的话，不往下逐条校验——逐条校验会回显条目；</li>
 *   <li>不带 value 的条目是「沿用上一版的值」：只有它们用上一版的值做替换；</li>
 *   <li>值不能出现在任何文件的路径里；根目录不能有只差大小写的 {@code Secrets.json}；</li>
 *   <li>其余文本文件里的 key 原文换成 {@code {{名字}}}（{@link SecretValues#replace}）；二进制文件里出现原文没法替换，整次发布拒绝；</li>
 *   <li><b>残留检查</b>：文本文件在<b>原文</b>上判——每个已知的值（当前的 + 上一版的）的每一处出现，每个字符都要被选中的替换盖住
 *       （{@link SecretValues.Replacement#notFullyReplaced}），否则拒绝发布。这样同时兜住：key 删了但旧值又被打进文件；key 换了
 *       新值但旧值还留在文件里；当前值只盖住旧值的一部分（剩下的部分原样留下）；旧值和当前值首尾相接；两个当前值在文本里互相重叠。
 *       旧值整段落在一个更长的当前值里、或者旧值同时也是这一版某个 key 的值（改了名），都整段被换掉，不算残留。
 *       文件路径、任何 key 的 hosts / methods / paths、二进制文件里还出现上一版的值，照旧按子串检查；</li>
 *   <li>文件里还在用 {@code {{X}}}、上一版有 X、这一版的 secrets.json 却没有 X 了：拒绝（否则新版本静默丢 key）；</li>
 *   <li>★ <b>用替换后的</b> SKILL.md 解析 frontmatter：正文进库时已经是占位符，解析报错的片段里也只有占位符；</li>
 *   <li>★ <b>解析之后再核对一遍</b>（R33、R34，{@link #valuesInParsedFields}）：frontmatter 里 YAML 解码出来的每一个字符串（入不入库都算）、
 *       正文、manifest 的 JSON 里不能有任何已知的值——转义、续行拼出来的值原文里没有、替换不掉，只能在这里兜住。
 *       manifest 的 JSON 由这里算好放进 {@link Output}，调用方原样存。</li>
 * </ol>
 * 解析或校验产生的任何消息放进异常之前都先抹值（当前的值 + 上一版的全部值）：{@code ServiceException} 构造时就把文案记进 ERROR 日志。
 * 加密和「沿用旧值」的范围核对不在这里，见 {@code SkillSecretService.prepare}。
 */
public final class SkillPackageProcessor {

    /** 文件里的占位符 {@code {{KEY_NAME}}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(" + SkillSecretsFile.NAME.pattern() + ")\\}\\}");

    private SkillPackageProcessor() {}

    /**
     * @param files        去掉了 {@code secrets.json}、key 原文已换成占位符的文件，路径 → 内容
     * @param secrets      secrets.json 里的 key（含值，只交给 SkillSecretService 加密）
     * @param manifestJson 要存进 {@code ai_skill.manifest} / {@code ai_skill_version.manifest} 的 JSON（metadata.jm + key 的声明，不含值）。
     *                     由这里算出来、调用方原样存：核对过的就是入库的这一份
     */
    public record Output(ParsedSkill skill, Map<String, byte[]> files, List<SkillSecretsFile.Entry> secrets, String manifestJson) {
        @Override
        public String toString() {
            return "Output[" + skill.name() + ", files=" + files.keySet() + ", secrets=" + secrets + "]";
        }
    }

    /** 新建 skill、导入：没有上一版。 */
    public static Output process(Map<String, byte[]> files, SkillManifestParser.Mode mode, boolean allowSecrets) {
        return process(files, mode, allowSecrets, PreviousKeys.NONE);
    }

    public static Output process(Map<String, byte[]> files, SkillManifestParser.Mode mode, boolean allowSecrets, PreviousKeys previous) {
        if (files == null || files.get(SkillBundleRules.SKILL_MD) == null) {
            throw reject(SecretValues.NONE, "skill 里没有 SKILL.md");
        }
        // 0. 上一版所有 key 的值，最先解密：没配密钥这里就抛 ServiceException（整次拒绝）。配了密钥、但某个 key 的密文解不开（R27），
        // 只有这个 key 不能沿用（下面第 2 步）；它的旧值平台已经不知道是什么，不参与抹值和残留检查
        Map<String, String> previousMap = previous.allValues();
        // ★ 解不开的 = 上一版有、这里却拿不到值的，从这一份值推出来，不信实现单独给的说法：实现对解不开的 key 返回空、又没声明它时，
        // 处理器会把它当成「上一版没有」放行，prepare 随后把解不开的旧密文原样复制进新版本，网关永远解不开它
        final Set<String> undecryptable = PreviousKeys.withoutValue(previous.names(), previousMap);
        SecretValues previousAll = SecretValues.of(previousMap);

        // 1. 读 secrets.json——要先知道有哪些值，后面每一步才抹得掉
        List<SkillSecretsFile.Entry> secrets = List.of();
        SecretValues current = SecretValues.NONE;
        byte[] secretsJson = files.get(SkillSecretsFile.FILE_NAME);
        if (secretsJson != null) {
            if (!allowSecrets) {
                throw reject(previousAll, "平台技能暂不支持带 key（secrets.json），请去掉这个文件；key 不能放进代码仓库");
            }
            SkillSecretsFile.Parsed parsed = SkillSecretsFile.parse(secretsJson, previousAll);
            if (!parsed.valid()) {
                // 这些文案在 parse 里已经抹过值；这里再抹一遍（文件里的值 + 上一版的全部值）是第二道
                throw reject(parsed.values().plus(previousAll), "secrets.json 不符合规则：" + String.join("；", parsed.errors()));
            }
            secrets = parsed.entries();
            current = parsed.values();
        }

        final SecretValues scrub = current.plus(previousAll);               // 报错文案里要抹掉的：当前的 + 上一版的全部
        // 解不开的 key 的名字也会出现在下面的文案里：名字里不能含有任何已知的值（有值的 key 的名字，parse 已经查过了）
        for (String name : undecryptable) {
            if (!scrub.namesIn(name).isEmpty()) throw reject(scrub, "上一版有 key 的名字里含有这次 key 的值：请换一个值，或者联系管理员");
        }

        // 2. 沿用：这一版声明了、没写 value 的条目，用上一版的值。只有它们用上一版的值做替换；解不开的 key 不能沿用
        Map<String, String> reuseMap = new LinkedHashMap<>();
        for (SkillSecretsFile.Entry e : secrets) {
            if (e.value() != null) continue;
            if (undecryptable.contains(e.name())) {
                throw reject(scrub, "key " + e.name() + " 上一版的值解不开（加密密钥可能换过），没法沿用：请在 secrets.json 里给 " + e.name()
                        + " 重新写 value，或者去掉 " + e.name());
            }
            if (previousMap.containsKey(e.name())) reuseMap.put(e.name(), previousMap.get(e.name()));
        }
        SecretValues reuse = SecretValues.of(reuseMap);
        final SecretValues replaceSet = current.plus(reuse);                // 要在文件里换成占位符的
        // 残留检查用的：上一版的全部值。不用先减掉「这一版替换掉的」：被替换的值（沿用的、或者同时也是这一版某个 key 的值），文本里已经
        // 换成占位符、不会再出现；它们在文件路径、二进制、声明里出现，下面各自的检查（replaceSet）先拒了。所以「不算残留」是自然成立的。
        final SecretValues residual = previousAll;
        if (!reuse.isEmpty()) {
            List<String> problems = SkillSecretsFile.crossCheck(secrets, replaceSet);   // 自己的值 parse 里已经查过了，这里只会报出涉及沿用值的
            if (!problems.isEmpty()) throw reject(scrub, "secrets.json 不符合规则：" + String.join("；", problems));
        }
        if (!residual.isEmpty()) {
            List<String> problems = SkillSecretsFile.residualCheck(secrets, residual);
            if (!problems.isEmpty()) throw reject(scrub, "secrets.json 不符合规则：" + String.join("；", problems));
        }

        // 3. 文件路径：根目录只差大小写的 Secrets.json；路径里不能有值（当前的，也不能有上一版没替换掉的）
        for (String path : files.keySet()) {
            if (!path.contains("/") && path.equalsIgnoreCase(SkillSecretsFile.FILE_NAME) && !path.equals(SkillSecretsFile.FILE_NAME)) {
                throw reject(scrub, "根目录有一个叫「" + path + "」的文件：key 文件只认小写的 secrets.json，请改名（不是 key 文件就换个名字）");
            }
            if (SkillSecretsFile.FILE_NAME.equals(path)) continue;
            List<String> inPath = replaceSet.namesIn(path);
            if (!inPath.isEmpty()) {
                throw reject(scrub, "文件「" + path + "」的路径里含有 key " + String.join("、", inPath)
                        + " 的值：路径会变成存储里的对象名、详情页和沙箱里看到的文件名，请改个名字");
            }
            List<String> oldInPath = residual.namesIn(path);
            if (!oldInPath.isEmpty()) {
                throw reject(scrub, "文件「" + path + "」的路径里含有 key " + String.join("、", oldInPath)
                        + " 上一版的值：路径会变成存储里的对象名、详情页和沙箱里看到的文件名，请改个名字");
            }
        }

        // 4. 文本文件里的值换成占位符；二进制里有值就拒绝；残留检查；顺便核对占位符
        Set<String> previousNames = previous.names();
        Set<String> declared = secrets.stream().map(SkillSecretsFile.Entry::name).collect(Collectors.toSet());
        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (SkillSecretsFile.FILE_NAME.equals(f.getKey())) continue;
            out.put(f.getKey(), processFile(f.getKey(), f.getValue(), replaceSet, current, residual, scrub, previousNames, undecryptable, declared));
        }

        // 5. 用替换后的 SKILL.md 解析（校验的文本 = 发布的文本）
        ParsedSkill skill = SkillManifestParser.parse(new String(out.get(SkillBundleRules.SKILL_MD), StandardCharsets.UTF_8), mode);
        if (!skill.valid()) {
            throw reject(scrub, "SKILL.md 不符合规则：" + String.join("；", skill.errors()));
        }

        // 6. ★ 解析之后再核对一遍（R33）：要入库的字符串里不能有任何已知的值。转义、续行拼出来的值只在解析之后才出现，见 valuesInParsedFields
        String manifestJson = SkillManifest.of(skill.jm(), SkillSecretsFile.decls(secrets)).toJson();
        Map<String, List<String>> decoded = valuesInParsedFields(skill, manifestJson, scrub);
        if (!decoded.isEmpty()) {
            throw reject(scrub, decodedValueMessage(decoded));
        }
        return new Output(skill, out, secrets, manifestJson);
    }

    /**
     * 解析之后再核对一遍（R33、R34）：解析得到的字符串里有没有已知的值。返回「位置 → 出现在里面的 key」（按位置的先后），没有就是空。
     *
     * <p>「先替换、再解析」只管得住原文。YAML 双引号里的转义（比如 {@code \x43}）、反斜杠续行、折叠标量、{@code !!binary}、十六进制数字……
     * 任何解码手法拼出来的值，原文里都没有、替换不掉，解码之后才出现。所以统一在解析之后用这一道兜住，不逐个封堵编码写法。用原文子串比：
     * <ul>
     *   <li>frontmatter 里 YAML 解码出来的每一个字符串（{@link ParsedSkill#decoded}：键名和标量值，含 license、被忽略的键、metadata 里的别的字段）——
     *       不入库的也算：转义原样留在 bundle 的 SKILL.md 里，运行时模型读得到、也解得开（R34）；</li>
     *   <li>要入库的：name、description、正文（它不经过解码，核对一下不费事）、manifest 的 JSON（metadata.jm 加 key 的声明，就是存进库的那一份）；
     *       manifest 里的域名入库前被归一成了小写（HostPattern），另外按小写比——和 secrets.json 的 hosts 同一个口径。</li>
     * </ul>
     *
     * <p>发布处理器（构建器发布、GitHub 导入、对话里的 skill_install 都走这里）和构建器的 DRAFT 行同步（SkillWorkspaceReader）共用这一个。
     */
    public static Map<String, List<String>> valuesInParsedFields(ParsedSkill skill, String manifestJson, SecretValues known) {
        Map<String, Set<String>> hits = new LinkedHashMap<>();
        addIfAny(hits, "name", known.namesIn(skill.name()));
        addIfAny(hits, "description", known.namesIn(skill.description()));
        addIfAny(hits, "正文", known.namesIn(skill.body()));
        for (ParsedSkill.Decoded d : skill.decoded()) addIfAny(hits, d.where(), known.namesIn(d.text()));
        addIfAny(hits, "metadata.jm", known.namesIn(manifestJson));
        for (String host : skill.jm().hosts()) addIfAny(hits, "metadata.jm", known.namesInIgnoreCase(host));
        Map<String, List<String>> out = new LinkedHashMap<>();
        hits.forEach((where, names) -> out.put(where, List.copyOf(names)));
        return out;
    }

    private static void addIfAny(Map<String, Set<String>> hits, String where, List<String> names) {
        if (!names.isEmpty()) hits.computeIfAbsent(where, k -> new LinkedHashSet<>()).addAll(names);
    }

    /** 解析之后核对出值的文案：点名字段和 key，不带值，告诉作者别用转义、续行拼 key。 */
    static String decodedValueMessage(Map<String, List<String>> hits) {
        Set<String> names = new LinkedHashSet<>();
        hits.values().forEach(names::addAll);
        return "SKILL.md 的 " + String.join("、", hits.keySet()) + " 解析出来含有 key " + String.join("、", names)
                + " 的值：文件里没有它的原文，多半是用 YAML 的转义（\\x、\\u）或者 \\ 续行拼出来的，这样写换不成占位符。"
                + "别用转义或续行拼 key，要用 key 就写 {{" + names.iterator().next() + "}}";
    }

    private static byte[] processFile(String path, byte[] content, SecretValues replaceSet, SecretValues current, SecretValues residual,
                                      SecretValues scrub, Set<String> previousNames, Set<String> undecryptable, Set<String> declared) {
        String text = decodeText(content);
        if (text == null) {
            List<String> hit = replaceSet.namesIn(content);
            if (!hit.isEmpty()) {
                throw reject(scrub, "文件「" + path + "」里有 key " + String.join("、", hit)
                        + " 的原文，但它不是文本文件，没法换成占位符；请把这个值从文件里拿掉");
            }
            List<String> old = residual.namesIn(content);
            if (!old.isEmpty()) {
                throw reject(scrub, "文件「" + path + "」里有 key " + String.join("、", old)
                        + " 上一版的值，但它不是文本文件，没法换成占位符；请把这个值从文件里拿掉");
            }
            return content;
        }
        // ★ 残留在原文上判：每个已知的值（当前的 + 上一版的）在原文里的每一处出现，每个字符都要被选中的替换盖住。
        // 不能在替换之后的文本里搜：当前值只盖住旧值的前一半时，旧值不再整段出现，后一半却原样留在文本里。
        SecretValues.Replacement replacement = replaceSet.replacement(text);
        List<String> clash = replacement.notFullyReplaced(current);
        if (!clash.isEmpty()) {
            throw reject(scrub, "文件「" + path + "」里 key " + String.join("、", clash) + " 的值没能整段换成占位符：它和别的 key 的值叠在了一起"
                    + "（首尾相接，或者互相包含了一部分），换完会剩下一截原文。请在文件里把这几个值分开写");
        }
        List<String> old = replacement.notFullyReplaced(residual);
        if (!old.isEmpty()) {
            throw reject(scrub, "文件「" + path + "」里出现了 key " + String.join("、", old) + " 上一版的值：要继续用，就在 secrets.json 里保留 "
                    + old.get(0) + "、文件里写 {{" + old.get(0) + "}}；不用了，就把它从文件里删掉");
        }
        String replaced = replacement.text();
        if (!previousNames.isEmpty()) {
            Matcher m = PLACEHOLDER.matcher(replaced);
            while (m.find()) {
                String x = m.group(1);
                if (previousNames.contains(x) && !declared.contains(x)) {
                    if (undecryptable.contains(x)) {   // 解不开的 key 不写值就沿用不了：出路是重新写 value
                        throw reject(scrub, "文件「" + path + "」里还在用 {{" + x + "}}，但 secrets.json 里没有 " + x + " 了，而 " + x
                                + " 上一版的值解不开（加密密钥可能换过）：要继续用，就在 secrets.json 里保留这一条并重新写 value；不用了，就把文件里的 {{"
                                + x + "}} 删掉");
                    }
                    throw reject(scrub, "文件「" + path + "」里还在用 {{" + x + "}}，但 secrets.json 里没有 " + x
                            + " 了：要继续用，就在 secrets.json 里保留这一条（不写值即沿用）；不用了，就把文件里的 {{" + x + "}} 删掉");
                }
            }
        }
        return replaced.equals(text) ? content : replaced.getBytes(StandardCharsets.UTF_8);
    }

    /** 所有拒绝都从这里出：文案先抹值再放进异常（ServiceException 构造时就记进 ERROR 日志）。 */
    private static ServiceException reject(SecretValues values, String message) {
        return new ServiceException(ExceptionCode.INVALID_REQUEST, values.replace(message));
    }

    /** 与构建器预览同一条规则：严格 UTF-8 且不含 NUL 才算文本。 */
    static String decodeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
