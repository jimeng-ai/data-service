package com.jimeng.dataserver.ai.model;

import com.jimeng.dataserver.ai.model.ModelCatalogProperties.ModelEntry;
import com.jimeng.dataserver.ai.model.dto.ModelView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogServiceTest {

    private ModelCatalogService serviceWith(ModelEntry... entries) {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setModels(List.of(entries));
        return new ModelCatalogService(props);
    }

    private ModelEntry entry(String value, String provider, double maxTemp, boolean enabled) {
        ModelEntry e = new ModelEntry();
        e.setValue(value);
        e.setLabel(value.toUpperCase());
        e.setProvider(provider);
        e.setMaxTemp(maxTemp);
        e.setDescription("desc-" + value);
        e.setEnabled(enabled);
        return e;
    }

    @Test
    void listEnabled_excludesDisabled() {
        ModelCatalogService svc = serviceWith(
                entry("claude-sonnet-4-6", "anthropic", 1, true),
                entry("gpt-4o", "openai", 2, true),
                entry("old-model", "openai", 2, false));
        List<ModelView> views = svc.listEnabled();
        assertEquals(2, views.size());
        assertTrue(views.stream().noneMatch(v -> v.getValue().equals("old-model")));
        assertEquals("anthropic", views.get(0).getProvider());
    }

    @Test
    void isValidModel_onlyEnabledCount() {
        ModelCatalogService svc = serviceWith(
                entry("claude-sonnet-4-6", "anthropic", 1, true),
                entry("old-model", "openai", 2, false));
        assertTrue(svc.isValidModel("claude-sonnet-4-6"));
        assertFalse(svc.isValidModel("old-model"));   // 禁用的不算合法
        assertFalse(svc.isValidModel("nonexistent"));
        assertFalse(svc.isValidModel(null));
    }

    @Test
    void maxTempOf_fallsBackTo2WhenUnknown() {
        ModelCatalogService svc = serviceWith(entry("claude-sonnet-4-6", "anthropic", 1, true));
        assertEquals(1.0, svc.maxTempOf("claude-sonnet-4-6"));
        assertEquals(2.0, svc.maxTempOf("unknown"));   // 兜底取较宽松上限
    }
}
