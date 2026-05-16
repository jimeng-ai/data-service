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
import java.util.List;
import java.util.stream.Collectors;

/**
 * ES retrievers API 双路检索（BM25 + KNN）+ RRF 融合。
 *
 * <p>用 RestClient low-level 发原始 JSON 是为了不被 Java Client DSL 是否覆盖 retrievers/RRF 卡住
 * （8.11+ 后端支持，但 Java Client DSL 跟得不一定齐）。结构稳定，便于后续调参。
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
        String index = ragProperties.getElasticsearch().getIndexName();
        String body = buildSearchBody(kbId, query, qVec, docIds, topK);

        Request req = new Request("POST", "/" + index + "/_search");
        req.setJsonEntity(body);
        Response resp = restClient.performRequest(req);
        if (resp.getStatusLine().getStatusCode() != 200) {
            throw new IOException("ES search 失败: " + resp.getStatusLine());
        }
        String respBody = new String(resp.getEntity().getContent().readAllBytes());
        return parseHits(respBody);
    }

    private String buildSearchBody(Long kbId, String query, float[] qVec, List<Long> docIds, int topK) {
        RagProperties.Retrieval r = ragProperties.getRetrieval();

        // 公共过滤
        JSONObject kbTerm = JSONUtil.createObj().set("term", JSONUtil.createObj().set("kb_id", String.valueOf(kbId)));
        JSONArray filters = JSONUtil.createArray().put(kbTerm);
        if (docIds != null && !docIds.isEmpty()) {
            JSONArray docIdStrs = new JSONArray();
            for (Long id : docIds) docIdStrs.put(String.valueOf(id));
            filters.put(JSONUtil.createObj().set("terms", JSONUtil.createObj().set("doc_id", docIdStrs)));
        }

        // BM25 retriever (standard)
        JSONObject matchQuery = JSONUtil.createObj()
                .set("query", JSONUtil.createObj()
                        .set("bool", JSONUtil.createObj()
                                .set("must", JSONUtil.createObj()
                                        .set("match", JSONUtil.createObj().set("contextualized_content", query)))
                                .set("filter", filters)));
        JSONObject standardRetriever = JSONUtil.createObj().set("standard", matchQuery);

        // KNN retriever
        JSONArray vec = new JSONArray();
        for (float v : qVec) vec.put(v);
        JSONObject knnRetriever = JSONUtil.createObj().set("knn", JSONUtil.createObj()
                .set("field", "embedding")
                .set("query_vector", vec)
                .set("k", r.getVectorTopK())
                .set("num_candidates", Math.max(r.getVectorTopK() * 4, 200))
                .set("filter", JSONUtil.createObj().set("bool", JSONUtil.createObj().set("filter", filters))));

        // RRF
        JSONObject rrf = JSONUtil.createObj()
                .set("retrievers", JSONUtil.createArray().put(standardRetriever).put(knnRetriever))
                .set("rank_window_size", Math.max(r.getBm25TopK(), r.getVectorTopK()))
                .set("rank_constant", r.getRrfRankConstant());

        JSONObject body = JSONUtil.createObj()
                .set("retriever", JSONUtil.createObj().set("rrf", rrf))
                .set("size", topK)
                .set("_source", JSONUtil.createArray()
                        .put("chunk_id").put("kb_id").put("doc_id").put("chunk_index").put("chunk_type")
                        .put("heading_path").put("page_num").put("content").put("image_url"));
        return body.toString();
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

    private Long parseLong(String s) {
        try { return s == null ? null : Long.parseLong(s); } catch (Exception e) { return null; }
    }

    public List<Long> distinctDocIds(List<SearchResultItem> items) {
        return items.stream().map(SearchResultItem::getDocId).distinct().collect(Collectors.toList());
    }
}
