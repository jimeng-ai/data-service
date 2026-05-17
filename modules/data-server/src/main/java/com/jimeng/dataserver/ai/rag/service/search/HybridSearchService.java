package com.jimeng.dataserver.ai.rag.service.search;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.embed.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 双路检索 BM25 + KNN，融合用 Java 手写 RRF。
 *
 * <p>ES 内置的 RRF（无论是 retriever.rrf 还是 rank.rrf）属 Platinum/Enterprise 付费特性，
 * basic 许可证会 403。因此这里改为：分别发两次 _search（BM25 / KNN，都是 basic 可用），
 * 在 Java 里按 1/(k + rank) 做 RRF 融合。和 ES 内置 RRF 行为等价。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final RestClient restClient;
    private final RagProperties ragProperties;
    private final EmbeddingService embeddingService;

    public List<SearchResultItem> search(Long kbId, String query, List<Long> docIds, int topK) throws IOException {
        if (query == null || query.isBlank()) return Collections.emptyList();
        float[] qVec = embeddingService.embedOne(query);
        RagProperties.Retrieval r = ragProperties.getRetrieval();
        String index = ragProperties.getElasticsearch().getIndexName();

        JSONArray filters = buildFilters(kbId, docIds);

        List<SearchResultItem> bm25Hits = executeSearch(index, buildBm25Body(query, filters, r.getBm25TopK()));
        List<SearchResultItem> knnHits = executeSearch(index, buildKnnBody(qVec, filters, r.getVectorTopK()));

        return rrfMerge(bm25Hits, knnHits, r.getRrfRankConstant(), topK);
    }

    // ------------------------------------------------------------------ filters

    private JSONArray buildFilters(Long kbId, List<Long> docIds) {
        JSONArray filters = JSONUtil.createArray();
        filters.put(JSONUtil.createObj().set("term", JSONUtil.createObj().set("kb_id", String.valueOf(kbId))));
        if (docIds != null && !docIds.isEmpty()) {
            JSONArray docIdStrs = new JSONArray();
            for (Long id : docIds) docIdStrs.put(String.valueOf(id));
            filters.put(JSONUtil.createObj().set("terms", JSONUtil.createObj().set("doc_id", docIdStrs)));
        }
        return filters;
    }

    // ------------------------------------------------------------------ BM25

    private String buildBm25Body(String query, JSONArray filters, int size) {
        JSONObject body = JSONUtil.createObj()
                .set("query", JSONUtil.createObj()
                        .set("bool", JSONUtil.createObj()
                                .set("must", JSONUtil.createObj()
                                        .set("match", JSONUtil.createObj().set("contextualized_content", query)))
                                .set("filter", filters)))
                .set("size", size)
                .set("_source", sourceFields());
        return body.toString();
    }

    // ------------------------------------------------------------------ KNN

    private String buildKnnBody(float[] qVec, JSONArray filters, int k) {
        JSONArray vec = new JSONArray();
        for (float v : qVec) vec.put(v);
        JSONObject knn = JSONUtil.createObj()
                .set("field", "embedding")
                .set("query_vector", vec)
                .set("k", k)
                .set("num_candidates", Math.max(k * 4, 200));
        if (filters != null && !filters.isEmpty()) {
            knn.set("filter", JSONUtil.createObj().set("bool", JSONUtil.createObj().set("filter", filters)));
        }
        JSONObject body = JSONUtil.createObj()
                .set("knn", knn)
                .set("size", k)
                .set("_source", sourceFields());
        return body.toString();
    }

    private JSONArray sourceFields() {
        return JSONUtil.createArray()
                .put("chunk_id").put("kb_id").put("doc_id").put("chunk_index").put("chunk_type")
                .put("heading_path").put("page_num").put("content").put("image_url");
    }

    // ------------------------------------------------------------------ HTTP

    private List<SearchResultItem> executeSearch(String index, String body) throws IOException {
        Request req = new Request("POST", "/" + index + "/_search");
        req.setJsonEntity(body);
        Response resp = restClient.performRequest(req);
        if (resp.getStatusLine().getStatusCode() != 200) {
            throw new IOException("ES search 失败: " + resp.getStatusLine());
        }
        String respBody = new String(resp.getEntity().getContent().readAllBytes());
        return parseHits(respBody);
    }

    private List<SearchResultItem> parseHits(String body) {
        JSONObject root = JSONUtil.parseObj(body);
        JSONObject hits = root.getJSONObject("hits");
        if (hits == null) return Collections.emptyList();
        JSONArray hitArr = hits.getJSONArray("hits");
        if (hitArr == null) return Collections.emptyList();
        List<SearchResultItem> out = new ArrayList<>(hitArr.size());
        for (Object o : hitArr) {
            JSONObject hit = (JSONObject) o;
            JSONObject src = hit.getJSONObject("_source");
            if (src == null) continue;
            out.add(SearchResultItem.builder()
                    .chunkId(src.getStr("chunk_id"))
                    .kbId(parseLong(src.getStr("kb_id")))
                    .docId(parseLong(src.getStr("doc_id")))
                    .chunkIndex(src.getInt("chunk_index"))
                    .chunkType(src.getStr("chunk_type"))
                    .headingPath(src.getStr("heading_path"))
                    .pageNum(src.getInt("page_num"))
                    .content(src.getStr("content"))
                    .imageUrl(src.getStr("image_url"))
                    .rrfScore(hit.getDouble("_score"))
                    .build());
        }
        return out;
    }

    // ------------------------------------------------------------------ RRF fusion (Java)

    private List<SearchResultItem> rrfMerge(List<SearchResultItem> a, List<SearchResultItem> b, int k, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, SearchResultItem> items = new LinkedHashMap<>();
        accumulate(a, scores, items, k);
        accumulate(b, scores, items, k);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchResultItem it = items.get(e.getKey());
                    it.setRrfScore(e.getValue());
                    return it;
                })
                .collect(Collectors.toList());
    }

    private void accumulate(List<SearchResultItem> hits, Map<String, Double> scores,
                            Map<String, SearchResultItem> items, int k) {
        for (int i = 0; i < hits.size(); i++) {
            SearchResultItem it = hits.get(i);
            String id = it.getChunkId();
            if (id == null) continue;
            double contribution = 1.0 / (k + i + 1.0);
            scores.merge(id, contribution, Double::sum);
            items.putIfAbsent(id, it);
        }
    }

    private Long parseLong(String s) {
        try { return s == null ? null : Long.parseLong(s); } catch (Exception e) { return null; }
    }

    public List<Long> distinctDocIds(List<SearchResultItem> items) {
        return items.stream().map(SearchResultItem::getDocId).distinct().collect(Collectors.toList());
    }
}
