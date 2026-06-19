package com.jimeng.dataserver.ai.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSkillRegistryService {
    public static final String RELOAD_TOPIC = "skill:registry:reload";
    private final AiSkillMapper aiSkillMapper;
    private final RedissonClient redissonClient;
    private final AtomicReference<Map<String, List<AiSkill>>> cache = new AtomicReference<>(Collections.emptyMap());

    @PostConstruct
    public void init() {
        reloadLocal();
        redissonClient.getTopic(RELOAD_TOPIC).addListener(String.class, (channel, msg) -> reloadLocal());
    }

    public void reloadLocal() {
        Map<String, List<AiSkill>> snap = TenantContext.runAsSystem(() -> {
            List<AiSkill> rows = aiSkillMapper.selectList(new LambdaQueryWrapper<AiSkill>()
                    .eq(AiSkill::getStatus, SkillConst.STATUS_ACTIVE)
                    .eq(AiSkill::getSkillType, SkillConst.TYPE_PROMPT));
            return rows.stream().filter(s -> s.getTenantId() != null)
                    .collect(Collectors.groupingBy(AiSkill::getTenantId));
        });
        cache.set(snap == null ? Collections.emptyMap() : snap);
        log.info("AiSkillRegistry 重建完成: 租户数={}", cache.get().size());
    }

    public void reloadAndBroadcast() {
        reloadLocal();
        redissonClient.getTopic(RELOAD_TOPIC).publish("reload");
    }

    public List<AiSkill> listActiveByTenant(String tenantId) {
        if (tenantId == null) return List.of();
        return cache.get().getOrDefault(tenantId, List.of());
    }
}
