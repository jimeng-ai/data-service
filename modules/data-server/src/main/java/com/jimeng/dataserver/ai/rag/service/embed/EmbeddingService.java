package com.jimeng.dataserver.ai.rag.service.embed;

import cn.hutool.crypto.SecureUtil;
import com.jimeng.dataserver.ai.rag.client.OpenRouterEmbeddingClient;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 批量 embedding，按 contextualized_content 的 sha256 做 Redis 缓存（避免重复计费）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final int BATCH_SIZE = 100;
    private static final String CACHE_PREFIX = "rag:emb:";
    private static final long CACHE_TTL_DAYS = 30;

    private final OpenRouterEmbeddingClient client;
    private final RagProperties ragProperties;
    private final StringRedisTemplate redis;

    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        int dims = ragProperties.getOpenrouter().getEmbeddingDims();
        String model = ragProperties.getOpenrouter().getEmbeddingModel();

        float[][] result = new float[texts.size()][];
        List<Integer> missingIdx = new ArrayList<>();
        List<String> missingText = new ArrayList<>();
        List<String> missingKey = new ArrayList<>();

        // 1. Redis 缓存查
        for (int i = 0; i < texts.size(); i++) {
            String key = cacheKey(model, texts.get(i));
            String cached = redis.opsForValue().get(key);
            float[] vec = cached != null ? decode(cached, dims) : null;
            if (vec != null) {
                result[i] = vec;
            } else {
                missingIdx.add(i);
                missingText.add(texts.get(i));
                missingKey.add(key);
            }
        }
        log.info("embedding 缓存命中 {} / {}", texts.size() - missingIdx.size(), texts.size());

        // 2. 缺失的分批请求
        for (int from = 0; from < missingText.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, missingText.size());
            List<String> batch = missingText.subList(from, to);
            List<float[]> embeddings = client.embed(batch);
            for (int i = 0; i < embeddings.size(); i++) {
                int origIdx = missingIdx.get(from + i);
                String key = missingKey.get(from + i);
                float[] vec = embeddings.get(i);
                result[origIdx] = vec;
                redis.opsForValue().set(key, encode(vec), CACHE_TTL_DAYS, TimeUnit.DAYS);
            }
        }
        return new ArrayList<>(Arrays.asList(result));
    }

    public float[] embedOne(String text) {
        return embedAll(List.of(text)).get(0);
    }

    private String cacheKey(String model, String text) {
        return CACHE_PREFIX + model + ":" + SecureUtil.sha256(text);
    }

    private String encode(float[] vec) {
        ByteBuffer bb = ByteBuffer.allocate(vec.length * Float.BYTES);
        for (float v : vec) bb.putFloat(v);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private float[] decode(String base64, int expectedDims) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length != expectedDims * Float.BYTES) return null;
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            float[] vec = new float[expectedDims];
            for (int i = 0; i < expectedDims; i++) vec[i] = bb.getFloat();
            return vec;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> usageDummy() {
        return new HashMap<>(0);
    }

    public byte[] textBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
