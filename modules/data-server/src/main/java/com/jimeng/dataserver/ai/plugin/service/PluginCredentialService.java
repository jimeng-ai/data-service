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
import java.util.Map;

/**
 * 凭证服务。
 *
 * <p>每个插件在租户内仅有一份凭证（{@code uk(tenant_id, plugin_id)}），不再支持 alias / is_default。
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
     */
    public Map<String, Object> resolveSecrets(Long pluginId) {
        PluginCredential cred = findByPlugin(pluginId);
        if (cred == null) {
            throw new CredentialMissingException("凭证缺失: pluginId=" + pluginId);
        }
        return parseCredentialJson(cred);
    }

    public PluginCredential findByPlugin(Long pluginId) {
        LambdaQueryWrapper<PluginCredential> wrapper = new LambdaQueryWrapper<PluginCredential>()
                .eq(PluginCredential::getPluginId, pluginId)
                .last("LIMIT 1");
        return credentialMapper.selectOne(wrapper);
    }

    /**
     * upsert：插件在租户内有凭证则更新，否则插入。返回最终落库实体。
     */
    public PluginCredential save(PluginCredential entity) {
        if (entity.getEncryptionVersion() == null) {
            entity.setEncryptionVersion(0);
        }
        PluginCredential existing = findByPlugin(entity.getPluginId());
        if (existing != null) {
            existing.setCredentialData(entity.getCredentialData());
            existing.setEncryptionVersion(entity.getEncryptionVersion());
            if (entity.getOwnerId() != null) {
                existing.setOwnerId(entity.getOwnerId());
            }
            credentialMapper.updateById(existing);
            return existing;
        }
        credentialMapper.insert(entity);
        return entity;
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
