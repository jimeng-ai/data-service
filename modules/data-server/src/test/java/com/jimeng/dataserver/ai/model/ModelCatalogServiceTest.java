package com.jimeng.dataserver.ai.model;

import com.jimeng.dataserver.ai.model.dto.ModelView;
import com.jimeng.persistence.entity.AiModel;
import com.jimeng.persistence.mapper.AiModelMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目录读取/校验：数据源已迁到 DB（ModelRegistry），这里用 mock mapper 喂行，
 * 验证 ModelCatalogService 的对外契约（启用过滤 / 合法性 / maxTemp 兜底）不变。
 */
class ModelCatalogServiceTest {

    private ModelCatalogService serviceWith(AiModel... rows) {
        AiModelMapper mapper = mock(AiModelMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rows));
        ModelRegistry registry = new ModelRegistry(mapper);
        registry.load();   // 装载快照
        return new ModelCatalogService(registry);
    }

    private AiModel model(String value, String provider, double maxTemp, boolean enabled, int sort) {
        AiModel m = new AiModel();
        m.setValue(value);
        m.setLabel(value.toUpperCase());
        m.setProvider(provider);
        m.setProtocol("anthropic");
        m.setUpstreamModel(value);
        m.setMaxTemp(maxTemp);
        m.setEnabled(enabled);
        m.setSort(sort);
        m.setDescription("desc-" + value);
        return m;
    }

    @Test
    void listEnabled_excludesDisabled() {
        ModelCatalogService svc = serviceWith(
                model("claude-sonnet-4-6", "anthropic", 1, true, 0),
                model("gpt-4o", "openai", 2, true, 1),
                model("old-model", "openai", 2, false, 2));
        List<ModelView> views = svc.listEnabled();
        assertEquals(2, views.size());
        assertTrue(views.stream().noneMatch(v -> v.getValue().equals("old-model")));
        assertEquals("anthropic", views.get(0).getProvider());
    }

    @Test
    void isValidModel_onlyEnabledCount() {
        ModelCatalogService svc = serviceWith(
                model("claude-sonnet-4-6", "anthropic", 1, true, 0),
                model("old-model", "openai", 2, false, 1));
        assertTrue(svc.isValidModel("claude-sonnet-4-6"));
        assertFalse(svc.isValidModel("old-model"));   // 禁用的不算合法
        assertFalse(svc.isValidModel("nonexistent"));
        assertFalse(svc.isValidModel(null));
    }

    @Test
    void maxTempOf_fallsBackTo2WhenUnknown() {
        ModelCatalogService svc = serviceWith(model("claude-sonnet-4-6", "anthropic", 1, true, 0));
        assertEquals(1.0, svc.maxTempOf("claude-sonnet-4-6"));
        assertEquals(2.0, svc.maxTempOf("unknown"));   // 兜底取较宽松上限
    }
}
