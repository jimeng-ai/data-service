package com.jimeng.common.core.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.internal.sse.RealEventSource;
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

    public Object post(String url, Map<String, String> header, Map<String, Object> params, Map<String, Object> body) {
        MediaType mediaType = MediaType.parse("application/json");
        String bodyStr = JSONUtil.toJsonStr(body);
        String paramsStr = JSONUtil.toJsonStr(params);
        RequestBody requestBody = RequestBody.create(mediaType, bodyStr);

        StringBuffer urlBuilder = new StringBuffer(url);
        if (!params.isEmpty()) {
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

        Request.Builder post = new Request.Builder()
                .url(url)
                .method("POST", requestBody);
        // 遍历map
        if (header != null) {
            for (Map.Entry<String, String> entry : header.entrySet()) {
                post.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = post.build();
        Response response = null;
        try {
            log.info("发送http:{} -> 请求体：{}  请求参数：{}", url, bodyStr, paramsStr);
            response = okHttpClient.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Object get(String url, Map<String, String> header, Map<String, Object> params) {
        StringBuffer urlBuilder = new StringBuffer(url);
        if (!params.isEmpty()) {
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
        Request.Builder get = new Request.Builder()
                .url(String.valueOf(urlBuilder));
        // 遍历map
        if (header != null) {
            for (Map.Entry<String, String> entry : header.entrySet()) {
                get.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = get.build();
        Response response = null;
        try {
            log.info("发送http:{} -> 请求参数：{}", String.valueOf(urlBuilder), JSONUtil.toJsonStr(params));
            response = okHttpClient.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void postStream(String url, Map<String, String> header, String requestBody, EventSourceListener eventSourceListener) {
        log.info("发送流式http请求: {} -> {}", url, requestBody);
        final Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(url);
        if (header != null && header.size() > 0) {
            header.forEach(requestBuilder::header);
        }
        Request request = requestBuilder.post(RequestBody.create(MediaType.get("application/json"), requestBody)).build();
        RealEventSource realEventSource = new RealEventSource(request, eventSourceListener);
        realEventSource.connect(okHttpClient);
    }

}
