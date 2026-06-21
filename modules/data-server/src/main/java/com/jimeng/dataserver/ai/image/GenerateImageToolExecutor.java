package com.jimeng.dataserver.ai.image;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.BizTypeContext;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话内置工具 generate_image：把模型发出的文生图调用路由到 ImageGenClient（复用
 * agent.sandbox.image-gen 配置），生成图片落 MinIO，返回长期可访问 URL 供前端图片卡片渲染。
 * 由 SkillToolExecutorRegistryService 自动 Spring 注入收集；traceStepType=TOOL_CALL 由注册中心自动埋点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateImageToolExecutor implements SkillToolExecutor {

    private static final String TOOL = "generate_image";
    private static final int PRESIGN_EXPIRY_SEC = 7 * 24 * 3600; // MinIO presigned 上限 7 天

    private final ImageGenClient imageGenClient;
    private final RagMinioStorageService minio;
    private final AiModelCallRecordService recordService;
    private final AgentSandboxProperties props;

    @Override
    public boolean supports(String toolName) {
        return TOOL.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        Object p = input == null ? null : input.get("prompt");
        String prompt = p instanceof String s ? s : null;
        if (StrUtil.isBlank(prompt)) {
            throw new IllegalArgumentException("generate_image 需要 prompt(string)");
        }
        int count = input.get("count") instanceof Number n ? Math.max(1, Math.min(4, n.intValue())) : 1;
        String size = input.get("size") instanceof String s ? s : "1024x1024";

        long start = System.currentTimeMillis();
        try {
            List<byte[]> images = imageGenClient.generate(prompt, size, count);
            List<String> urls = new ArrayList<>(images.size());
            for (byte[] img : images) {
                String ext = sniffExt(img);
                String objectName = minio.uploadBytes(img, "genimg-" + IdUtil.fastSimpleUUID() + ext, contentType(ext));
                urls.add(minio.presignedUrl(objectName, PRESIGN_EXPIRY_SEC));
            }
            recordBilling(size, urls.size(), 200, (int) (System.currentTimeMillis() - start));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("urls", urls);
            out.put("model", props.getImageGen().getModel());
            out.put("size", size);
            out.put("count", urls.size());
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            recordBilling(size, 0, 500, (int) (System.currentTimeMillis() - start));
            log.warn("生图工具执行失败 prompt={} err={}", StrUtil.maxLength(prompt, 80), e.getMessage());
            throw new RuntimeException("生图失败: " + e.getMessage(), e);
        }
    }

    private void recordBilling(String size, int imageCount, int httpStatus, int latencyMs) {
        try {
            AgentSandboxProperties.ImageGen ig = props.getImageGen();
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("biz_type", BizTypeContext.IMAGE_GEN);
            note.put("image_count", imageCount);
            note.put("size", size);
            recordService.recordComputedCall(
                    StrUtil.blankToDefault(ig.getProvider(), "image"),
                    "image:generate",
                    ig.getModel(),
                    BizTypeContext.IMAGE_GEN,
                    new NormalizedUsage(),   // 生图无 token 用量
                    httpStatus,
                    latencyMs,
                    note);
        } catch (Exception e) {
            log.warn("生图计费记录失败: {}", e.getMessage());
        }
    }

    private static String sniffExt(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return ".jpg";
        }
        return ".png";
    }

    private static String contentType(String ext) {
        return ".jpg".equals(ext) ? "image/jpeg" : "image/png";
    }
}
