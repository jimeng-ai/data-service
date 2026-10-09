package com.jimeng.dataserver.ai.skill.manifest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillManifestTest {

    private final Logger manifestLogger = (Logger) LoggerFactory.getLogger(SkillManifest.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = manifestLogger.getLevel();
        manifestLogger.setLevel(Level.TRACE);
        logs.start();
        manifestLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        manifestLogger.detachAppender(logs);
        logs.stop();
        manifestLogger.setLevel(previousLevel);
    }

    private List<String> errorMessages() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("JSON 往返不丢字段；数字仍是数字（不能用 Spring 那个把数字写成字符串的 ObjectMapper）")
    void roundTrip() {
        SkillManifest m = new SkillManifest(List.of("cdn.example.com"), true, 90, "connections", "always",
                List.of(new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.example.com"), List.of("GET"), List.of("/v1/**"))));
        String json = m.toJson();
        assertThat(json).contains("\"timeoutSeconds\":90").contains("\"web\":true").doesNotContain("value");
        assertThat(SkillManifest.fromJson(json)).isEqualTo(m);
    }

    @Test
    @DisplayName("KeyDecl 的 methods / paths 有无四种组合都往返不变：null（= 不限）读回来仍是 null，不会变成空列表")
    void keyDeclNullAndNonNullCombinationsRoundTrip() {
        List<String> methods = List.of("GET", "POST");
        List<String> paths = List.of("/v1/**");
        for (List<String> m : Arrays.asList(null, methods)) {
            for (List<String> p : Arrays.asList(null, paths)) {
                SkillManifest manifest = new SkillManifest(null, null, null, null, null,
                        List.of(new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.example.com"), m, p)));
                String json = manifest.toJson();
                if (m == null) assertThat(json).as("methods 为 null 时不写出").doesNotContain("methods");
                if (p == null) assertThat(json).as("paths 为 null 时不写出").doesNotContain("paths");

                SkillManifest back = SkillManifest.fromJson(json);
                assertThat(back).isEqualTo(manifest);
                SkillManifest.KeyDecl k = back.keys().get(0);
                assertThat(k.name()).isEqualTo("CRM_TOKEN");
                assertThat(k.hosts()).containsExactly("api.example.com");
                assertThat(k.methods()).isEqualTo(m);
                assertThat(k.paths()).isEqualTo(p);
            }
        }
    }

    @Test
    @DisplayName("KeyDecl 只写了名字：hosts 读出来是空列表而不是 null；methods / paths 仍是 null（= 不限）")
    void keyDeclMissingFieldsReadAsDefaults() {
        SkillManifest.KeyDecl k = SkillManifest.fromJson("{\"keys\":[{\"name\":\"CRM_TOKEN\"}]}").keys().get(0);
        assertThat(k.name()).isEqualTo("CRM_TOKEN");
        assertThat(k.hosts()).isNotNull().isEmpty();
        assertThat(k.methods()).isNull();
        assertThat(k.paths()).isNull();
        // 直接构造也一样
        assertThat(new SkillManifest.KeyDecl("K_1", null, null, null).hosts()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("KeyDecl 拷贝一份列表：构造之后再改传进去的列表不影响它，读出来的列表本身也改不了")
    void keyDeclCopiesItsLists() {
        List<String> hosts = new ArrayList<>(List.of("api.example.com"));
        List<String> methods = new ArrayList<>(List.of("GET"));
        List<String> paths = new ArrayList<>(List.of("/v1/**"));
        SkillManifest.KeyDecl k = new SkillManifest.KeyDecl("K_1", hosts, methods, paths);
        hosts.add("evil.example.com");
        methods.add("DELETE");
        paths.add("/**");
        assertThat(k.hosts()).containsExactly("api.example.com");
        assertThat(k.methods()).containsExactly("GET");
        assertThat(k.paths()).containsExactly("/v1/**");

        assertThatThrownBy(() -> k.hosts().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> k.methods().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> k.paths().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("老数据（NULL / 空串）和坏 JSON 都按「没有扩展字段、没有 key」处理")
    void emptyAndGarbage() {
        assertThat(SkillManifest.fromJson(null)).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.fromJson(" ")).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.fromJson("{not json")).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.EMPTY.hasHttp()).isFalse();
        assertThat(SkillManifest.EMPTY.timeoutSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("读出来的内容不合法（requires 认不出来、binding 不是 always / optional）按坏数据处理：EMPTY，不留到 toJm() 才抛")
    void semanticallyInvalidReadsAsEmpty() {
        assertThat(SkillManifest.fromJson("{\"hosts\":[\"api.example.com\"],\"requires\":\"bogus\"}")).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.fromJson("{\"binding\":\"sometimes\"}")).isEqualTo(SkillManifest.EMPTY);
        // 我们写入时 null 就不写这个键，写成空白的只能是被人改过的数据
        assertThat(SkillManifest.fromJson("{\"requires\":\" \"}")).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.fromJson("{\"binding\":\"\"}")).isEqualTo(SkillManifest.EMPTY);
        // JSON 的 null 字面量也不是一份 manifest
        assertThat(SkillManifest.fromJson("null")).isEqualTo(SkillManifest.EMPTY);

        // 对照：合法的值照常读出来，toJm() 不抛
        SkillManifest ok = SkillManifest.fromJson("{\"hosts\":[\"api.example.com\"],\"requires\":\"knowledge_bases\",\"binding\":\"always\"}");
        assertThat(ok.hosts()).containsExactly("api.example.com");
        assertThat(ok.requires()).isEqualTo("knowledge_bases");
        assertThat(ok.binding()).isEqualTo("always");
        assertThatCode(ok::toJm).doesNotThrowAnyException();
        assertThat(ok.toJm().requires()).isEqualTo(SkillRequirement.KNOWLEDGE_BASES);
    }

    @Test
    @DisplayName("未知字段让整份变 EMPTY：这是有意的失败即关闭（授权数据宁可声明失效，也不读一半）")
    void unknownFieldReadsAsEmpty() {
        assertThat(SkillManifest.fromJson("{\"hosts\":[\"api.example.com\"],\"futureField\":1}")).isEqualTo(SkillManifest.EMPTY);
    }

    @Test
    @DisplayName("读不出来或内容不合法时，ERROR 日志带上原文的前 200 个字符，运维才找得到是哪一行数据坏了")
    void errorLogCarriesRawPrefix() {
        // 语义不合法：Jackson 读得出来，没有任何位置信息，只能靠日志里的原文定位
        String raw = "{\"hosts\":[\"api.example.com\"],\"requires\":\"bogus\",\"keys\":[{\"name\":\"" + "K".repeat(300) + "\"}]}";
        assertThat(SkillManifest.fromJson(raw)).isEqualTo(SkillManifest.EMPTY);
        assertThat(errorMessages()).singleElement().satisfies(msg -> {
            assertThat(msg).contains(raw.substring(0, 200));
            assertThat(msg).doesNotContain(raw.substring(0, 201));
        });

        // 坏 JSON 同样带原文
        logs.list.clear();
        assertThat(SkillManifest.fromJson("{not json")).isEqualTo(SkillManifest.EMPTY);
        assertThat(errorMessages()).singleElement().satisfies(msg -> assertThat(msg).contains("{not json"));
    }

    @Test
    @DisplayName("hosts、web、key 任一出现就算有 HTTP 声明")
    void hasHttp() {
        assertThat(new SkillManifest(List.of("a.example.com"), false, null, null, null, null).hasHttp()).isTrue();
        assertThat(new SkillManifest(null, true, null, null, null, null).hasHttp()).isTrue();
        assertThat(new SkillManifest(null, false, null, null, null,
                List.of(new SkillManifest.KeyDecl("K_1", List.of("a.example.com"), null, null))).hasHttp()).isTrue();
        assertThat(new SkillManifest(null, null, 120, "connections", "always", null).hasHttp()).isFalse();
    }

    @Test
    @DisplayName("与 JmMetadata 互转不丢字段")
    void jmRoundTrip() {
        JmMetadata jm = new JmMetadata(List.of("a.example.com"), true, 30, SkillRequirement.KNOWLEDGE_BASES, "always");
        assertThat(SkillManifest.of(jm, List.of()).toJm()).isEqualTo(jm);
        assertThat(SkillManifest.EMPTY.toJm()).isEqualTo(JmMetadata.EMPTY);
    }

    @Test
    @DisplayName("「什么都没声明」只有一种表示：EMPTY 与由 JmMetadata.EMPTY 生成的 manifest（库里实际存的就是后者）相等，JSON 也一样")
    void noDeclarationHasOneRepresentation() {
        SkillManifest stored = SkillManifest.of(JmMetadata.EMPTY, List.of());
        assertThat(stored).isEqualTo(SkillManifest.EMPTY);
        assertThat(stored.toJson()).isEqualTo(SkillManifest.EMPTY.toJson());
        assertThat(SkillManifest.fromJson(stored.toJson())).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.EMPTY.binding()).isEqualTo(JmMetadata.BINDING_OPTIONAL);
    }
}
