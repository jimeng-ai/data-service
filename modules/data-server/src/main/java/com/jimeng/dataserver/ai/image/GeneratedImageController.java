package com.jimeng.dataserver.ai.image;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * 对话内置工具 generate_image 生成图片的鉴权流式回传端点。
 *
 * <p><b>背景</b>：原先 generate_image 直接把 MinIO presigned URL 返回给前端，浏览器在生产环境无法访问
 * 内网 MinIO 端点（如 http://minio:9000）→ 图片加载失败；且 presigned 7 天过期，历史图也会失效。
 * 改为经网关鉴权的后端流式端点（对齐 {@code AgentFileController}「不暴露裸 MinIO / presigned URL」），
 * 前端带鉴权头 fetch 取 blob 渲染，永不过期、内网可达。
 *
 * <p><b>访问控制</b>：经网关 JWT 鉴权（非白名单，匿名访问被网关拦截）；objectName 为不可枚举的随机 UUID
 * （{@code genimg-<uuid>.png}），且只出现在属主自己的会话/历史里；严格限定 {@code genimg-} 前缀，杜绝借此
 * 端点把 RAG 知识库里的其它对象（文档/切片）读出来。安全等级不低于原 presigned，且额外要求登录。
 */
@Slf4j
@Tag(name = "对话生图-回传", description = "generate_image 生成图片的鉴权流式回传")
@RestController
@RequestMapping("/data/ai/image")
@RequiredArgsConstructor
public class GeneratedImageController {

    /** 仅允许回传 generate_image 产出的对象（genimg-<hex>.png/jpg），防止越权读取 RAG 其它对象。 */
    private static final Pattern SAFE_NAME = Pattern.compile("^genimg-[0-9a-fA-F]{1,64}\\.(png|jpe?g)$");

    private final RagMinioStorageService storage;

    @Operation(summary = "回传生成图片",
            description = "按对象名（genimg- 前缀，网关鉴权）从 MinIO 流式回传，inline 供前端 blob 预览。")
    @GetMapping("/{name:.+}")
    public void get(@PathVariable String name, HttpServletResponse response) throws Exception {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "图片不存在");
        }
        InputStream in;
        try {
            in = storage.download(name);
        } catch (Exception e) {
            // 对象不存在/已删除：统一 404，不向调用方泄露 MinIO 内部错误。
            log.debug("生成图片回传未命中 object={} err={}", name, e.getMessage());
            throw new ServiceException(ExceptionCode.NOT_FOUND, "图片不存在");
        }
        response.setContentType(name.toLowerCase().endsWith(".png")
                ? MediaType.IMAGE_PNG_VALUE : MediaType.IMAGE_JPEG_VALUE);
        response.setHeader("Content-Disposition", "inline; filename=\"" + name + "\"");
        // 必须 void+OutputStream 写出：非 void 返回会被 GlobalResponseHandler 包成 JSON，前端 blob 损坏
        // （见 jm-binary-download-response-wrapping 记忆，与 AgentFileController 同款约定）。
        try (InputStream is = in; OutputStream out = response.getOutputStream()) {
            is.transferTo(out);
            out.flush();
        }
    }
}
