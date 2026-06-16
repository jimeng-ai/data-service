package com.jimeng.dataserver.admin.operator.model.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.operator.model.dto.ModelUpsertRequest;
import com.jimeng.dataserver.admin.operator.model.dto.ProviderOption;
import com.jimeng.dataserver.ai.model.ModelRegistry;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.persistence.entity.AiModel;
import com.jimeng.persistence.mapper.AiModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 运营侧模型目录维护。平台级表 {@code ai_model}（不在租户白名单），全程 {@code runAsSystem}。
 * 写操作后刷新 {@link ModelRegistry} 缓存，使目录/路由/计费即时生效（无需重启）。
 *
 * <p>连接（provider 的 base-url/key/协议）仍由 Nacos {@code providers.*} 维护；这里只校验
 * 模型行引用的连接名存在、且模型协议与连接协议一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorModelService {

    private final AiModelMapper aiModelMapper;
    private final ModelRegistry modelRegistry;
    private final AiProviderProperties providerProperties;

    /** 全部模型（含 disabled），按 sort 升序。 */
    public List<AiModel> list() {
        return TenantContext.runAsSystem(() ->
                aiModelMapper.selectList(Wrappers.<AiModel>lambdaQuery().orderByAsc(AiModel::getSort)));
    }

    /** 可选连接（Nacos providers.* 的名字 + 各自 chat 协议），给页面下拉。 */
    public List<ProviderOption> providers() {
        return providerProperties.getProviders().entrySet().stream()
                .map(e -> new ProviderOption(e.getKey(),
                        e.getValue().getChat() == null ? null : e.getValue().getChat().getProtocol()))
                .toList();
    }

    public AiModel create(ModelUpsertRequest req, Long operatorId) {
        validate(req, null);
        AiModel m = new AiModel();
        apply(m, req);
        String op = String.valueOf(operatorId);
        m.setCreateUser(op);
        m.setUpdateUser(op);
        TenantContext.runAsSystem(() -> aiModelMapper.insert(m));
        modelRegistry.refresh();
        return m;
    }

    public AiModel update(Long id, ModelUpsertRequest req, Long operatorId) {
        AiModel m = requireById(id);
        validate(req, id);
        apply(m, req);
        m.setUpdateUser(String.valueOf(operatorId));
        TenantContext.runAsSystem(() -> aiModelMapper.updateById(m));
        modelRegistry.refresh();
        return m;
    }

    public void setEnabled(Long id, boolean enabled, Long operatorId) {
        AiModel m = requireById(id);
        m.setEnabled(enabled);
        m.setUpdateUser(String.valueOf(operatorId));
        TenantContext.runAsSystem(() -> aiModelMapper.updateById(m));
        modelRegistry.refresh();
    }

    public void delete(Long id) {
        requireById(id);
        TenantContext.runAsSystem(() -> aiModelMapper.deleteById(id));
        modelRegistry.refresh();
    }

    // ------------------------------------------------------------------ internals

    private AiModel requireById(Long id) {
        AiModel m = TenantContext.runAsSystem(() -> aiModelMapper.selectById(id));
        if (m == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "模型不存在");
        }
        return m;
    }

    /** 校验：必填、连接存在、协议一致、value 不重名（应用层兜底，表无唯一键）。 */
    private void validate(ModelUpsertRequest req, Long selfId) {
        if (StrUtil.isBlank(req.getValue())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "模型 value 不能为空");
        }
        if (StrUtil.isBlank(req.getLabel())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "展示名不能为空");
        }
        if (StrUtil.isBlank(req.getProvider())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请选择连接（provider）");
        }
        Map<String, ProviderConfig> providers = providerProperties.getProviders();
        ProviderConfig cfg = providers.get(req.getProvider());
        if (cfg == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "连接 [" + req.getProvider() + "] 未在 Nacos providers.* 配置（已配置: " + providers.keySet() + "）");
        }
        String connProtocol = cfg.getChat() == null ? null : cfg.getChat().getProtocol();
        if (StrUtil.isBlank(req.getProtocol())) {
            req.setProtocol(connProtocol);   // 缺省取连接协议
        } else if (connProtocol != null && !req.getProtocol().equalsIgnoreCase(connProtocol)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "模型协议 " + req.getProtocol() + " 与连接 [" + req.getProvider()
                            + "] 的协议 " + connProtocol + " 不一致");
        }
        // value 重名拦截（排除自身）。表无唯一键，靠这里给可读提示。
        Long dupId = TenantContext.runAsSystem(() -> {
            AiModel exist = aiModelMapper.selectOne(
                    Wrappers.<AiModel>lambdaQuery().eq(AiModel::getValue, req.getValue()).last("limit 1"));
            return exist == null ? null : exist.getId();
        });
        if (dupId != null && !dupId.equals(selfId)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "模型 value [" + req.getValue() + "] 已存在");
        }
    }

    private void apply(AiModel m, ModelUpsertRequest req) {
        m.setValue(req.getValue().trim());
        m.setLabel(req.getLabel().trim());
        m.setDescription(req.getDescription());
        m.setProtocol(req.getProtocol());
        m.setProvider(req.getProvider());
        // upstream 留空时默认 = value（直连场景一致）。
        m.setUpstreamModel(StrUtil.isBlank(req.getUpstreamModel()) ? req.getValue().trim() : req.getUpstreamModel().trim());
        m.setMaxTemp(req.getMaxTemp() == null ? 1.0 : req.getMaxTemp());
        m.setPriceInput(nz(req.getPriceInput()));
        m.setPriceOutput(nz(req.getPriceOutput()));
        m.setPriceCacheRead(nz(req.getPriceCacheRead()));
        m.setPriceCacheWrite(nz(req.getPriceCacheWrite()));
        m.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
        m.setSort(req.getSort() == null ? 0 : req.getSort());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
