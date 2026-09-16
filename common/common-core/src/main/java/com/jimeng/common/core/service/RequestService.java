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
import java.time.Duration;
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

    /**
     * 单次超时重载里 callTimeout 比 readTimeout 多给的余量。
     *
     * <p>readTimeout 管的是「两次读之间最长等多久」，非流式 LLM 调用里它实际等的是<b>响应头</b>——
     * 上游要把整条生成完才回头。callTimeout 管整通调用（建连 + 写请求体 + 等响应头 + 读完响应体），
     * 所以它必须比 readTimeout 大一截，否则会抢在 readTimeout 之前把一次本来来得及的调用掐掉。
     * 60 秒覆盖建连、写一份几十 KB 的请求体、以及响应头之后读完响应体。
     */
    public static final Duration CALL_TIMEOUT_GRACE = Duration.ofSeconds(60);

    private final OkHttpClient okHttpClient;

    public HttpResp post(String url, Map<String, String> header, Map<String, Object> params, Map<String, Object> body) {
        return post(url, header, params, body, null);
    }

    /**
     * 同 {@link #post(String, Map, Map, Map)}，但可以给<b>这一次</b>调用单独指定读超时。
     *
     * <h3>为什么要有它</h3>
     * {@code @Primary} 的 OkHttpClient 读超时来自 Nacos {@code okhttp.read-timeout}，是按「交互式对话」调的。
     * 非流式调用要等上游整条生成完才回响应头，一次大输出的平台内部调用（语义层推导几十张表）必然超过它，
     * 表现是 {@code SocketTimeoutException}；而把全局值调大会让所有 LLM 调用挂死时都多等那么久。
     * 所以超时按调用给，不动全局。
     *
     * <h3>为什么用 newBuilder 而不是另建一个客户端</h3>
     * {@code okHttpClient.newBuilder()} 派生出的客户端<b>共享</b>原客户端的连接池与 Dispatcher
     * （{@code OkHttpConfig} 专门调大过的 maxRequestsPerHost 仍然生效），只是这一次的超时不同；
     * 原客户端本身不被修改，其它调用方看不到任何变化。
     *
     * <p>同时补一个 callTimeout（= readTimeout + {@link #CALL_TIMEOUT_GRACE}）：readTimeout 只限「单次读之间」，
     * 上游若每隔一会儿吐几个字节就能无限拖下去；调用方要的是「这次调用最多占我多久」这个硬上限。
     *
     * @param readTimeout 为 null 时与 4 参版本行为完全一致（用共享客户端的全局超时）；非 null 必须为正数
     */
    public HttpResp post(String url, Map<String, String> header, Map<String, Object> params, Map<String, Object> body,
                         Duration readTimeout) {
        if (readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero())) {
            // OkHttp 里 0 表示「不限时」，负数直接抛——两者都不是调用方想要的「给这一次单独一个上限」。
            throw new IllegalArgumentException("readTimeout 必须为正数，实际=" + readTimeout);
        }
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
        OkHttpClient client = readTimeout == null
                ? okHttpClient
                : okHttpClient.newBuilder()
                        .readTimeout(readTimeout)
                        .callTimeout(readTimeout.plus(CALL_TIMEOUT_GRACE))
                        .build();
        try (Response response = client.newCall(request).execute()) {
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
