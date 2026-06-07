package com.jimeng.common.core.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.internal.sse.RealEventSource;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * @Author Moonlight
 * @Description 请求服务
 * @Date 2024/9/14 22:40
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class RequestService {

    private final OkHttpClient okHttpClient;

    public HttpResp post(String url, Map<String, String> header, Map<String, Object> params, Map<String, Object> body) {
        MediaType mediaType = MediaType.parse("application/json");
        String bodyStr = JSONUtil.toJsonStr(body);
        String paramsStr = JSONUtil.toJsonStr(params);
        RequestBody requestBody = RequestBody.create(mediaType, bodyStr);
        String requestUrl = buildUrl(url, params);

        Request.Builder post = new Request.Builder()
                .url(requestUrl)
                .method("POST", requestBody);
        addHeaders(post, header);
        Request request = post.build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String respBody = response.body() == null ? null : response.body().string();
            log.info("发送http:{} -> 请求体：{}  请求参数：{} 响应码：{}", requestUrl, bodyStr, paramsStr, response.code());
            return new HttpResp(response.code(), respBody);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public HttpResp get(String url, Map<String, String> header, Map<String, Object> params) {
        String requestUrl = buildUrl(url, params);
        Request.Builder get = new Request.Builder()
                .url(requestUrl);
        addHeaders(get, header);
        Request request = get.build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String respBody = response.body() == null ? null : response.body().string();
            log.info("发送http:{} -> 请求参数：{} 响应码：{}", requestUrl, JSONUtil.toJsonStr(params), response.code());
            return new HttpResp(response.code(), respBody);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 发起一条 SSE 流式请求，返回底层 {@link EventSource} 句柄。
     *
     * <p>返回句柄供编排层在「用户点停止 / 取消」时 {@link EventSource#cancel()} 真正中断上游
     * LLM 调用（对话）或关闭到沙箱边车的上游请求（沙箱据此 docker-kill）。历史调用方忽略返回值即可，
     * 行为不变。
     */
    public EventSource postStream(String url, Map<String, String> header, String requestBody, EventSourceListener eventSourceListener) {
        log.info("发送流式http请求: {} -> {}", url, requestBody);
        final Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(url);
        if (header != null && header.size() > 0) {
            header.forEach(requestBuilder::header);
        }
        Request request = requestBuilder.post(RequestBody.create(MediaType.get("application/json"), requestBody)).build();
        RealEventSource realEventSource = new RealEventSource(request, eventSourceListener);
        realEventSource.connect(okHttpClient);
        return realEventSource;
    }

    private String buildUrl(String url, Map<String, Object> params) {
        StringBuilder urlBuilder = new StringBuilder(url);
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            int index = 0;
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                if (index != params.size() - 1) {
                    urlBuilder.append("&");
                }
                index++;
            }
        }
        return urlBuilder.toString();
    }

    private void addHeaders(Request.Builder requestBuilder, Map<String, String> header) {
        if (header == null || header.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : header.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }
    }

    public static class HttpResp {
        private final Integer statusCode;
        private final String body;

        public HttpResp(Integer statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public Integer getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }

}
