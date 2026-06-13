package com.jimeng.dataserver.ai.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.grant.service.CreatorGrantService;
import com.jimeng.dataserver.ai.rag.service.es.ChunkIndexService;
import com.jimeng.persistence.entity.KnowledgeBase;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final ChunkIndexService chunkIndexService;
    private final CreatorGrantService creatorGrantService;

    @Transactional
    public KnowledgeBase create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "name 不能为空");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        knowledgeBaseMapper.insert(kb);
        // 成员自授权：否则建完知识库后列表被 filterCurrent 过滤掉，表现为「知识库列表为空」。
        creatorGrantService.grantNewResourceToCreator(ResourceType.KNOWLEDGE_BASE, kb.getId());
        return kb;
    }

    public KnowledgeBase get(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "knowledge_base 不存在: " + id);
        return kb;
    }

    public List<KnowledgeBase> list() {
        return knowledgeBaseMapper.selectList(null);
    }

    /**
     * 列表页统计回填：按 kb_id 聚合 kb_document（已自动排除逻辑删除）得到 文档数 / 切片数 / 总大小 /
     * 完成数，并据文档状态汇总出 indexStatus（ERROR > INDEXING > READY）。一条 GROUP BY 查询，避免 N+1。
     */
    public void fillStats(List<KnowledgeBase> kbs) {
        if (kbs == null || kbs.isEmpty()) {
            return;
        }
        List<Long> ids = kbs.stream().map(KnowledgeBase::getId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        QueryWrapper<KbDocument> qw = new QueryWrapper<>();
        qw.select(
                "kb_id AS kbId",
                "COUNT(*) AS docCount",
                "COALESCE(SUM(file_size), 0) AS totalSize",
                "COALESCE(SUM(total_chunks), 0) AS chunkCount",
                "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS doneCount",
                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount");
        qw.in("kb_id", ids);
        qw.groupBy("kb_id");

        Map<Long, Map<String, Object>> byKb = new HashMap<>();
        for (Map<String, Object> row : kbDocumentMapper.selectMaps(qw)) {
            Long kbId = toLong(row.get("kbId"));
            if (kbId != null) {
                byKb.put(kbId, row);
            }
        }

        for (KnowledgeBase kb : kbs) {
            Map<String, Object> r = byKb.get(kb.getId());
            long doc = r == null ? 0 : toLong(r.get("docCount"));
            long chunk = r == null ? 0 : toLong(r.get("chunkCount"));
            long size = r == null ? 0 : toLong(r.get("totalSize"));
            long done = r == null ? 0 : toLong(r.get("doneCount"));
            long failed = r == null ? 0 : toLong(r.get("failedCount"));
            kb.setDocCount((int) doc);
            kb.setChunkCount(chunk);
            kb.setTotalSize(size);
            kb.setDoneCount((int) done);
            if (failed > 0) {
                kb.setIndexStatus("ERROR");
            } else if (doc - done > 0) {
                kb.setIndexStatus("INDEXING");
            } else {
                kb.setIndexStatus("READY");
            }
        }
    }

    /** selectMaps 的聚合列可能是 Long / BigInteger / BigDecimal / String，统一转 long。 */
    private long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof BigInteger bi) {
            return bi.longValue();
        }
        if (v instanceof BigDecimal bd) {
            return bd.longValue();
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void delete(Long id) throws Exception {
        // 级联删除：先 ES 后 MySQL
        chunkIndexService.deleteByKb(id);
        LambdaQueryWrapper<KbDocument> dw = new LambdaQueryWrapper<>();
        dw.eq(KbDocument::getKbId, id);
        kbDocumentMapper.delete(dw);
        knowledgeBaseMapper.deleteById(id);
        log.info("knowledge_base {} 及其所有文档已删除", id);
    }
}
