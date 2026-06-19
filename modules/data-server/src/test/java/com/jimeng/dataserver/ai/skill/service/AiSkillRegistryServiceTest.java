package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AiSkillRegistryServiceTest {
    private AiSkill skill(String tenant, String status, String type) {
        AiSkill s = new AiSkill();
        s.setTenantId(tenant); s.setStatus(status); s.setSkillType(type);
        s.setName("n"); s.setDescription("d");
        return s;
    }
    @Test
    void groupsActivePromptSkillsByTenant() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                skill("t1", SkillConst.STATUS_ACTIVE, SkillConst.TYPE_PROMPT),
                skill("t1", SkillConst.STATUS_ACTIVE, SkillConst.TYPE_PROMPT),
                skill("t2", SkillConst.STATUS_ACTIVE, SkillConst.TYPE_PROMPT)));
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getTopic(anyString())).thenReturn(mock(RTopic.class));
        AiSkillRegistryService svc = new AiSkillRegistryService(mapper, redisson);
        svc.reloadLocal();
        assertEquals(2, svc.listActiveByTenant("t1").size());
        assertEquals(1, svc.listActiveByTenant("t2").size());
        assertTrue(svc.listActiveByTenant("nope").isEmpty());
    }
    @Test
    void reloadAndBroadcastPublishes() {
        AiSkillMapper mapper = mock(AiSkillMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        RTopic topic = mock(RTopic.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        AiSkillRegistryService svc = new AiSkillRegistryService(mapper, redisson);
        svc.reloadAndBroadcast();
        verify(topic, atLeastOnce()).publish(anyString());
    }
}
