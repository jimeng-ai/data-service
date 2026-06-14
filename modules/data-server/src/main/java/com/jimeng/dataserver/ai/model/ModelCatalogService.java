package com.jimeng.dataserver.ai.model;

import com.jimeng.dataserver.ai.model.ModelCatalogProperties.ModelEntry;
import com.jimeng.dataserver.ai.model.dto.ModelView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 模型目录读取/校验。所有"可选模型"判断的唯一入口。 */
@Service
@RequiredArgsConstructor
public class ModelCatalogService {

    /** maxTemp 兜底（未匹配到模型时取较宽松的 OpenAI 上限），与前端 DEFAULT_MAX_TEMP 对齐。 */
    private static final double DEFAULT_MAX_TEMP = 2.0;

    private final ModelCatalogProperties properties;

    /** 启用中的模型（接口出参 / 构建器注入用）。 */
    public List<ModelView> listEnabled() {
        return properties.getModels().stream()
                .filter(ModelEntry::isEnabled)
                .map(this::toView)
                .toList();
    }

    /** value 是否为"启用中的合法模型"。draft_agent 校验、调试台保存校验共用。 */
    public boolean isValidModel(String value) {
        if (value == null || value.isBlank()) return false;
        return properties.getModels().stream()
                .anyMatch(e -> e.isEnabled() && value.equals(e.getValue()));
    }

    /** 取模型 temperature 上限；未知返回兜底 2.0。 */
    public double maxTempOf(String value) {
        return properties.getModels().stream()
                .filter(e -> e.getValue() != null && e.getValue().equals(value))
                .map(ModelEntry::getMaxTemp)
                .filter(t -> t != null)
                .findFirst()
                .orElse(DEFAULT_MAX_TEMP);
    }

    private ModelView toView(ModelEntry e) {
        ModelView v = new ModelView();
        v.setValue(e.getValue());
        v.setLabel(e.getLabel());
        v.setProvider(e.getProvider());
        v.setMaxTemp(e.getMaxTemp());
        v.setDescription(e.getDescription());
        return v;
    }
}
