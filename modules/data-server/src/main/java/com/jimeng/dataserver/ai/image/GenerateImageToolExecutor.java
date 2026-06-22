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
 * agent.sandbox.image-gen 配置），生成图片落 MinIO，返回<b>后端鉴权流式端点</b>的相对路径
 * （{@code /data/ai/image/<objectName>}）供前端图片卡片 fetch-blob 渲染。
 * 由 SkillToolExecutorRegistryService 自动 Spring 注入收集；traceStepType=TOOL_CALL 由注册中心自动埋点。
 *
 * <p><b>为什么不再返回 MinIO presigned URL</b>：presigned URL 内嵌 file.minio.endpoint（生产为内网
 * 服务名如 http://minio:9000），浏览器根本无法访问 → 图片加载失败；且 presigned 7 天过期，历史图也会失效。
 * 改为存为<b>扁平 key</b>（{@code genimg-<uuid>.png}，无日期斜杠前缀，便于做单段路径回传），URL 指向
 * {@link GeneratedImageController} 的鉴权端点，浏览器带鉴权头取 blob，永不过期、内网可达
 * （对齐 AgentFileController「不暴露裸 MinIO / presigned URL」）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateImageToolExecutor implements SkillToolExecutor {

    private static final String TOOL = "generate_image";
    /** 回传端点路径前缀，需与 {@link GeneratedImageController} 的 @RequestMapping 一致。 */
    private static final String IMAGE_URL_PREFIX = "/data/ai/image/";

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
                // 扁平、无斜杠的 key：UUID 不可枚举，便于做单段路径回传 + 严格 genimg- 前缀校验。
                // 用 putObject(不改写 key)而非 uploadBytes(会加 yyyy/MM/dd 斜杠前缀，破坏单段路径)。
                String objectName = "genimg-" + IdUtil.fastSimpleUUID() + ext;
                minio.putObject(objectName, img, contentType(ext));
                urls.add(IMAGE_URL_PREFIX + objectName);
            }
            recordBilling(size, urls.size(), 200, (int) (System.currentTimeMillis() - start));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("urls", urls);
            out.put("model", props.getImageGen().getModel());
            out.put("size", size);
            out.put("count", urls.size());
            return out;
        } catch (IllegalArgumentException e) {
            // 入参类错误原样抛出，不记 billing(500)、不包成"生图失败"——那是生成/上传失败才该走的路径
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
