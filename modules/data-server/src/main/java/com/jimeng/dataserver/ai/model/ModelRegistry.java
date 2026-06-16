package com.jimeng.dataserver.ai.model;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.AiModel;
import com.jimeng.persistence.mapper.AiModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可选模型目录的单一查询入口（DB 支撑 + 进程内缓存）。
 *
 * <p>替代原 Nacos {@code ai.model-catalog} 的真相源地位：目录展示、构建器/调试台选型校验、
 * chat 按模型路由解析、计费取价，全部走这里。运营 CRUD 写库后调 {@link #refresh()} 重建快照。
 *
 * <p>缓存是「整表非删行」的不可变快照（{@code volatile} 引用替换，读无锁）。模型条目低频变更、
 * 量小（个位数～几十），整表缓存足够；快照含 disabled 行，以便历史/下线模型仍可解析取价，
 * 但 {@link #listEnabled()} / {@link #isValid(String)} 只认 enabled。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRegistry {

    private final AiModelMapper aiModelMapper;

    /** 不可变快照：value→model、upstreamModel→model（取价兜底用）、有序 enabled 列表。 */
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), List.of());

    private record Snapshot(Map<String, AiModel> byValue,
                            Map<String, AiModel> byUpstream,
                            List<AiModel> enabledSorted) {}

    /**
     * 首次装载：用 ApplicationReadyEvent 而非 @PostConstruct——确保在 Flyway 建表/迁移完成之后，
     * 避免 bean 初始化早于迁移导致「Table ai_model doesn't exist」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        try {
            refresh();
        } catch (Exception e) {
            // 容错：首次装载失败不拖垮应用（保留空快照），记录后等运营写操作或下次重启重试。
            log.error("模型目录首次装载失败，暂用空快照；请检查 ai_model 表与 DB 连接", e);
        }
    }

    /** 重新从 DB 装载快照。运营写操作后调用；启动后 ApplicationReadyEvent 调用。 */
    public synchronized void refresh() {
        // ai_model 是平台级表（不在租户白名单），跨租户读取走 runAsSystem 保险。
        List<AiModel> rows = TenantContext.runAsSystem(() ->
                aiModelMapper.selectList(Wrappers.<AiModel>lambdaQuery().orderByAsc(AiModel::getSort)));

        Map<String, AiModel> byValue = new LinkedHashMap<>();
        Map<String, AiModel> byUpstream = new LinkedHashMap<>();
        List<AiModel> enabled = new ArrayList<>();
        for (AiModel m : rows) {
            if (m.getValue() != null) byValue.put(m.getValue(), m);
            if (m.getUpstreamModel() != null) byUpstream.putIfAbsent(m.getUpstreamModel(), m);
            if (Boolean.TRUE.equals(m.getEnabled())) enabled.add(m);
        }
        this.snapshot = new Snapshot(byValue, byUpstream, List.copyOf(enabled));
        log.info("模型目录已装载：{} 条（启用 {}）", rows.size(), enabled.size());
    }

    /** 启用中的模型，按 sort 升序。供 /data/admin/models 与构建器注入。 */
    public List<AiModel> listEnabled() {
        return snapshot.enabledSorted();
    }

    /** 按逻辑 value 解析（含 disabled，用于路由/取价）；无则返回 null。 */
    public AiModel resolve(String value) {
        if (value == null) return null;
        return snapshot.byValue().get(value);
    }

    /** value 是否为「启用中的合法模型」。draft_agent 校验、调试台保存校验共用。 */
    public boolean isValid(String value) {
        AiModel m = resolve(value);
        return m != null && Boolean.TRUE.equals(m.getEnabled());
    }

    /**
     * 取定价：先按逻辑 value 命中，再按 upstream_model 兜底（计费时 model 可能已被改写成上游名）。
     * 无匹配返回 null（由 ModelPricing 落回前缀表兜底）。
     */
    public AiModel priceOf(String model) {
        if (model == null) return null;
        AiModel m = snapshot.byValue().get(model);
        return m != null ? m : snapshot.byUpstream().get(model);
    }
}
