package com.jimeng.dataserver.ai.skill.secret;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SecretValues;
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
import java.util.LinkedHashSet;
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
        // 所有拼了名字的异常文案都过这一个替换 helper：ServiceException 构造时就把文案记进 ERROR 日志。名字里本不该有值
        // （发布处理器在读 secrets.json 时就拒了），这是第二道
        SecretValues known = SecretValues.ofEntries(entries);
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
                            known.replace("key " + e.name() + " 没有给值：第一次出现的 key 必须在 secrets.json 里写 value"));
                }
                if (!sameScope(old, row)) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST, known.replace("key " + e.name()
                            + " 的发送范围（hosts / methods / paths）改了，旧值已作废，请在 secrets.json 里重新写 value"));
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
     * 上一版的 key，给发布处理器核对文件用（{@link PreviousKeys}）。名字不需要密钥；解密只在被问到值的时候做。解出来的值只在这一次发布的内存里。
     *
     * <p>解不开的按 key 处理（R27）：<b>没配加密密钥</b>就拒绝（部署配置错了，跳过核对等于放行，新值也没法加密）；
     * <b>配了密钥、但某个 key 的密文解不开</b>（密钥换过、密文坏了）只有这个 key 不能沿用——它不在返回的值里，记在
     * {@link PreviousKeys.Opened#undecryptable} 里，记一条 WARN（只带 skill id 和 key 名），由发布处理器在它没写 value 时拒绝、
     * 给了新值或去掉了就照常发布。不然加密密钥一换，这个 skill 就再也不能用「用 AI 改进」发布了。
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
                return decrypt(rows, names.stream().filter(rows::containsKey).toList()).values();
            }

            @Override
            public Opened open() {
                return decrypt(rows, List.copyOf(rows.keySet()));
            }
        };
    }

    private PreviousKeys.Opened decrypt(Map<String, SkillSecret> rows, List<String> wanted) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> failed = new LinkedHashSet<>();
        if (wanted.isEmpty()) return new PreviousKeys.Opened(values, failed);
        if (!cipher.isAvailable()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "加密密钥没有配置（CONNECTION_CREDENTIAL_KEY），请联系管理员");
        }
        for (String name : wanted) {
            SkillSecret row = rows.get(name);
            try {
                if (row.getCipherVersion() == null) throw new IllegalStateException("没有密文版本号");
                String value = cipher.decrypt(row.getCiphertext(), row.getCipherVersion());
                if (value == null) throw new IllegalStateException("没有密文");
                values.put(name, value);
            } catch (RuntimeException e) {
                // 不带原因：解密失败的细节对调用方没用；日志里也只留 skill id 和 key 名
                log.warn("skill key 解密失败，这一版不能沿用它 skillId={} name={}", row.getSkillId(), name);
                failed.add(name);
            }
        }
        return new PreviousKeys.Opened(values, failed);
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
