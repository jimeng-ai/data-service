package com.jimeng.dataserver.ai.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.PluginCredential;
import com.jimeng.persistence.mapper.PluginCredentialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证服务。
 *
 * <p>当前版本明文存储：{@code credential_data} 直接是 JSON 字符串，
 * 解析后作为 {@code secrets} 命名空间塞进 {@link com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext}。
 *
 * <p>未来加密时 {@code encryption_version > 0}，在 {@link #parseCredentialJson} 里按版本走解密路径。
 *
 * <p>所有读取依赖 MyBatis-Plus 多租户拦截器自动注入 {@code WHERE tenant_id = ?}——所以业务代码无需关心租户隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginCredentialService {

    /** 凭证缺失异常 */
    public static class CredentialMissingException extends RuntimeException {
        public CredentialMissingException(String message) { super(message); }
    }

    private final PluginCredentialMapper credentialMapper;

    /**
     * 解析出可直接用作 {@code secrets} 命名空间的 Map。
     *
     * @param pluginId 插件 ID
     * @param alias    凭证别名；为空走 {@code is_default=true}
     */
    public Map<String, Object> resolveSecrets(Long pluginId, String alias) {
        PluginCredential cred = findCredential(pluginId, alias);
        if (cred == null) {
            throw new CredentialMissingException(
                    "凭证缺失: pluginId=" + pluginId + ", alias=" + (alias == null ? "<default>" : alias));
        }
        return parseCredentialJson(cred);
    }

    public PluginCredential findCredential(Long pluginId, String alias) {
        LambdaQueryWrapper<PluginCredential> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PluginCredential::getPluginId, pluginId);
        if (StringUtils.hasText(alias)) {
            wrapper.eq(PluginCredential::getAlias, alias);
        } else {
            wrapper.eq(PluginCredential::getIsDefault, Boolean.TRUE);
        }
        wrapper.last("LIMIT 1");
        return credentialMapper.selectOne(wrapper);
    }

    public List<PluginCredential> listByPlugin(Long pluginId) {
        LambdaQueryWrapper<PluginCredential> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PluginCredential::getPluginId, pluginId);
        wrapper.orderByDesc(PluginCredential::getIsDefault);
        wrapper.orderByAsc(PluginCredential::getAlias);
        return credentialMapper.selectList(wrapper);
    }

    public PluginCredential create(PluginCredential entity) {
        if (entity.getEncryptionVersion() == null) {
            entity.setEncryptionVersion(0);
        }
        if (entity.getIsDefault() == null) {
            entity.setIsDefault(Boolean.FALSE);
        }
        if (!StringUtils.hasText(entity.getAlias())) {
            entity.setAlias("default");
        }
        credentialMapper.insert(entity);
        return entity;
    }

    public PluginCredential update(PluginCredential entity) {
        credentialMapper.updateById(entity);
        return entity;
    }

    public int deleteById(Long id) {
        return credentialMapper.deleteById(id);
    }

    private Map<String, Object> parseCredentialJson(PluginCredential cred) {
        int version = cred.getEncryptionVersion() == null ? 0 : cred.getEncryptionVersion();
        if (version != 0) {
            // 预留：未来切加密时按版本走对应解密
            log.warn("凭证 encryption_version={} 尚未实现，按明文当作 fallback 解析", version);
        }
        String raw = cred.getCredentialData();
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return CommonUtil.getObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            throw new CredentialMissingException("凭证 JSON 解析失败: id=" + cred.getId() + ", error=" + e.getMessage());
        }
    }
}
