package com.jimeng.dataserver.ai.rag.service;

import com.jimeng.dataserver.ai.rag.model.CitationItem;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把检索命中（{@link SearchResultItem}）富化成前端可直接展示的「参考来源」引用项：
 * 补 1-based index、文档标题（联 kb_document）、统一分值（精排分优先，否则 RRF 分）。
 *
 * <p>原本内联在 {@code RagAnswerService}（强制检索路 A）里；改为工具式按需检索（路 B）后，
 * 引用由 {@code RagSkillToolExecutor} 在 rag.search 执行时产出、经 tool_result 旁路上抛，
 * 故抽成独立组件供两处复用。kb_document 不在多租户白名单，但 docId 来自已鉴权的知识库检索，
 * 按 id 批量取标题即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CitationAssembler {

    private final KbDocumentMapper kbDocumentMapper;

    public List<CitationItem> assemble(List<SearchResultItem> hits) {
        if (hits == null || hits.isEmpty()) return List.of();
        List<Long> docIds = hits.stream()
                .map(SearchResultItem::getDocId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> titles = new HashMap<>();
        if (!docIds.isEmpty()) {
            try {
                for (KbDocument d : kbDocumentMapper.selectBatchIds(docIds)) {
                    titles.put(d.getId(), d.getTitle());
                }
            } catch (Exception e) {
                log.warn("加载引用文档标题失败 docIds={}: {}", docIds, e.getMessage());
            }
        }
        List<CitationItem> out = new ArrayList<>(hits.size());
        int index = 1;
        for (SearchResultItem c : hits) {
            out.add(CitationItem.builder()
                    .index(index++)
                    .docId(c.getDocId())
                    .docTitle(c.getDocId() == null ? null : titles.get(c.getDocId()))
                    .chunkId(c.getChunkId())
                    .content(c.getContent())
                    .headingPath(c.getHeadingPath())
                    .pageNum(c.getPageNum())
                    .score(c.getScore())
                    .build());
        }
        return out;
    }
}
