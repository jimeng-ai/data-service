package com.jimeng.dataserver.ai.skill.service;

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
            SidecarRunPayload.SkillFile f = new SidecarRunPayload.SkillFile();
            f.setObjectName(obj);
            f.setRelPath(obj.startsWith(prefix) ? obj.substring(prefix.length()) : obj);
            f.setBucket(bucket);
            files.add(f);
        }
        ref.setFiles(files);
        return ref;
    }
}
