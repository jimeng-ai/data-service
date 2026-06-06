package com.jimeng.dataserver.ai.rag.service.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import com.jimeng.dataserver.ai.rag.model.Chunk;
import com.jimeng.persistence.entity.KbChunk;
import com.jimeng.persistence.mapper.KbChunkMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chunk 双写：MySQL（kb_chunk 元数据）+ Elasticsearch（kb_chunks 索引含 embedding）。
 *
 * <p>MySQL 写在事务内（提交后才动 ES）；ES 写在事务外。原因：ES 非事务性，把 {@code esClient} 的
 * 网络调用放进 {@code @Transactional} 既无法回滚 ES、又会在 ES 调用期间一直占着一条 DB 连接（长事务 +
 * 连接池压力）。ES 失败仍抛 {@link IOException} 让上游把 doc 标记 FAILED 重试——本类对同一 docId 的写
 * 是幂等的（先删后插 / 先删后 upsert），重试可自愈短暂的 MySQL 已写、ES 未写不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkIndexService {

    private final ElasticsearchClient esClient;
    private final RagProperties ragProperties;
    private final KbChunkMapper kbChunkMapper;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTx() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public void indexChunks(Long kbId, Long docId, List<Chunk> chunks) throws IOException {
        if (chunks == null || chunks.isEmpty()) return;

        // 1) MySQL（事务内）：幂等预清理（覆盖 retry 重跑 / 重新切片导致旧高 index chunk 变孤儿）后插入新 chunk。
        txTemplate.executeWithoutResult(status -> {
            deleteChunksFromDb(docId);
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
        });

        // 2) ES（事务外）：先 deleteByQuery 清孤儿（bulk 按 chunk_id upsert 覆盖不到新 chunk 数 < 旧的情况），再 bulk index。
        deleteChunksFromEs(docId);
        bulkIndexToEs(kbId, docId, chunks);
        log.info("索引完成 kbId={} docId={} chunks={}", kbId, docId, chunks.size());
    }

    public void deleteByDoc(Long docId) throws IOException {
        txTemplate.executeWithoutResult(status -> deleteChunksFromDb(docId));
        deleteChunksFromEs(docId);
    }

    public void deleteByKb(Long kbId) throws IOException {
        txTemplate.executeWithoutResult(status -> kbChunkMapper.physicalDeleteByKbId(kbId));
        String index = ragProperties.getElasticsearch().getIndexName();
        esClient.deleteByQuery(d -> d
                .index(index)
                .query(Query.of(q -> q.term(t -> t.field("kb_id").value(String.valueOf(kbId))))));
    }

    private void deleteChunksFromDb(Long docId) {
        // 物理删除：kb_chunk 有唯一键 uk_chunk_id，逻辑删除会留着旧 chunk_id 占位，
        // 重新索引插入同名 chunk_id 会撞 Duplicate entry → 整批回滚 → 入库失败重试死循环。
        kbChunkMapper.physicalDeleteByDocId(docId);
    }

    private void deleteChunksFromEs(Long docId) throws IOException {
        String index = ragProperties.getElasticsearch().getIndexName();
        esClient.deleteByQuery(d -> d
                .index(index)
                .query(Query.of(q -> q.term(t -> t.field("doc_id").value(String.valueOf(docId))))));
    }

    private void bulkIndexToEs(Long kbId, Long docId, List<Chunk> chunks) throws IOException {
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
