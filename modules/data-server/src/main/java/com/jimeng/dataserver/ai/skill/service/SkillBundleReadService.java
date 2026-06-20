package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.controller.dto.SkillFileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 读取 DOER skill 的 MinIO bundle 文件内容，供管理页详情查看脚本。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBundleReadService {

    /** 单文件内容上限：脚本通常很小，超过则截断展示。 */
    private static final int MAX_BYTES_PER_FILE = 512 * 1024;
    /** 根 SKILL.md 已由详情 body 渲染，列文件时跳过避免重复。 */
    private static final String ROOT_SKILL_MD = "SKILL.md";

    private final RagMinioStorageService minio;

    /** 返回 bundle 下的文本文件列表（按相对路径排序）；bundleKey 为空或读失败返回空列表，不抛错。 */
    public List<SkillFileView> read(String bundleKey) {
        List<SkillFileView> files = new ArrayList<>();
        if (bundleKey == null || bundleKey.isBlank()) return files;
        List<String> objects;
        try {
            objects = minio.listObjects(bundleKey);
        } catch (Exception e) {
            log.warn("列 skill bundle 失败 key={} err={}", bundleKey, e.getMessage());
            return files;
        }
        for (String obj : objects) {
            String rel = obj.startsWith(bundleKey) ? obj.substring(bundleKey.length()) : obj;
            if (ROOT_SKILL_MD.equals(rel)) continue;
            try {
                files.add(readOne(obj, rel));
            } catch (Exception e) {
                log.warn("读 skill bundle 文件失败 obj={} err={}", obj, e.getMessage());
            }
        }
        files.sort(Comparator.comparing(SkillFileView::getPath));
        return files;
    }

    private SkillFileView readOne(String objectName, String relPath) throws Exception {
        SkillFileView f = new SkillFileView();
        f.setPath(relPath);
        try (InputStream is = minio.download(objectName)) {
            byte[] bytes = is.readNBytes(MAX_BYTES_PER_FILE + 1);
            boolean truncated = bytes.length > MAX_BYTES_PER_FILE;
            if (truncated) {
                byte[] capped = new byte[MAX_BYTES_PER_FILE];
                System.arraycopy(bytes, 0, capped, 0, MAX_BYTES_PER_FILE);
                bytes = capped;
            }
            f.setSize(bytes.length);
            f.setTruncated(truncated);
            if (isBinary(bytes)) {
                f.setBinary(true);
                f.setContent(null);
            } else {
                f.setBinary(false);
                f.setContent(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return f;
    }

    /** 含 NUL 字节即判为二进制（脚本/文本不含 NUL，图片等含）。 */
    private static boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) return true;
        }
        return false;
    }
}
