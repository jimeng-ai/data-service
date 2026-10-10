package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.imports.GithubTarballFetcher;
import com.jimeng.dataserver.ai.skill.imports.SkillBundle;
import com.jimeng.dataserver.ai.skill.imports.SkillBundleExtractor;
import com.jimeng.dataserver.ai.skill.imports.SkillClassifier;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import com.jimeng.dataserver.ai.skill.manifest.SkillPackageProcessor;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SkillImportService {
    private final GithubTarballFetcher fetcher;
    private final SkillBundleExtractor extractor;
    private final AiSkillMapper aiSkillMapper;
    private final AiSkillRegistryService registry;
    private final RagMinioStorageService minio;
    private final SkillSecretService secretService;

    @Transactional
    public AiSkill importFromGithub(String owner, String repo, String ref, String subPath,
                                    String tenantId, Long ownerUserId) {
        byte[] tarGz = fetcher.fetch(owner, repo, ref);
        SkillBundle bundle = extractor.extract(tarGz, subPath);
        // 与构建器发布同一套处理（设计文档 §3.4）：仓库里万一带了 secrets.json，key 同样被提取加密、原文换成占位符。
        Map<String, byte[]> all = new LinkedHashMap<>(bundle.files());
        all.put(SkillBundleRules.SKILL_MD, bundle.skillMarkdown().getBytes(StandardCharsets.UTF_8));
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(all, SkillManifestParser.Mode.LENIENT, true);
        Map<String, byte[]> others = new LinkedHashMap<>(out.files());
        byte[] skillMd = others.remove(SkillBundleRules.SKILL_MD);
        SkillBundle processed = new SkillBundle(new String(skillMd, StandardCharsets.UTF_8), others);
        String type = SkillClassifier.classify(processed);
        List<SkillSecret> secretRows = secretService.prepare(tenantId, null, null, out.secrets());

        AiSkill s = new AiSkill();
        s.setTenantId(tenantId);
        s.setOwnerUserId(ownerUserId);
        s.setScope(SkillConst.SCOPE_PRIVATE);
        s.setName(out.skill().name());
        s.setDescription(out.skill().description());
        s.setBody(out.skill().body());
        s.setSkillType(type);
        s.setSource(SkillConst.SOURCE_MARKET);
        s.setOriginRef(owner + "/" + repo + "@" + ref + ":" + (subPath == null ? "" : subPath));
        s.setStatus(SkillConst.STATUS_ACTIVE);
        s.setVersion(1);
        s.setManifest(out.manifestJson());
        aiSkillMapper.insert(s);

        if (SkillConst.TYPE_DOER.equals(type)) {
            String prefix = "skills/" + s.getId() + "/1/";
            storeBundle(prefix, processed);
            s.setBundleKey(prefix);
            s.setBundleHash(sha256Hex(concat(processed)));
            aiSkillMapper.updateById(s);
        }
        secretService.save(s.getId(), 1, secretRows);
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
            // 只记异常类名：存储异常的 message 带着对象名，ServiceException 构造时就进 ERROR 日志、也回给调用方
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "存储 skill bundle 失败（" + ex.getClass().getSimpleName() + "）");
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
