package com.jimeng.dataserver.ai.rag.service.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.Chunk;
import com.jimeng.persistence.entity.KbChunk;
import com.jimeng.persistence.mapper.KbChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chunk 双写：MySQL（kb_chunk 元数据）+ Elasticsearch（kb_chunks 索引含 embedding）。
 * MySQL 出现故障 → 整批回滚；ES 出现故障 → 抛 IOException 让上游标记 doc FAILED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkIndexService {

    private final ElasticsearchClient esClient;
    private final RagProperties ragProperties;
    private final KbChunkMapper kbChunkMapper;

    @Transactional(rollbackFor = Exception.class)
    public void indexChunks(Long kbId, Long docId, List<Chunk> chunks) throws IOException {
        if (chunks == null || chunks.isEmpty()) return;

        // 0) 幂等预清理：覆盖以下两类场景的"半成品残留"
        //   a) 同一 doc 上次 ingest 失败（retry 重跑前）
        //   b) 同一 doc 重新切片导致 chunk 数变化（旧的高 index chunk 会变孤儿）
        // MySQL DELETE 跟下面的 INSERT 共享 @Transactional，原子。
        // ES 那边 bulk index 虽然按 chunk_id upsert 已经能覆盖同 id，
        // 但若新 chunk 数 < 旧 chunk 数，旧的 doc_id_{N+1...} 不会被覆盖到，需主动删。
        cleanupExistingChunks(docId);

        // 1) MySQL 元数据
        for (Chunk c : chunks) {
            if (c.getChunkId() == null) {
                c.setChunkId(docId + "_" + c.getChunkIndex());
            }
            KbChunk entity = new KbChunk();
            entity.setChunkId(c.getChunkId());
            entity.setDocId(docId);
            entity.setKbId(kbId);
            entity.setChunkIndex(c.getChunkIndex());
            entity.setChunkType(c.getType() != null ? c.getType().name() : "TEXT");
            entity.setHeadingPath(c.getHeadingPath() == null ? null : String.join(" > ", c.getHeadingPath()));
            entity.setPageNum(c.getPageNum());
            entity.setContent(c.getContent());
            entity.setContextualizedContent(c.getContextualizedContent());
            entity.setImageUrl(c.getImageUrl());
            entity.setTokenCount(c.getTokenCount());
            kbChunkMapper.insert(entity);
        }

        // 2) ES bulk
        String index = ragProperties.getElasticsearch().getIndexName();
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (Chunk c : chunks) {
            Map<String, Object> doc = toEsDoc(kbId, docId, c);
            br.operations(op -> op.index(idx -> idx
                    .index(index)
                    .id(c.getChunkId())
                    .document(doc)));
        }
        BulkResponse resp = esClient.bulk(br.build());
        if (resp.errors()) {
            String err = resp.items().stream()
                    .filter(i -> i.error() != null)
                    .map(i -> i.id() + ":" + i.error().reason())
                    .limit(5)
                    .collect(Collectors.joining(" | "));
            throw new IOException("ES bulk 索引失败: " + err);
        }
        log.info("索引完成 kbId={} docId={} chunks={}", kbId, docId, chunks.size());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByDoc(Long docId) throws IOException {
        cleanupExistingChunks(docId);
    }

    /** 清掉 doc 下所有 chunk 的 ES + MySQL 两端记录，供 deleteByDoc 与 indexChunks 复用。 */
    private void cleanupExistingChunks(Long docId) throws IOException {
        String index = ragProperties.getElasticsearch().getIndexName();
        esClient.deleteByQuery(d -> d
                .index(index)
                .query(Query.of(q -> q.term(t -> t.field("doc_id").value(String.valueOf(docId))))));
        LambdaQueryWrapper<KbChunk> w = new LambdaQueryWrapper<>();
        w.eq(KbChunk::getDocId, docId);
        kbChunkMapper.delete(w);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByKb(Long kbId) throws IOException {
        String index = ragProperties.getElasticsearch().getIndexName();
        esClient.deleteByQuery(d -> d
                .index(index)
                .query(Query.of(q -> q.term(t -> t.field("kb_id").value(String.valueOf(kbId))))));
        LambdaQueryWrapper<KbChunk> w = new LambdaQueryWrapper<>();
        w.eq(KbChunk::getKbId, kbId);
        kbChunkMapper.delete(w);
    }

    private Map<String, Object> toEsDoc(Long kbId, Long docId, Chunk c) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("chunk_id", c.getChunkId());
        doc.put("kb_id", String.valueOf(kbId));
        doc.put("doc_id", String.valueOf(docId));
        doc.put("chunk_index", c.getChunkIndex());
        doc.put("chunk_type", c.getType() != null ? c.getType().name() : "TEXT");
        if (c.getHeadingPath() != null && !c.getHeadingPath().isEmpty()) {
            doc.put("heading_path", String.join(" > ", c.getHeadingPath()));
        }
        if (c.getPageNum() != null) doc.put("page_num", c.getPageNum());
        if (c.getImageUrl() != null) doc.put("image_url", c.getImageUrl());
        doc.put("content", c.getContent());
        doc.put("contextualized_content",
                c.getContextualizedContent() != null && !c.getContextualizedContent().isEmpty()
                        ? c.getContextualizedContent() : c.getContent());

        if (c.getEmbedding() != null) {
            List<Float> vec = new ArrayList<>(c.getEmbedding().length);
            for (float v : c.getEmbedding()) vec.add(v);
            doc.put("embedding", vec);
        }
        return doc;
    }
}
