package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 *   <li>读 {@code secrets.json} 并校验（平台 skill 不允许带；格式、值↔声明、重名的检查都在
 *       {@link SkillSecretsFile#parse}）；</li>
 *   <li>不带 value 的条目是「沿用上一版的值」：把上一版的值解密出来（{@link PreviousKeys}）并进下面所有步骤；</li>
 *   <li>值不能出现在任何文件的路径里；根目录不能有只差大小写的 {@code Secrets.json}；</li>
 *   <li>其余文本文件里的 key 原文换成 {@code {{名字}}}（{@link SecretValues#replace}）；
 *       二进制文件里出现原文没法替换，整次发布拒绝；</li>
 *   <li>文件里还在用 {@code {{X}}}、上一版有 X、这一版的 secrets.json 却没有 X 了：拒绝（否则新版本静默丢 key）；</li>
 *   <li>★ <b>用替换后的</b> SKILL.md 解析 frontmatter：正文进库时已经是占位符，解析报错的片段里也只有占位符。</li>
 * </ol>
 * 解析或校验产生的任何消息放进异常之前都先抹值：{@code ServiceException} 构造时就把文案记进 ERROR 日志。
 * 加密和「沿用旧值」的范围核对不在这里，见 {@code SkillSecretService.prepare}。
 */
public final class SkillPackageProcessor {

    /** 文件里的占位符 {@code {{KEY_NAME}}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(" + SkillSecretsFile.NAME.pattern() + ")\\}\\}");

    private SkillPackageProcessor() {}

    /**
     * @param files   去掉了 {@code secrets.json}、key 原文已换成占位符的文件，路径 → 内容
     * @param secrets secrets.json 里的 key（含值，只交给 SkillSecretService 加密）
     */
    public record Output(ParsedSkill skill, Map<String, byte[]> files, List<SkillSecretsFile.Entry> secrets) {
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
        // 1. 先读 secrets.json——要先知道有哪些值，后面每一步才抹得掉
        List<SkillSecretsFile.Entry> secrets = List.of();
        SecretValues values = SecretValues.NONE;
        byte[] secretsJson = files.get(SkillSecretsFile.FILE_NAME);
        if (secretsJson != null) {
            if (!allowSecrets) {
                throw reject(SecretValues.NONE, "平台技能暂不支持带 key（secrets.json），请去掉这个文件；key 不能放进代码仓库");
            }
            SkillSecretsFile.Parsed parsed = SkillSecretsFile.parse(secretsJson);
            if (!parsed.valid()) {
                throw reject(SecretValues.NONE, "secrets.json 不符合规则：" + String.join("；", parsed.errors()));   // 这些文案在 parse 里已经抹过值
            }
            secrets = parsed.entries();
            values = parsed.values();
        }

        // 2. 沿用上一版的值：解密出来并进来；解不出来就整次拒绝（previous.values 抛 ServiceException），绝不跳过核对
        List<String> reuseNames = secrets.stream().filter(e -> e.value() == null).map(SkillSecretsFile.Entry::name).toList();
        if (!reuseNames.isEmpty()) {
            SecretValues reused = SecretValues.of(previous.values(reuseNames));
            if (!reused.isEmpty()) {
                values = values.plus(reused);
                List<String> problems = SkillSecretsFile.crossCheck(secrets, values);   // 自己的值 parse 里已经查过了，这里只会报出涉及沿用值的
                if (!problems.isEmpty()) throw reject(values, "secrets.json 不符合规则：" + String.join("；", problems));
            }
        }
        final SecretValues all = values;

        // 3. 文件路径：根目录只差大小写的 Secrets.json；路径里不能有值
        for (String path : files.keySet()) {
            if (!path.contains("/") && path.equalsIgnoreCase(SkillSecretsFile.FILE_NAME) && !path.equals(SkillSecretsFile.FILE_NAME)) {
                throw reject(all, "根目录有一个叫「" + path + "」的文件：key 文件只认小写的 secrets.json，请改名（不是 key 文件就换个名字）");
            }
            if (SkillSecretsFile.FILE_NAME.equals(path)) continue;
            List<String> inPath = all.namesIn(path);
            if (!inPath.isEmpty()) {
                throw reject(all, "文件「" + path + "」的路径里含有 key " + String.join("、", inPath)
                        + " 的值：路径会变成存储里的对象名、详情页和沙箱里看到的文件名，请改个名字");
            }
        }

        // 4. 文本文件里的值换成占位符；二进制里有值就拒绝；顺便核对占位符
        Set<String> previousNames = previous.names();
        Set<String> declared = secrets.stream().map(SkillSecretsFile.Entry::name).collect(Collectors.toSet());
        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (SkillSecretsFile.FILE_NAME.equals(f.getKey())) continue;
            out.put(f.getKey(), processFile(f.getKey(), f.getValue(), all, previousNames, declared));
        }

        // 5. 用替换后的 SKILL.md 解析（校验的文本 = 发布的文本）
        ParsedSkill skill = SkillManifestParser.parse(new String(out.get(SkillBundleRules.SKILL_MD), StandardCharsets.UTF_8), mode);
        if (!skill.valid()) {
            throw reject(all, "SKILL.md 不符合规则：" + String.join("；", skill.errors()));
        }
        return new Output(skill, out, secrets);
    }

    private static byte[] processFile(String path, byte[] content, SecretValues values, Set<String> previousNames, Set<String> declared) {
        String text = decodeText(content);
        if (text == null) {
            List<String> hit = values.namesIn(content);
            if (!hit.isEmpty()) {
                throw reject(values, "文件「" + path + "」里有 key " + String.join("、", hit)
                        + " 的原文，但它不是文本文件，没法换成占位符；请把这个值从文件里拿掉");
            }
            return content;
        }
        String replaced = values.replace(text);
        if (!previousNames.isEmpty()) {
            Matcher m = PLACEHOLDER.matcher(replaced);
            while (m.find()) {
                String x = m.group(1);
                if (previousNames.contains(x) && !declared.contains(x)) {
                    throw reject(values, "文件「" + path + "」里还在用 {{" + x + "}}，但 secrets.json 里没有 " + x
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
