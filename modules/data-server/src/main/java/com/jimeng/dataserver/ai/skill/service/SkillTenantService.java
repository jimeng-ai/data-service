package com.jimeng.dataserver.ai.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.util.SkillMarkdownParser;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SkillTenantService {
    private final AiSkillMapper aiSkillMapper;
    private final AiSkillRegistryService registry;

    @Transactional
    public AiSkill createFromMarkdown(String rawMarkdown, String tenantId, Long ownerUserId) {
        SkillMarkdownParser.ParsedSkill p = SkillMarkdownParser.parse(rawMarkdown);
        SkillMarkdownParser.validate(p);
        AiSkill s = new AiSkill();
        s.setTenantId(tenantId);
        s.setOwnerUserId(ownerUserId);
        s.setScope(SkillConst.SCOPE_PRIVATE);
        s.setName(p.name());
        s.setDescription(p.description());
        s.setBody(p.body());
        s.setSkillType(SkillConst.TYPE_PROMPT);
        s.setSource(SkillConst.SOURCE_UPLOAD);
        s.setStatus(SkillConst.STATUS_ACTIVE);
        s.setVersion(1);
        aiSkillMapper.insert(s);
        registry.reloadAndBroadcast();
        return s;
    }

    public List<AiSkill> list(String tenantId, Long currentUserId, boolean onlyMine) {
        LambdaQueryWrapper<AiSkill> q = new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getTenantId, tenantId)
                .orderByDesc(AiSkill::getCreateTime);
        if (onlyMine) {
            q.eq(AiSkill::getOwnerUserId, currentUserId);
        } else {
            q.and(w -> w.eq(AiSkill::getScope, SkillConst.SCOPE_TENANT)
                        .or().eq(AiSkill::getOwnerUserId, currentUserId));
        }
        return aiSkillMapper.selectList(q);
    }

    public AiSkill get(Long id, String tenantId, Long currentUserId) {
        AiSkill s = aiSkillMapper.selectById(id);
        if (s == null || !Objects.equals(s.getTenantId(), tenantId)) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "未找到该 skill");
        }
        // 可见性与列表一致：私有 skill 仅创建者可见（含其正文/脚本），否则当作不存在，避免按 id 越权读他人源码
        boolean visible = SkillConst.SCOPE_TENANT.equals(s.getScope())
                || Objects.equals(s.getOwnerUserId(), currentUserId);
        if (!visible) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "未找到该 skill");
        }
        return s;
    }

    @Transactional
    public void setScope(Long id, String scope, Long currentUserId) {
        AiSkill s = requireOwned(id, currentUserId);
        s.setScope(scope);
        aiSkillMapper.updateById(s);
        registry.reloadAndBroadcast();
    }

    @Transactional
    public void setStatus(Long id, String status, Long currentUserId) {
        AiSkill s = requireOwned(id, currentUserId);
        s.setStatus(status);
        aiSkillMapper.updateById(s);
        registry.reloadAndBroadcast();
    }

    @Transactional
    public void delete(Long id, Long currentUserId) {
        requireOwned(id, currentUserId);
        aiSkillMapper.deleteById(id);
        registry.reloadAndBroadcast();
    }

    /** 本次 run 可见的 ACTIVE DOER skill（scope=TENANT 或 owner==当前用户）。每次现读，不走缓存。 */
    /**
     * 本次 run 要物化进沙箱的 DOER 技能。
     *
     * @param allowedSkillIds 该 Agent 绑定的技能 id。<b>三态，与 AgentRuntimeView.allowedSkillIds 一致</b>：
     *                        {@code null} = 无绑定信息（老发布快照）→ 不按 Agent 过滤，保持旧行为；
     *                        空集 = 明确不绑 → 一个 DOER 技能都不下发；
     *                        非空 = 只下发集合内的。
     *
     * <p>加这个参数之前，每个 Agent 的每一次 run 都会把该租户【全部】活跃 DOER 技能的完整 bundle
     * 从 MinIO 拉进工作区——既是作用域漏洞（SDK 明确说 options.skills 只是上下文过滤器，
     * 未列出的技能文件仍在盘上、Read/Bash 可达），也是无谓的拉取开销。
     */
    public List<AiSkill> listActiveDoerForRun(String tenantId, Long currentUserId, Set<Long> allowedSkillIds) {
        if (allowedSkillIds != null && allowedSkillIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<AiSkill> q = new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getTenantId, tenantId)
                .eq(AiSkill::getStatus, SkillConst.STATUS_ACTIVE)
                .eq(AiSkill::getSkillType, SkillConst.TYPE_DOER)
                .and(w -> w.eq(AiSkill::getScope, SkillConst.SCOPE_TENANT)
                            .or().eq(AiSkill::getOwnerUserId, currentUserId));
        if (allowedSkillIds != null) {
            q.in(AiSkill::getId, allowedSkillIds);
        }
        return aiSkillMapper.selectList(q);
    }

    private AiSkill requireOwned(Long id, Long currentUserId) {
        AiSkill s = aiSkillMapper.selectById(id);
        if (s == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "未找到该 skill");
        if (!Objects.equals(s.getOwnerUserId(), currentUserId))
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "只能修改自己创建的 skill");
        return s;
    }
}
