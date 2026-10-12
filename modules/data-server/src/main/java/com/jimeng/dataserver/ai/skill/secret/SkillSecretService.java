package com.jimeng.dataserver.ai.skill.secret;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.http.SkillKey;
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
     * 上一版的 key（{@link PreviousKeys}），发布时最先调（R23）：<b>一次把上一版所有 key 解密成一份快照</b>，草稿读取（报错抹值）和发布处理器
     * 共用这一份，之后只是查表、不再解密。解出来的值只在这一次发布的内存里。上一版没有 key 行时直接是 {@link PreviousKeys#NONE}，不需要密钥。
     *
     * <p>解不开的按 key 处理（R27）：<b>没配加密密钥</b>就拒绝（部署配置错了，跳过核对等于放行，新值也没法加密）；
     * <b>配了密钥、但某个 key 的密文解不开</b>（密钥换过、密文坏了）只有这个 key 不能沿用——它的名字在 {@code names()} 里、值不在，
     * 于是 {@link PreviousKeys#undecryptable()} 就是它；由发布处理器在它没写 value 时拒绝、给了新值或去掉了就照常发布。
     * 不然加密密钥一换，这个 skill 就再也不能用「用 AI 改进」发布了。
     */
    public PreviousKeys previousKeys(Long skillId, Integer version) {
        Map<String, SkillSecret> rows = previous(skillId, version);
        if (rows.isEmpty()) return PreviousKeys.NONE;
        if (!cipher.isAvailable()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "加密密钥没有配置（CONNECTION_CREDENTIAL_KEY），请联系管理员");
        }
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> failed = new LinkedHashSet<>();
        for (SkillSecret row : rows.values()) {
            String value = decryptOrNull(row);
            if (value != null) {
                values.put(row.getName(), value);
            } else {
                failed.add(row.getName());
            }
        }
        // 这里不记日志：解不开的 key 的名字里可能正好含有这一版某个 key 的值，要等发布处理器的名字检查过了，
        // 由发布入口抹过值再记那一条 WARN（SkillBuilderPublishService）
        return PreviousKeys.decrypted(values, failed);
    }

    /**
     * 解一条 key 行；解不开（没有密文或版本号、密文坏了、密钥换过）返回 null。这是预期情况（R27），不抛、不记 ERROR：
     * 用 {@link CredentialCipher#decryptOrNull}，不用 {@code decrypt}（它每次都构造 ServiceException、记一条 ERROR）。
     * 没配密钥要整体拒绝的，调用方先查 {@code cipher.isAvailable()}。
     */
    private String decryptOrNull(SkillSecret row) {
        return row.getCipherVersion() == null ? null : cipher.decryptOrNull(row.getCiphertext(), row.getCipherVersion());
    }

    private final Set<String> warnedUnavailable = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 授权版本的 key；单条解不开仍返回不可用项，只有持久层读取失败才整体失败。 */
    public List<SkillKey> loadKeys(List<SkillGrant> grants) {
        if (grants == null) return List.of();
        Map<Long, SkillGrant> byId = new LinkedHashMap<>();
        for (SkillGrant g : grants) {
            if (g.skillId() != null && !g.manifest().keys().isEmpty()) byId.putIfAbsent(g.skillId(), g);
        }
        if (byId.isEmpty()) return List.of();
        List<SkillKey> out = new ArrayList<>();
        for (SkillSecret r : mapper.selectList(new LambdaQueryWrapper<SkillSecret>().in(SkillSecret::getSkillId, byId.keySet()))) {
            SkillGrant g = byId.get(r.getSkillId());
            if (g == null || !Objects.equals(r.getVersion(), g.version())) continue;
            String value = decryptOrNull(r);
            if (value == null && warnedUnavailable.add(r.getSkillId() + ":" + r.getVersion() + ":" + r.getName())) {
                log.warn("skill key 无法解密 skillId={} v{} name={}", r.getSkillId(), r.getVersion(), r.getName());
            }
            out.add(new SkillKey(r.getName(), r.getSkillId(), g.skillName(), asList(r.getHosts()), asList(r.getMethods()),
                    asList(r.getPaths()), value));
        }
        return out;
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
