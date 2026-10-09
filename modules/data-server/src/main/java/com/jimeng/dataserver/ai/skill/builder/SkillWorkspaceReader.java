package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftFile;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReviewMeta;
import com.jimeng.dataserver.ai.skill.manifest.ParsedSkill;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SecretValues;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 读构建器工作区（MinIO 上的会话前缀）：被构建的 skill、最新一轮评审页、触发优化报告。目录约定见 {@link SkillWorkspaceLayout}。
 *
 * <p>只读、无状态：每次按前缀重新列一次对象。工作区是这些信息的唯一真源——不在库里再存一份，
 * 存了就会出现「库里说有评审页、工作区里其实没有」的漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillWorkspaceReader {

    private final RagMinioStorageService storage;
    private final SkillBuilderProperties props;

    /** 一次列举的快照：相对工作区根的路径 → 对象信息。 */
    public record Listing(String prefix, Map<String, ObjectInfo> objects) {
        public boolean has(String rel) {
            return objects.containsKey(rel);
        }
    }

    /** 选中的 skill 目录（dir 为 null 表示工作区里还没有 skill）。 */
    public record SkillLocation(String dir, List<String> others) {}

    public Listing list(String prefix) {
        try {
            Map<String, ObjectInfo> m = new LinkedHashMap<>();
            for (ObjectInfo o : storage.listObjectInfos(prefix)) {
                if (o.name().startsWith(prefix)) m.put(o.name().substring(prefix.length()), o);
            }
            return new Listing(prefix, m);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "读取构建器工作区失败: " + e.getMessage());
        }
    }

    /**
     * 找被构建的 skill：skill/&lt;dir&gt;/SKILL.md 里最近修改的那个。一个会话约定只做一个 skill，
     * 出现多个时取最新的，其余列进 {@link SkillLocation#others} 让界面提示出来——不静默挑一个。
     */
    public SkillLocation locateSkill(Listing l) {
        List<Map.Entry<String, ObjectInfo>> hits = new ArrayList<>();
        for (Map.Entry<String, ObjectInfo> e : l.objects().entrySet()) {
            if (SkillWorkspaceLayout.skillDirOfSkillMd(e.getKey()) != null) hits.add(e);
        }
        if (hits.isEmpty()) return new SkillLocation(null, List.of());
        hits.sort(Comparator.comparing((Map.Entry<String, ObjectInfo> e) ->
                e.getValue().lastModified() == null ? new Date(0) : e.getValue().lastModified()).reversed());
        List<String> dirs = hits.stream().map(e -> SkillWorkspaceLayout.skillDirOfSkillMd(e.getKey())).toList();
        return new SkillLocation(dirs.get(0), dirs.subList(1, dirs.size()));
    }

    /** skill 目录下的全部文件：相对 skill 目录的路径 → 对象信息（按路径排序）。 */
    public Map<String, ObjectInfo> skillFiles(Listing l, String dir) {
        String p = SkillWorkspaceLayout.skillDirPrefix(dir);
        Map<String, ObjectInfo> out = new java.util.TreeMap<>();
        l.objects().forEach((rel, o) -> {
            if (rel.startsWith(p)) out.put(rel.substring(p.length()), o);
        });
        return out;
    }

    public byte[] readBytes(Listing l, String rel) {
        try {
            return storage.readBytes(l.prefix() + rel);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "读取工作区文件失败 " + rel + ": " + e.getMessage());
        }
    }

    /** 草稿预览；工作区里还没有 skill 时返回 null。预览和同步 DRAFT 行用这个：不解密沿用的 key。 */
    public DraftView draft(Listing l, String skillTypeOverride) {
        return draft(l, skillTypeOverride, PreviousKeys.NONE);
    }

    /**
     * 草稿预览；工作区里还没有 skill 时返回 null。
     *
     * <p>★ key 的值不出现在这里的任何输出里（预览会随 draft-update 事件推给前端、写进 Redis Stream，DRAFT 行同步也用它；
     * 报错文案会进异常和 ERROR 日志）：先用工作区 secrets.json 里的值把 SKILL.md 抹一遍，<b>再</b>解析——
     * 校验的文本就是将要发布的文本，报错片段里也只有 {@code {{名字}}}；secrets.json 的预览把每个 value 打码。
     * secrets.json 存在却读不出来时不知道哪些是值：只报它自己的错误，不校验 SKILL.md（见 {@link DraftView#isSecretsUnreadable}）。
     *
     * @param previous 发布时传进来：不带 value 的条目是沿用上一版的值，它们的原文只有解密之后才抹得掉。预览不传。
     */
    public DraftView draft(Listing l, String skillTypeOverride, PreviousKeys previous) {
        SkillLocation loc = locateSkill(l);
        if (loc.dir() == null) return null;
        String dirPrefix = SkillWorkspaceLayout.skillDirPrefix(loc.dir());
        Map<String, ObjectInfo> files = skillFiles(l, loc.dir());
        String rawSkillMd = new String(readBytes(l, dirPrefix + SkillBundleRules.SKILL_MD), StandardCharsets.UTF_8);

        List<String> errors = new ArrayList<>();
        SecretValues values = SecretValues.NONE;
        boolean unreadable = false;
        byte[] secretsJson = null;
        if (files.containsKey(SkillBundleRules.SECRETS_JSON)) {
            secretsJson = readBytes(l, dirPrefix + SkillBundleRules.SECRETS_JSON);
            SkillSecretsFile.Parsed parsed = SkillSecretsFile.parse(secretsJson);
            errors.addAll(parsed.errors());   // 文案在 parse 里已经抹过值
            if (parsed.readable()) {
                values = parsed.values();
                List<String> reuse = parsed.valid()
                        ? parsed.entries().stream().filter(e -> e.value() == null).map(SkillSecretsFile.Entry::name).toList()
                        : List.of();
                if (!reuse.isEmpty()) values = values.plus(SecretValues.of(previous.values(reuse)));
            } else {
                unreadable = true;
            }
        }
        final SecretValues known = values;

        String skillMd = known.replace(rawSkillMd);
        ParsedSkill fm = SkillManifestParser.parse(skillMd, SkillManifestParser.Mode.STRICT);
        if (unreadable) {
            // 不知道哪些是 key 的值：只报 secrets.json 自己的错误，SKILL.md 的校验报错、目录名 / name 不一致这类会回显文件名和 name 的话都不给
            errors.add("SKILL.md 等 secrets.json 修好之后再校验：现在不知道哪些是 key 的值，校验的报错里可能带出原文");
        } else {
            fm.errors().forEach(e -> errors.add(known.replace(e)));
            // quick_validate 的「只能有一个 SKILL.md」：除根目录 SKILL.md 外，属于 skill 的路径里不能再有 SKILL.md。
            List<String> nested = files.keySet().stream()
                    .filter(p -> !p.equals(SkillBundleRules.SKILL_MD) && p.endsWith("/" + SkillBundleRules.SKILL_MD))
                    .filter(p -> SkillBundleRules.isPartOfSkill(p) && !SkillBundleRules.isRootEvalsPath(p))
                    .toList();
            if (!nested.isEmpty()) {
                errors.add(known.replace("skill 里只能有一个 SKILL.md，多出来的：" + String.join(", ", nested)
                        + "（参考资料请改名为 references/<主题>.md）"));
            }
            if (fm.name() != null && !fm.name().equals(loc.dir())) {
                errors.add(known.replace("目录名「" + loc.dir() + "」与 frontmatter 的 name「" + fm.name() + "」不一致"));
            }
        }

        DraftView v = new DraftView();
        v.setDirName(loc.dir());
        v.setName(fm.name());
        v.setDescription(fm.description());
        v.setSkillMd(skillMd);
        v.setBody(fm.body());
        String inferred = SkillBundleRules.inferSkillType(files.keySet());
        v.setInferredType(inferred);
        v.setEffectiveType(skillTypeOverride != null ? skillTypeOverride : inferred);
        v.setValidationErrors(errors);
        v.setIterations(iterationCount(l, loc.dir()));
        v.setOtherSkillDirs(loc.others());
        v.setSecretsUnreadable(unreadable);

        List<DraftFile> out = new ArrayList<>();
        for (Map.Entry<String, ObjectInfo> entry : files.entrySet()) {
            String path = entry.getKey();
            ObjectInfo o = entry.getValue();
            if (!SkillBundleRules.isPartOfSkill(path)) continue;   // 缓存 / 依赖不进预览
            DraftFile f = new DraftFile();
            f.setPath(path);
            f.setSize(o.size());
            f.setRuntime(SkillBundleRules.isRuntimeFile(path));
            if (o.size() <= props.getPreviewFileMaxBytes()) {
                if (SkillBundleRules.isSecretsFile(path)) {
                    f.setText(SkillSecretsFile.maskedPreview(secretsJson));   // 读不出来是 null：一个字都不给
                    f.setBinary(false);
                } else {
                    String text = decodeText(readBytes(l, dirPrefix + path));
                    f.setText(text == null ? null : known.replace(text));
                    f.setBinary(text == null);
                }
            } else {
                f.setBinary(false);
            }
            out.add(f);
        }
        v.setFiles(out);
        return v;
    }

    /** 最新一轮评审页的元数据；没有评审页返回 null。 */
    public ReviewMeta latestReview(Listing l, String dir) {
        Integer n = latestReviewIteration(l, dir);
        if (n == null) return null;
        ReviewMeta m = new ReviewMeta();
        m.setIteration(n);
        ObjectInfo o = l.objects().get(SkillWorkspaceLayout.reviewPath(dir, n));
        m.setUpdatedAt(o == null || o.lastModified() == null ? null : isoTime(o.lastModified()));
        m.setFeedbackSubmitted(l.has(SkillWorkspaceLayout.feedbackPath(dir, n)));
        return m;
    }

    public Integer latestReviewIteration(Listing l, String dir) {
        Integer best = null;
        for (String rel : l.objects().keySet()) {
            Object[] m = SkillWorkspaceLayout.matchReview(rel);
            if (m == null || !Objects.equals(m[0], dir)) continue;
            int n = (Integer) m[1];
            if (best == null || n > best) best = n;
        }
        return best;
    }

    public boolean hasOptimizationReport(Listing l, String dir) {
        return dir != null && l.has(SkillWorkspaceLayout.optimizationReportPath(dir));
    }

    /** iteration-N 目录数（按出现过的文件推出来，MinIO 没有真正的目录）。 */
    public int iterationCount(Listing l, String dir) {
        String p = SkillWorkspaceLayout.workspaceDirPrefix(dir) + "iteration-";
        TreeSet<String> ns = new TreeSet<>();
        for (String rel : l.objects().keySet()) {
            if (!rel.startsWith(p)) continue;
            String rest = rel.substring(p.length());
            int slash = rest.indexOf('/');
            if (slash > 0 && rest.substring(0, slash).matches("\\d{1,4}")) ns.add(rest.substring(0, slash));
        }
        return ns.size();
    }

    /** 严格 UTF-8 解码；不是合法 UTF-8 或含 NUL（二进制的强信号）则返回 null。 */
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

    private static String isoTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        return f.format(d);
    }

    /** 运行方式是否合法（PROMPT / DOER）。 */
    static boolean validType(String t) {
        return SkillConst.TYPE_PROMPT.equals(t) || SkillConst.TYPE_DOER.equals(t);
    }
}
