package com.jimeng.dataserver.ai.skill.imports;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Component
@RequiredArgsConstructor
public class SkillBundleExtractor {
    private final SkillImportProperties props;

    public SkillBundle extract(byte[] tarGz, String subPath) {
        String normSub = subPath == null ? "" : subPath.replaceAll("^/+|/+$", "");
        Map<String, byte[]> files = new LinkedHashMap<>();
        String skillMd = null;
        long total = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(tarGz)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.isSymbolicLink() || entry.isLink())
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "bundle 含软/硬链接，已拒绝");
                String name = entry.getName();
                if (name.contains("..")) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "bundle 含非法路径: " + name);
                int slash = name.indexOf('/');
                String afterTop = slash >= 0 ? name.substring(slash + 1) : name;
                String rel;
                if (normSub.isEmpty()) rel = afterTop;
                else if (afterTop.equals(normSub) || afterTop.startsWith(normSub + "/"))
                    rel = afterTop.substring(normSub.length()).replaceAll("^/+", "");
                else continue;
                if (rel.isEmpty()) continue;
                byte[] data = readAll(tar);
                total += data.length;
                if (total > props.getMaxTotalBytes()) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "bundle 超出大小上限");
                if ("SKILL.md".equals(rel)) skillMd = new String(data, StandardCharsets.UTF_8);
                else {
                    files.put(rel, data);
                    if (files.size() > props.getMaxFiles()) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "bundle 文件数超出上限");
                }
            }
        } catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException(ExceptionCode.INVALID_REQUEST, "解包失败: " + e.getMessage()); }
        if (skillMd == null) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "目标路径下未找到 SKILL.md");
        return new SkillBundle(skillMd, files);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
