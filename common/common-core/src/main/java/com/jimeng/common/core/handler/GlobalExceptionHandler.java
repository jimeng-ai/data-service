package com.jimeng.common.core.handler;

import com.jimeng.common.core.entity.common.CommonResponse;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * @Author Moonlight
 * @Description 全局异常处理器
 * @Date 2024/8/4 13:27
 */

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ====================== 业务异常 ======================

    @ExceptionHandler(ServiceException.class)
    public CommonResponse.Resp handleServiceException(ServiceException ex) {
        return build(ex.getRespCode(), ex.getRespMsg());
    }

    // ====================== 文件上传 ======================

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public CommonResponse.Resp handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        long maxBytes = ex.getMaxUploadSize();
        String maxHuman = maxBytes > 0 ? humanReadable(maxBytes) : "服务端配置上限";
        log.warn("文件上传超限: maxBytes={}, ex={}", maxBytes, ex.getMessage());
        return build(ExceptionCode.INVALID_REQUEST,
                "上传文件超过大小限制（" + maxHuman + "），请压缩或拆分后再试。" +
                        "如需放宽限制，请联系管理员调整 spring.servlet.multipart.max-file-size / max-request-size。");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public CommonResponse.Resp handleMissingPart(MissingServletRequestPartException ex) {
        log.warn("缺少 multipart 字段: {}", ex.getMessage());
        return build(ExceptionCode.INVALID_REQUEST, "缺少必填的表单字段：" + ex.getRequestPartName());
    }

    // ====================== 参数校验 / 绑定 ======================

    /** @RequestBody @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResponse.Resp handleMethodArgNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("请求体参数校验失败: {}", detail);
        return build(ExceptionCode.BODY_NOT_MATCH, "请求体参数校验失败：" + detail);
    }

    /** 表单 / @ModelAttribute @Valid 校验失败 */
    @ExceptionHandler(BindException.class)
    public CommonResponse.Resp handleBind(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("表单参数绑定/校验失败: {}", detail);
        return build(ExceptionCode.BODY_NOT_MATCH, "表单参数校验失败：" + detail);
    }

    /** @Validated 在 Controller 方法的 PathVariable/RequestParam 上失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public CommonResponse.Resp handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::formatViolation)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败: {}", detail);
        return build(ExceptionCode.INVALID_REQUEST, "参数校验失败：" + detail);
    }

    /** 缺少必填 @RequestParam */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public CommonResponse.Resp handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数: {}", ex.getMessage());
        return build(ExceptionCode.INVALID_REQUEST,
                "缺少必填参数：" + ex.getParameterName() + "（类型：" + ex.getParameterType() + "）");
    }

    /** PathVariable / RequestParam 类型不匹配（例如把字母传给 Long） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public CommonResponse.Resp handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null ? "?" : ex.getRequiredType().getSimpleName();
        log.warn("参数类型不匹配 name={} value={} required={}", ex.getName(), ex.getValue(), expected);
        return build(ExceptionCode.BODY_NOT_MATCH,
                "参数 [" + ex.getName() + "] 类型错误，期望 " + expected + "，实际值：" + ex.getValue());
    }

    // ====================== 请求格式 / 路由 ======================

    /** 请求体 JSON 无法解析 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public CommonResponse.Resp handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return build(ExceptionCode.JSON_PARSE_ERROR, "请求体格式错误，无法解析（请检查 JSON 是否合法）");
    }

    /** 方法不支持（例如对一个 GET 接口发 POST） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public CommonResponse.Resp handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP 方法不支持: {}", ex.getMessage());
        return build(ExceptionCode.OPERATION_UNSUPPORTED,
                "该接口不支持 " + ex.getMethod() + " 方法，请使用 " +
                        (ex.getSupportedHttpMethods() == null ? "正确方法" : ex.getSupportedHttpMethods().toString()));
    }

    /** Content-Type 不支持 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public CommonResponse.Resp handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Content-Type 不支持: {}", ex.getMessage());
        return build(ExceptionCode.BODY_NOT_MATCH,
                "不支持的 Content-Type：" + ex.getContentType() +
                        "，支持的类型：" + ex.getSupportedMediaTypes());
    }

    /** 路由没匹配上（需要在 yml 配置 spring.mvc.throw-exception-if-no-handler-found=true 才会触发） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public CommonResponse.Resp handleNoHandler(NoHandlerFoundException ex) {
        log.warn("接口不存在: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return build(ExceptionCode.NOT_FOUND, "接口不存在：" + ex.getHttpMethod() + " " + ex.getRequestURL());
    }

    // ====================== 兜底 ======================

    /** 兜底处理：避免未识别异常把堆栈直接抛给前端 */
    @ExceptionHandler(Exception.class)
    public CommonResponse.Resp handleAny(Exception ex) {
        log.error("未处理的异常", ex);
        return build(ExceptionCode.INTERNAL_SERVER_ERROR,
                "服务器内部错误：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
    }

    // ====================== 工具方法 ======================

    private static CommonResponse.Resp build(ExceptionCode code, String msg) {
        return build(code.getResultCode(), msg);
    }

    private static CommonResponse.Resp build(String code, String msg) {
        return CommonResponse.Resp.newBuilder()
                .setRespCode(code)
                .setRespMsg(msg)
                .setSuccess(Boolean.FALSE)
                .build();
    }

    private static String formatFieldError(FieldError fe) {
        return fe.getField() + " " + (fe.getDefaultMessage() == null ? "非法" : fe.getDefaultMessage());
    }

    private static String formatViolation(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
        return path + " " + v.getMessage();
    }

    private static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

}
