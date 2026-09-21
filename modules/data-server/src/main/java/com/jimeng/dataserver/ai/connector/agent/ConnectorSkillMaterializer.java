package com.jimeng.dataserver.ai.connector.agent;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.service.SkillBundleResolver;
import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 把磁盘上的平台技能 {@code connector} 物化进 MinIO，产出边车能直接铺进 {@code .claude/skills} 的
 * {@link SidecarRunPayload.SkillRef}。
 *
 * <h3>为什么沙箱平面也要这份 SKILL.md</h3>
 * 对话平面上 {@code connector} 是直注技能，SKILL.md 全文进 system；沙箱平面过去<b>只有工具描述</b>。
 * 两处差的正是 SKILL.md 里那套用法：先 {@code conn_list} 再 {@code conn_catalog} 再 {@code conn_describe}
 * 的取数顺序、写操作必须先查清影响行、截断与 verified 三态怎么解释。少了它模型仍然能调工具，
 * 只是会跳步、会拿快照当权威结构、会在不该写的时候写——安静地差一截。
 *
 * <h3>内容寻址前缀：稳态下每轮零写入</h3>
 * 前缀里嵌的是全文 SHA-256 的前 16 位。SKILL.md 不变 ⇒ 前缀不变 ⇒ 对象已在 ⇒ 每轮只做一次
 * 列举、不写。改了 SKILL.md（发版）⇒ 新哈希 ⇒ 新前缀写一次。旧前缀留着不删：可能还有在跑的运行引用它。
 *
 * <h3>失败一律降级，不让整轮 run 挂掉</h3>
 * 任何异常只 {@code log.warn} 并返回 {@code null}。模型没有 SKILL.md 仍能靠工具描述与系统提示里的
 * 六条契约工作——差一些，但比「因为一次 MinIO 抖动整轮对话报错」好得多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorSkillMaterializer {

    /** 平台技能名（{@code skills/connector/SKILL.md} 的 frontmatter name），也是边车里的技能目录名。 */
    private static final String SKILL_NAME = "connector";

    /** 内容寻址前缀的根。{@code platform} 这一段用来与租户 DOER 技能的 bundleKey 分开。 */
    private static final String PREFIX_ROOT = "skills/platform/connector/";

    private static final String SKILL_FILE = "SKILL.md";

    private final SkillPackageLoaderService skillPackageLoaderService;
    private final RagMinioStorageService storage;

    /**
     * @return 可直接追加进 {@code payload.skills} 的引用；技能包缺失 / 物化失败时返回 {@code null}
     */
    public SidecarRunPayload.SkillRef materialize() {
        try {
            SkillPackage pkg = skillPackageLoaderService.findByName(
                    skillPackageLoaderService.loadSkillPackages(), SKILL_NAME);
            if (pkg == null || StrUtil.isBlank(pkg.getBody())) {
                log.warn("平台技能 {} 未加载到（或正文为空），本轮沙箱不带它的 SKILL.md", SKILL_NAME);
                return null;
            }

            String full = compose(pkg);
            String prefix = PREFIX_ROOT + sha256Hex16(full) + "/";
            String objectName = prefix + SKILL_FILE;

            // 以对象全名为前缀列举 = statObject 的等价物（RagMinioStorageService 没有 stat 接口）：
            // 命中说明这版全文已经在了，直接构造引用返回，稳态下每轮零写入。
            List<String> existing = storage.listObjects(objectName);
            if (existing == null || !existing.contains(objectName)) {
                storage.putObject(objectName, full.getBytes(StandardCharsets.UTF_8), "text/markdown");
                log.info("平台技能 {} 物化到沙箱前缀 {}", SKILL_NAME, prefix);
            }
            return SkillBundleResolver.toSkillRef(
                    StrUtil.blankToDefault(pkg.getName(), SKILL_NAME), prefix, storage.getBucket(),
                    List.of(objectName));
        } catch (Exception e) {
            log.warn("物化平台技能 {} 失败，本轮沙箱不带它的 SKILL.md：{}", SKILL_NAME, e.getMessage());
            return null;
        }
    }

    /**
     * 拼回一份 Claude Agent SDK 能读的 SKILL.md：loader 已经把 frontmatter 剥掉了，这里只补
     * {@code name} / {@code description} 两个键。
     *
     * <p>★ <b>刻意不补 {@code requires}</b>，并且把正文里任何残留的 {@code requires:} 行删掉。
     * 那是本仓 JVM loader 的私有字段（{@code SkillRequirement}，用来决定直注还是走发现），
     * Claude Agent SDK 对未知 frontmatter 键的容忍度本仓<b>未核实</b>——不拿线上对话去试。
     */
    private String compose(SkillPackage pkg) {
        String description = StrUtil.nullToEmpty(pkg.getDescription()).replace("\n", " ").trim();
        return "---\n"
                + "name: " + SKILL_NAME + "\n"
                + "description: " + description + "\n"
                + "---\n\n"
                + stripLeadingFrontmatterRequires(pkg.getBody()).trim() + "\n";
    }

    /**
     * 只在正文<b>开头仍然是一段 frontmatter</b> 时，删掉其中的 {@code requires:} 行。
     *
     * <p>正常路径上 loader 已剥掉 frontmatter，这里是空操作；收窄到「开头的那段」是为了
     * 不去动正文里可能出现的同名字样（SKILL.md 是讲用法的散文，误删正文比多留一行更糟）。
     */
    private static String stripLeadingFrontmatterRequires(String body) {
        String normalized = StrUtil.nullToEmpty(body).replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return normalized;
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            return normalized;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : normalized.substring(4, end).split("\n", -1)) {
            if (line.trim().toLowerCase().startsWith("requires:")) continue;
            kept.append(line).append('\n');
        }
        return "---\n" + kept + normalized.substring(end + 1);
    }

    /** 全文 SHA-256 的前 16 位十六进制，做内容寻址的前缀段。 */
    private static String sha256Hex16(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.substring(0, 16);
    }
}
