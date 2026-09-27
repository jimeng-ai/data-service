package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.AiSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBundleResolver {
    private final RagMinioStorageService minio;

    public List<SidecarRunPayload.SkillRef> resolve(List<AiSkill> doerSkills) {
        List<SidecarRunPayload.SkillRef> out = new ArrayList<>();
        for (AiSkill s : doerSkills) {
            if (s.getBundleKey() == null) continue;
            try {
                List<String> objects = minio.listObjects(s.getBundleKey());
                out.add(toSkillRef(s.getName(), s.getBundleKey(), minio.getBucket(), objects));
            } catch (Exception e) {
                log.warn("列 skill bundle 失败 name={} key={} err={}", s.getName(), s.getBundleKey(), e.getMessage());
            }
        }
        return out;
    }

    public static SidecarRunPayload.SkillRef toSkillRef(String name, String prefix, String bucket, List<String> objects) {
        SidecarRunPayload.SkillRef ref = new SidecarRunPayload.SkillRef();
        ref.setName(name);
        List<SidecarRunPayload.SkillFile> files = new ArrayList<>();
        for (String obj : objects) {
            String rel = obj.startsWith(prefix) ? obj.substring(prefix.length()) : obj;
            // 根目录 evals/（测试用例 + 断言）与缓存文件不下发：下发了，评测时被测模型就能读到答案，
            // 生产运行里则是每一次都白发一份。规则见 SkillBundleRules，与 skill-creator package_skill.py 同源。
            if (!SkillBundleRules.isRuntimeFile(rel)) continue;
            SidecarRunPayload.SkillFile f = new SidecarRunPayload.SkillFile();
            f.setObjectName(obj);
            f.setRelPath(rel);
            f.setBucket(bucket);
            files.add(f);
        }
        ref.setFiles(files);
        return ref;
    }
}
