package com.jimeng.dataserver.ai.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.util.List;

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
