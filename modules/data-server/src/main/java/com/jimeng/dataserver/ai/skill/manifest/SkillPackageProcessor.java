package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 发布前把一个 skill 目录处理成可以入库的样子（设计文档 §3.4 第 1、3 步）。所有入口共用这一份，纯函数、不碰存储：
 * <ol>
 *   <li>读 {@code secrets.json} 并校验（平台 skill 不允许带）；</li>
 *   <li>其余文本文件里的 key 原文换成 {@code {{名字}}}（长的值先换，免得短值是长值前缀时只换掉一半）；
 *       二进制文件里出现原文没法替换，整次发布拒绝；</li>
 *   <li>用替换后的 SKILL.md 解析 frontmatter：正文进库时已经是占位符。</li>
 * </ol>
 * 加密和「沿用旧值」不在这里，见 {@code SkillSecretService.prepare}。
 */
public final class SkillPackageProcessor {

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

    public static Output process(Map<String, byte[]> files, SkillManifestParser.Mode mode, boolean allowSecrets) {
        if (files == null || files.get(SkillBundleRules.SKILL_MD) == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "skill 里没有 SKILL.md");
        }
        List<SkillSecretsFile.Entry> secrets = List.of();
        byte[] secretsJson = files.get(SkillSecretsFile.FILE_NAME);
        if (secretsJson != null) {
            if (!allowSecrets) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "平台技能暂不支持带 key（secrets.json），请去掉这个文件；key 不能放进代码仓库");
            }
            SkillSecretsFile.Parsed parsed = SkillSecretsFile.parse(secretsJson);
            if (!parsed.valid()) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "secrets.json 不符合规则：" + String.join("；", parsed.errors()));
            }
            secrets = parsed.entries();
        }
        List<SkillSecretsFile.Entry> withValues = secrets.stream()
                .filter(e -> e.value() != null)
                .sorted(Comparator.comparingInt((SkillSecretsFile.Entry e) -> e.value().length()).reversed())
                .toList();

        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (SkillSecretsFile.FILE_NAME.equals(f.getKey())) continue;
            out.put(f.getKey(), replaceLiterals(f.getKey(), f.getValue(), withValues));
        }
        ParsedSkill skill = SkillManifestParser.parse(
                new String(out.get(SkillBundleRules.SKILL_MD), StandardCharsets.UTF_8), mode).orThrow();
        return new Output(skill, out, secrets);
    }

    private static byte[] replaceLiterals(String path, byte[] content, List<SkillSecretsFile.Entry> keys) {
        if (keys.isEmpty()) return content;
        String text = decodeText(content);
        if (text == null) {
            for (SkillSecretsFile.Entry k : keys) {
                if (indexOf(content, k.value().getBytes(StandardCharsets.UTF_8)) >= 0) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文件「" + path + "」里有 key " + k.name()
                            + " 的原文，但它不是文本文件，没法换成占位符；请把这个值从文件里拿掉");
                }
            }
            return content;
        }
        String replaced = text;
        for (SkillSecretsFile.Entry k : keys) {
            replaced = replaced.replace(k.value(), "{{" + k.name() + "}}");
        }
        return replaced.equals(text) ? content : replaced.getBytes(StandardCharsets.UTF_8);
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

    static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
