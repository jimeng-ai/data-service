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

    /**
     * 有界读取：最多读 {@code limit + 1} 个字节，内容超过 {@code limit} 就返回 null（调用方按超限处理）。
     * 列举时报的大小只是当时的快照，读之前对象可能被换大（会话 GET 发生在一轮运行中，沙箱还在写工作区）：
     * 上限在读的时候才算数，不先把整个对象读进内存再判断。
     *
     * <p>读失败时异常文案里的路径先用 {@code known} 抹值（存储报的出错信息里常常带着对象名，一起抹）。
     * 读 secrets.json 本身时值集合还没确定：{@code showWhenFailed} 传固定的 {@code secrets.json}，且不带存储报的出错信息。
     */
    private byte[] readBounded(Listing l, String rel, long limit, SecretValues known, String showWhenFailed, boolean withDetail) {
        byte[] bytes;
        try {
            bytes = storage.readBytesUpTo(l.prefix() + rel, limit);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR,
                    known.replace("读取工作区文件失败 " + showWhenFailed + (withDetail ? ": " + e.getMessage() : "")));
        }
        return bytes.length > limit ? null : bytes;
    }

    /** 草稿预览；工作区里还没有 skill 时返回 null。预览和同步 DRAFT 行用这个：不解密沿用的 key。 */
    public DraftView draft(Listing l, String skillTypeOverride) {
        return draft(l, skillTypeOverride, PreviousKeys.NONE);
    }

    /** SKILL.md 超过预览上限的固定文案。理由写在文案里：运行时整份 SKILL.md 都进模型上下文，这么大本来就用不了。 */
    static String skillMdTooLargeMessage(long limitBytes) {
        return "SKILL.md 超过 " + limitBytes / 1024 + " KB：运行时整份 SKILL.md 都会放进模型的上下文，这么大用不了。"
                + "请把细节拆到 references/ 下的文件里，SKILL.md 只留流程和索引";
    }

    /**
     * 草稿预览；工作区里还没有 skill 时返回 null。
     *
     * <p>★ key 的值不出现在这里的任何输出里（预览会随 draft-update 事件推给前端、写进 Redis Stream，DRAFT 行同步也用它；
     * 报错文案会进异常和 ERROR 日志）：先用工作区 secrets.json 里的值把 SKILL.md 抹一遍，<b>再</b>解析——
     * 校验的文本就是将要发布的文本，报错片段里也只有 {@code {{名字}}}；secrets.json 的预览把每个 value 打码。
     *
     * <p>★ 抹值的成本有上限，所以值集合要「能确定」才抹：secrets.json 不超过 {@code previewFileMaxBytes}（<b>先看对象大小，超了不读</b>）、
     * JSON 读得出来、key 不超过 32 个、候选值不超过 64 个、每个候选值不超过 8192 个字符（见 {@link SkillSecretsFile}）。
     * 确定不了就按「读不出来」处理：<b>一律不给 skill 文件的原文</b>（SKILL.md、正文、其它文本文件都不读），校验结果只有
     * secrets.json 自己的固定文案，不回显条目、不往下逐条校验（见 {@link DraftView#isSecretsUnreadable}）。
     *
     * <p>SKILL.md 超过 {@code previewFileMaxBytes} 同理：不读、不解析、不抹值，校验结果是一句固定的话（见 {@link DraftView#isSkillMdTooLarge}）。
     * 运行时整份 SKILL.md 都会放进模型的上下文，这个大小本来就用不了，所以不是限制作者，是提前告诉他。
     *
     * @param previous 发布时传进来：上一版所有 key 的值，最先解密出来，所有报错文案、预览文字都用它们抹值（不论 secrets.json
     *                 在不在、合不合规）。预览不传。
     */
    public DraftView draft(Listing l, String skillTypeOverride, PreviousKeys previous) {
        SkillLocation loc = locateSkill(l);
        if (loc.dir() == null) return null;
        String dirPrefix = SkillWorkspaceLayout.skillDirPrefix(loc.dir());
        Map<String, ObjectInfo> files = skillFiles(l, loc.dir());
        final long limit = props.getPreviewFileMaxBytes();

        // 发布时：上一版所有 key 的值最先解密（不看 secrets.json 在不在、合不合规；解不出来、没配密钥就在这里抛，文案只带 key 名）。
        // 报错文案、预览里的文字都要用它们抹值。预览和同步传 NONE：沿用的值只在发布时于内存里解密。
        SecretValues previousValues = SecretValues.of(previous.allValues());

        List<String> errors = new ArrayList<>();
        SecretValues current = SecretValues.NONE;
        boolean unreadable = false;
        byte[] secretsJson = null;
        ObjectInfo secretsInfo = files.get(SkillBundleRules.SECRETS_JSON);
        if (secretsInfo != null) {
            // 先看列举时的大小，超了不读：读进来、解析、再数候选值，成本都是文件大小的倍数。没超也只读 limit + 1 个字节，
            // 读回来超了同样按超限处理（列举之后、读取之前，对象可能被换大）
            secretsJson = secretsInfo.size() > limit ? null
                    : readBounded(l, dirPrefix + SkillBundleRules.SECRETS_JSON, limit, SecretValues.NONE, SkillBundleRules.SECRETS_JSON, false);
            if (secretsJson == null) {
                unreadable = true;
                errors.add(SkillSecretsFile.tooLargeMessage(limit));
            } else {
                SkillSecretsFile.Parsed parsed = SkillSecretsFile.parse(secretsJson, previousValues);
                errors.addAll(parsed.errors());   // 文案在 parse 里已经抹过值（文件里的 + 上一版的）
                if (parsed.readable()) {
                    current = parsed.values();
                } else {
                    unreadable = true;
                }
            }
        }
        final SecretValues known = current.plus(previousValues);   // 抹值用的全集：文件里的值 + 上一版的全部值

        boolean skillMdTooLarge = files.get(SkillBundleRules.SKILL_MD).size() > limit;   // 列举时就超了：不读
        byte[] skillMdBytes = null;
        if (!skillMdTooLarge && !unreadable) {
            skillMdBytes = readBounded(l, dirPrefix + SkillBundleRules.SKILL_MD, limit, known, dirPrefix + SkillBundleRules.SKILL_MD, true);
            skillMdTooLarge = skillMdBytes == null;   // 读的时候才发现超了（列举之后被换大）
        }
        String skillMd = "";
        ParsedSkill fm = null;
        if (skillMdTooLarge) {
            errors.add(skillMdTooLargeMessage(limit));
        } else if (unreadable) {
            // 不知道哪些是 key 的值：只报 secrets.json 自己的固定文案；SKILL.md 连读都不读，也不给任何文件的原文
            errors.add("SKILL.md 等 secrets.json 修好之后再校验：现在不知道哪些是 key 的值，校验的报错里可能带出原文");
        } else {
            skillMd = known.replace(new String(skillMdBytes, StandardCharsets.UTF_8));
            fm = SkillManifestParser.parse(skillMd, SkillManifestParser.Mode.STRICT);
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
        // 没解析的时候 name / description 是 null、skillMd / body 是空串（前端要用 skillMd 和 body 的长度切 frontmatter）
        v.setName(fm == null ? null : fm.name());
        v.setDescription(fm == null ? null : fm.description());
        v.setSkillMd(skillMd);
        v.setBody(fm == null ? "" : fm.body());
        String inferred = SkillBundleRules.inferSkillType(files.keySet());
        v.setInferredType(inferred);
        v.setEffectiveType(skillTypeOverride != null ? skillTypeOverride : inferred);
        v.setValidationErrors(errors);
        v.setIterations(iterationCount(l, loc.dir()));
        v.setOtherSkillDirs(loc.others());
        v.setSecretsUnreadable(unreadable);
        v.setSkillMdTooLarge(skillMdTooLarge);

        List<DraftFile> out = new ArrayList<>();
        for (Map.Entry<String, ObjectInfo> entry : files.entrySet()) {
            String path = entry.getKey();
            ObjectInfo o = entry.getValue();
            if (!SkillBundleRules.isPartOfSkill(path)) continue;   // 缓存 / 依赖不进预览
            DraftFile f = new DraftFile();
            f.setPath(path);
            f.setSize(o.size());
            f.setRuntime(SkillBundleRules.isRuntimeFile(path));
            // 读不出来 / 超过上限的文件：不读，text 为 null（unreadable 时所有文件都不读——不知道哪些是值，原文一个字都不给）
            boolean skip = unreadable || o.size() > limit || (skillMdTooLarge && SkillBundleRules.SKILL_MD.equals(path));
            if (skip) {
                f.setBinary(false);
            } else if (SkillBundleRules.isSecretsFile(path)) {
                f.setText(SkillSecretsFile.maskedPreview(secretsJson));
                f.setBinary(false);
            } else {
                // SKILL.md 上面已经有界读过一遍，不再读第二次；别的文件读的时候才算数，超了和「过大」一样不给文本
                byte[] bytes = SkillBundleRules.SKILL_MD.equals(path) ? skillMdBytes
                        : readBounded(l, dirPrefix + path, limit, known, dirPrefix + path, true);
                if (bytes == null) {
                    f.setBinary(false);
                } else {
                    String text = decodeText(bytes);
                    f.setText(text == null ? null : known.replace(text));
                    f.setBinary(text == null);
                }
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
