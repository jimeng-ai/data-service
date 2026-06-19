package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.imports.GithubTarballFetcher;
import com.jimeng.dataserver.ai.skill.imports.SkillBundle;
import com.jimeng.dataserver.ai.skill.imports.SkillBundleExtractor;
import com.jimeng.dataserver.ai.skill.imports.SkillClassifier;
import com.jimeng.dataserver.ai.skill.util.SkillMarkdownParser;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SkillImportService {
    private final GithubTarballFetcher fetcher;
    private final SkillBundleExtractor extractor;
    private final AiSkillMapper aiSkillMapper;
    private final AiSkillRegistryService registry;
    private final RagMinioStorageService minio;

    @Transactional
    public AiSkill importFromGithub(String owner, String repo, String ref, String subPath,
                                    String tenantId, Long ownerUserId) {
        byte[] tarGz = fetcher.fetch(owner, repo, ref);
        SkillBundle bundle = extractor.extract(tarGz, subPath);
        SkillMarkdownParser.ParsedSkill p = SkillMarkdownParser.parse(bundle.skillMarkdown());
        SkillMarkdownParser.validate(p);
        String type = SkillClassifier.classify(bundle);

        AiSkill s = new AiSkill();
        s.setTenantId(tenantId);
        s.setOwnerUserId(ownerUserId);
        s.setScope(SkillConst.SCOPE_PRIVATE);
        s.setName(p.name());
        s.setDescription(p.description());
        s.setBody(p.body());
        s.setSkillType(type);
        s.setSource(SkillConst.SOURCE_MARKET);
        s.setOriginRef(owner + "/" + repo + "@" + ref + ":" + (subPath == null ? "" : subPath));
        s.setStatus(SkillConst.STATUS_ACTIVE);
        s.setVersion(1);
        aiSkillMapper.insert(s);

        if (SkillConst.TYPE_DOER.equals(type)) {
            String prefix = "skills/" + s.getId() + "/1/";
            storeBundle(prefix, bundle);
            s.setBundleKey(prefix);
            s.setBundleHash(sha256Hex(concat(bundle)));
            aiSkillMapper.updateById(s);
        }
        registry.reloadAndBroadcast();
        return s;
    }

    private void storeBundle(String prefix, SkillBundle bundle) {
        try {
            minio.putObject(prefix + "SKILL.md",
                    bundle.skillMarkdown().getBytes(StandardCharsets.UTF_8), "text/markdown");
            for (Map.Entry<String, byte[]> e : bundle.files().entrySet()) {
                minio.putObject(prefix + e.getKey(), e.getValue(), "application/octet-stream");
            }
        } catch (Exception ex) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "存储 skill bundle 失败: " + ex.getMessage());
        }
    }

    private static byte[] concat(SkillBundle bundle) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            bos.write(bundle.skillMarkdown().getBytes(StandardCharsets.UTF_8));
            for (byte[] v : bundle.files().values()) bos.write(v);
        } catch (Exception ignored) {}
        return bos.toByteArray();
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
