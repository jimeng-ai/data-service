package com.jimeng.dataserver.ai.skill.eval;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.service.SkillBundleResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「SKILL.md + 文件表」写进 MinIO 前缀并构造 {@link SidecarRunPayload.SkillRef}。
 *
 * <p><b>刻意做成只依赖存储的叶子组件。</b>这段逻辑原本私有在 SkillBuilderRunService 里，
 * 评测也需要它；若让 SkillEvalService 去依赖 SkillBuilderRunService，就会牵进
 * ClaudeService → … → SkillToolExecutorRegistryService 那条链，正是当初逼出
 * SkillDraftStore（"独立叶子组件，破构造环"）的同一个环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillMaterializer {

    private final RagMinioStorageService storage;

    /**
     * @param prefix 必须以 / 结尾，例如 {@code skills/eval/{runId}/}
     * @param body   SKILL.md 正文；空则不写（此时包里只有 files）
     * @param files  相对路径 → 内容
     */
    public SidecarRunPayload.SkillRef materialize(String name, String prefix, String body, Map<String, String> files) {
        try {
            if (body != null && !body.isBlank()) {
                storage.putObject(prefix + "SKILL.md", body.getBytes(StandardCharsets.UTF_8), "text/markdown");
            }
            if (files != null) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    String rel = e.getKey();
                    if (rel == null || rel.isBlank()) continue;
                    // 防越界：去前导 / 与 ../，保证只写在前缀内。与 SkillBuilderRunService 同一套规则。
                    String safeRel = rel.replace("\\", "/").replaceAll("^/+", "").replace("../", "");
                    String content = e.getValue() == null ? "" : e.getValue();
                    storage.putObject(prefix + safeRel, content.getBytes(StandardCharsets.UTF_8), "text/plain");
                }
            }
            List<String> objects = storage.listObjects(prefix);
            return SkillBundleResolver.toSkillRef(name, prefix, storage.getBucket(), objects);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "物化 skill 到沙箱失败: " + e.getMessage());
        }
    }

    public String bucket() {
        return storage.getBucket();
    }

    /**
     * 把已发布 skill 的 bundle（MinIO 前缀下的对象）读回「相对路径 → 内容」表。
     * 与 {@link #materialize} 对称：那个把 files 写进前缀，这个从前缀读回来——评测已发布 skill 时
     * 需要它拿到 {@code evals/evals.json} 与随包文件（草稿评测走 SkillDraftStore，不经这里）。
     *
     * @param bundleKey {@code AiSkill.bundleKey}，即该 skill 文件在 MinIO 的前缀
     */
    public Map<String, String> readBundleFiles(String bundleKey) {
        Map<String, String> out = new LinkedHashMap<>();
        if (bundleKey == null || bundleKey.isBlank()) return out;
        try {
            for (String obj : storage.listObjects(bundleKey)) {
                // 相对路径 = 对象名去掉前缀（与 materialize 写入时 prefix+rel 的结构对称）。
                String rel = (obj.startsWith(bundleKey) ? obj.substring(bundleKey.length()) : obj)
                        .replaceAll("^/+", "");
                if (rel.isBlank()) continue;
                try (InputStream is = storage.download(obj)) {
                    out.put(rel, new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "读取 skill bundle 失败: " + e.getMessage());
        }
        return out;
    }
}
