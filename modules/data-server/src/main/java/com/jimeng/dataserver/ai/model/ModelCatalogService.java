package com.jimeng.dataserver.ai.model;

import com.jimeng.dataserver.ai.model.dto.ModelView;
import com.jimeng.persistence.entity.AiModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型目录读取/校验。所有"可选模型"判断的唯一入口。
 *
 * <p>数据源已从 Nacos {@code ai.model-catalog} 迁到 DB（{@link ModelRegistry}）；
 * 出参形态与对外契约（/data/admin/models）保持不变。
 */
@Service
@RequiredArgsConstructor
public class ModelCatalogService {

    /** maxTemp 兜底（未匹配到模型时取较宽松的 OpenAI 上限），与前端 DEFAULT_MAX_TEMP 对齐。 */
    private static final double DEFAULT_MAX_TEMP = 2.0;

    private final ModelRegistry registry;

    /** 启用中的模型（接口出参 / 构建器注入用）。 */
    public List<ModelView> listEnabled() {
        return registry.listEnabled().stream().map(this::toView).toList();
    }

    /** value 是否为"启用中的合法模型"。draft_agent 校验、调试台保存校验共用。 */
    public boolean isValidModel(String value) {
        return registry.isValid(value);
    }

    /** 取模型 temperature 上限；未知返回兜底 2.0。 */
    public double maxTempOf(String value) {
        AiModel m = registry.resolve(value);
        if (m != null && m.getMaxTemp() != null) {
            return m.getMaxTemp();
        }
        return DEFAULT_MAX_TEMP;
    }

    private ModelView toView(AiModel m) {
        ModelView v = new ModelView();
        v.setValue(m.getValue());
        v.setLabel(m.getLabel());
        v.setProvider(m.getProvider());
        v.setMaxTemp(m.getMaxTemp());
        v.setDescription(m.getDescription());
        return v;
    }
}
