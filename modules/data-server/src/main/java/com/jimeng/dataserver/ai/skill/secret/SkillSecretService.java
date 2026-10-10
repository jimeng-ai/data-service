package com.jimeng.dataserver.ai.skill.secret;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.SkillSecretMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 企业 skill 的 key：加密、沿用 / 作废、落库（设计文档 §3.4），以及给网关解密（Task 6 加 {@code loadKeys}）。
 *
 * <p>★ 这里经手明文。日志只准写 key 名和 skillId，绝不写值、密文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSecretService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillSecretMapper mapper;
    private final CredentialCipher cipher;

    /**
     * 算好这一版要写的 key 行（已加密），<b>不落库</b>：落库由 {@link #save} 在发布的同一个事务里做。
     * 发布入口要在动存储之前调它——算不过（没给值、范围变了、没配密钥）就整次发布失败，什么都不写。
     *
     * @param previousVersion 改进已有 skill 时是当前版本号；新建时为 null
     */
    public List<SkillSecret> prepare(String tenantId, Long skillId, Integer previousVersion,
                                     List<SkillSecretsFile.Entry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        boolean needsCipher = entries.stream().anyMatch(e -> e.value() != null);
        if (needsCipher && !cipher.isAvailable()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "平台没有配置加密密钥（CONNECTION_CREDENTIAL_KEY），不能保存技能里的 key，请联系管理员");
        }
        Map<String, SkillSecret> previous = previous(skillId, previousVersion);
        List<SkillSecret> rows = new ArrayList<>();
        for (SkillSecretsFile.Entry e : entries) {
            SkillSecret row = new SkillSecret();
            row.setTenantId(tenantId);
            row.setName(e.name());
            row.setHosts(toJson(e.hosts()));
            row.setMethods(e.methods() == null ? null : toJson(e.methods()));
            row.setPaths(e.paths() == null ? null : toJson(e.paths()));
            if (e.value() != null) {
                row.setCiphertext(cipher.encrypt(e.value()));
                row.setCipherVersion(CredentialCipher.CURRENT_VERSION);
            } else {
                SkillSecret old = previous.get(e.name());
                if (old == null) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                            "key " + e.name() + " 没有给值：第一次出现的 key 必须在 secrets.json 里写 value");
                }
                if (!sameScope(old, row)) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, "key " + e.name()
                            + " 的发送范围（hosts / methods / paths）改了，旧值已作废，请在 secrets.json 里重新写 value");
                }
                row.setCiphertext(old.getCiphertext());
                row.setCipherVersion(old.getCipherVersion());
            }
            rows.add(row);
        }
        return rows;
    }

    /** 与 ai_skill / ai_skill_version 在同一个事务里调用。 */
    public void save(Long skillId, int version, List<SkillSecret> rows) {
        for (SkillSecret r : rows) {
            r.setId(null);
            r.setSkillId(skillId);
            r.setVersion(version);
            mapper.insert(r);
        }
        if (!rows.isEmpty()) {
            log.info("skill key 已加密保存 skillId={} v{} names={}", skillId, version,
                    rows.stream().map(SkillSecret::getName).toList());
        }
    }

    /** 删 skill 时一并删掉它所有版本的 key。物理删除：密文不留在表里（见 {@link SkillSecretMapper#deleteBySkillId}）。 */
    public void deleteAll(Long skillId) {
        if (skillId == null) return;
        mapper.deleteBySkillId(skillId);
    }

    /**
     * 上一版的 key，给发布处理器核对文件用（{@link PreviousKeys}）。名字不需要密钥；解密只在被问到值的时候做，
     * 没配密钥或解不开就拒绝，绝不返回「查不到」——跳过核对等于放行。解出来的值只在这一次发布的内存里。
     */
    public PreviousKeys previousKeys(Long skillId, Integer version) {
        Map<String, SkillSecret> rows = previous(skillId, version);
        return new PreviousKeys() {
            @Override
            public Set<String> names() {
                return Set.copyOf(rows.keySet());
            }

            @Override
            public Map<String, String> values(Collection<String> names) {
                Map<String, String> out = new LinkedHashMap<>();
                List<String> wanted = names.stream().filter(rows::containsKey).toList();
                if (wanted.isEmpty()) return out;
                if (!cipher.isAvailable()) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                            "无法核对上一版的 key：平台没有配置加密密钥（CONNECTION_CREDENTIAL_KEY），请联系管理员");
                }
                for (String name : wanted) {
                    out.put(name, decrypt(name, rows.get(name)));
                }
                return out;
            }
        };
    }

    private String decrypt(String name, SkillSecret row) {
        try {
            if (row.getCipherVersion() == null) throw new IllegalStateException("没有密文版本号");
            String value = cipher.decrypt(row.getCiphertext(), row.getCipherVersion());
            if (value == null) throw new IllegalStateException("没有密文");
            return value;
        } catch (RuntimeException e) {
            // 不带原因：解密失败的细节对调用方没用；日志里也只留 key 名
            log.warn("skill key 解密失败 skillId={} v{} name={}", row.getSkillId(), row.getVersion(), name);
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "无法核对上一版的 key " + name + "：解密失败，可能是加密密钥已轮换；请在 secrets.json 里重新写 value");
        }
    }

    /** 某一版的 key 声明（名字和发送范围，不含值），来源是 skill_secret 行——key 能发到哪里只看这张表，manifest 只是给人看的副本。 */
    public List<SkillManifest.KeyDecl> declarationsOf(Long skillId, Integer version) {
        return previous(skillId, version).values().stream()
                .sorted(Comparator.comparing(SkillSecret::getName))
                .map(r -> new SkillManifest.KeyDecl(r.getName(), asList(r.getHosts()), asList(r.getMethods()), asList(r.getPaths())))
                .toList();
    }

    private Map<String, SkillSecret> previous(Long skillId, Integer version) {
        Map<String, SkillSecret> out = new LinkedHashMap<>();
        if (skillId == null || version == null) return out;
        for (SkillSecret s : mapper.selectList(new LambdaQueryWrapper<SkillSecret>()
                .eq(SkillSecret::getSkillId, skillId).eq(SkillSecret::getVersion, version))) {
            out.put(s.getName(), s);
        }
        return out;
    }

    /** 范围按集合比（顺序无关）；methods / paths 的 null（不限）只等于 null。 */
    private static boolean sameScope(SkillSecret a, SkillSecret b) {
        return Objects.equals(asSet(a.getHosts()), asSet(b.getHosts()))
                && Objects.equals(asSet(a.getMethods()), asSet(b.getMethods()))
                && Objects.equals(asSet(a.getPaths()), asSet(b.getPaths()));
    }

    static HashSet<String> asSet(String jsonArray) {
        List<String> list = asList(jsonArray);
        return list == null ? null : new HashSet<>(list);
    }

    static List<String> asList(String jsonArray) {
        if (jsonArray == null) return null;
        try {
            return JSON.readValue(jsonArray, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("skill_secret 里的范围不是合法的 JSON 数组", e);
        }
    }

    static String toJson(List<String> list) {
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
