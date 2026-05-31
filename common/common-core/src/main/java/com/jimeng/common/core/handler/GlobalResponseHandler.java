package com.jimeng.common.core.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.entity.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * @Author Moonlight
 * @Description 全局响应处理器，自动包装返回结果为 CommonResponse.Resp
 * @Date 2025/10/25 17:30
 */

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice(basePackages = "com.jimeng")
public class GlobalResponseHandler implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> t = returnType.getParameterType();
        // 如果返回类型已经是 CommonResponse.Resp，则不需要包装
        if (CommonResponse.Resp.class.equals(t)) {
            return false;
        }
        // 二进制 / 流式返回（文件下载等）不可被 JSON 包装，否则会破坏字节流。
        // 注意：SseEmitter 走 ResponseBodyEmitterReturnValueHandler，本就不经过此 advice。
        if (byte[].class.equals(t)
                || org.springframework.core.io.Resource.class.isAssignableFrom(t)
                || org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class.isAssignableFrom(t)) {
            return false;
        }
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                   Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                   ServerHttpRequest request, ServerHttpResponse response) {

        // 如果 body 已经是 CommonResponse.Resp，直接返回
        if (body instanceof CommonResponse.Resp) {
            return body;
        }

        // 构建统一响应格式
        CommonResponse.Resp resp = CommonResponse.Resp.newBuilder()
                .setSuccess(Boolean.TRUE)
                .setRespCode("200")
                .setRespMsg("操作成功")
                .setData(body)
                .build();

        // 处理 String 类型返回值的特殊情况
        // 因为 StringHttpMessageConverter 会将对象转换为字符串，需要手动序列化
        if (body instanceof String) {
            try {
                return objectMapper.writeValueAsString(resp);
            } catch (JsonProcessingException e) {
                log.error("序列化响应失败", e);
                throw new RuntimeException("序列化响应失败", e);
            }
        }

        return resp;
    }
}
