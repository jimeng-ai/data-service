package com.jimeng.dataserver.ai.connector.registry;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 连接器类型注册表：{@code kind → 实现}。
 *
 * <p>形状照抄 {@code ProviderRegistry}（构造器注入 {@code List<Connector>} + {@code @PostConstruct}
 * 校验），<b>但刻意修掉了它的一个缺陷</b>：ProviderRegistry 用 {@code map.put} 灌名字，
 * 两个 bean 同名时后者直接覆盖前者，无日志无异常。这里改成重名即抛
 * {@link IllegalStateException}，让应用<b>启动失败</b>。
 *
 * <p>理由是这套系统反复栽过的那类跟头：静默覆盖不会在部署时暴露，只会在某次调用走到「错的那个实现」
 * 时表现成一个莫名其妙的行为，排查成本远大于修复成本。启动期失败是最便宜的发现时机。
 *
 * <p>构造器注入 {@code List<Connector>} 同时保证了次序：所有 Connector bean 必然先于本 bean 实例化，
 * 所以 {@link #validate()} 跑的时候拿到的一定是全集。
 */
@Slf4j
@Component
public class ConnectorRegistry {

    /** key 一律大写归一，避免 "mysql" 与 "MySQL" 被当成两种类型。 */
    private final Map<String, Connector> byKind = new LinkedHashMap<>();

    public ConnectorRegistry(List<Connector> connectors) {
        if (connectors == null) return;
        for (Connector c : connectors) {
            if (c == null) continue;
            String kind = normalize(c.kind());
            if (kind.isEmpty()) {
                throw new IllegalStateException(
                        "连接器 " + c.getClass().getName() + " 的 kind() 为空——类型标识是存量行的判别键，不能为空");
            }
            Connector prev = byKind.put(kind, c);
            if (prev != null) {
                throw new IllegalStateException("连接器类型标识重复: kind=" + kind
                        + "，冲突实现 " + prev.getClass().getName() + " 与 " + c.getClass().getName()
                        + "。重名会让「到底用哪个实现」变成不确定行为，必须在启动期解决。");
            }
        }
    }

    @PostConstruct
    void validate() {
        for (Connector c : byKind.values()) {
            if (c.paramSpec() == null) {
                throw new IllegalStateException("连接器 " + c.kind() + " 没有声明 paramSpec()——"
                        + "参数定义同时驱动前端表单与后端校验，缺了它这种类型根本无法录入");
            }
            if (c.declaredCapabilities() == null || c.declaredCapabilities().isEmpty()) {
                throw new IllegalStateException("连接器 " + c.kind() + " 没有声明任何能力——"
                        + "一个什么都不能干的连接器接进来也没有用途");
            }
        }
        log.info("连接器注册表就绪，共 {} 种类型: {}", byKind.size(), byKind.keySet());
    }

    /** 找不到即抛，且把可用类型列出来——这是「配错必须当场报错」那条纪律的一部分。 */
    public Connector require(String kind) {
        Connector c = byKind.get(normalize(kind));
        if (c == null) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                    "不支持的连接器类型 " + kind + "，当前可用: " + byKind.keySet());
        }
        return c;
    }

    public Optional<Connector> find(String kind) {
        return Optional.ofNullable(byKind.get(normalize(kind)));
    }

    public boolean supports(String kind) {
        return byKind.containsKey(normalize(kind));
    }

    public Collection<Connector> all() {
        return byKind.values();
    }

    public static String normalize(String kind) {
        return kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
    }
}
