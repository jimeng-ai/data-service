package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackageProcessorTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";
    private static final String TOKEN_PREFIX = "sk-live-CRM";   // 另一个 key 的值恰好是它的前缀：验证「长的先换」

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> skill(String body) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n" + body));
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]},"
                + "\"CRM_PREFIX\":{\"value\":\"" + TOKEN_PREFIX + "\",\"hosts\":[\"api.customer.com\"]}}"));
        return files;
    }

    @Test
    @DisplayName("secrets.json 从包里拿掉；SKILL.md 正文和其余文本文件里的 key 原文换成 {{名字}}（长的先换）")
    void extractsAndReplaces() {
        Map<String, byte[]> files = skill("调接口时带上 " + TOKEN + "\n");
        files.put("references/api.md", b("Authorization: Bearer " + TOKEN + "\nX-Prefix: " + TOKEN_PREFIX));
        files.put("scripts/call.py", b("TOKEN = '" + TOKEN + "'\n"));
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true);
        assertThat(out.files()).doesNotContainKey("secrets.json").containsKeys("SKILL.md", "references/api.md", "scripts/call.py");
        assertThat(out.skill().body()).isEqualTo("调接口时带上 {{CRM_TOKEN}}\n");
        assertThat(new String(out.files().get("references/api.md"), StandardCharsets.UTF_8))
                .isEqualTo("Authorization: Bearer {{CRM_TOKEN}}\nX-Prefix: {{CRM_PREFIX}}");
        assertThat(new String(out.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\n");
        assertThat(out.secrets()).extracting(SkillSecretsFile.Entry::name).containsExactlyInAnyOrder("CRM_TOKEN", "CRM_PREFIX");
        assertThat(out.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("二进制文件里有 key 原文：没法换成占位符，整次发布拒绝；二进制里没有就原样保留")
    void binaryWithLiteralIsRejected() {
        Map<String, byte[]> files = skill("正文\n");
        byte[] bin = new byte[]{0, 1, 2};
        files.put("assets/logo.png", bin);
        assertThat(SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true).files().get("assets/logo.png"))
                .isSameAs(bin);
        byte[] leaky = ("\0PNG" + TOKEN).getBytes(StandardCharsets.UTF_8);
        files.put("assets/leak.bin", leaky);
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("assets/leak.bin").hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("平台 skill（allowSecrets=false）带 secrets.json 直接拒绝")
    void platformRejectsSecrets() {
        assertThatThrownBy(() -> SkillPackageProcessor.process(skill("正文\n"), SkillManifestParser.Mode.STRICT, false))
                .isInstanceOf(ServiceException.class).hasMessageContaining("secrets.json");
    }

    @Test
    @DisplayName("secrets.json 不合规：整次发布拒绝，报错逐条列出；没有 SKILL.md 也拒绝")
    void invalidSecretsOrMissingSkillMd() {
        Map<String, byte[]> files = skill("正文\n");
        files.put("secrets.json", b("{\"crm\":{\"value\":\"x\"}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("crm");
        assertThatThrownBy(() -> SkillPackageProcessor.process(Map.of("x.md", b("x")), SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("SKILL.md");
    }

    @Test
    @DisplayName("没有 secrets.json 时文件原样返回，SKILL.md 照常解析")
    void noSecrets() {
        Map<String, byte[]> files = Map.of("SKILL.md", b("---\nname: a\ndescription: d\n---\nb\n"));
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(files, SkillManifestParser.Mode.LENIENT, true);
        assertThat(out.secrets()).isEmpty();
        assertThat(out.files().get("SKILL.md")).isSameAs(files.get("SKILL.md"));
    }
    // ---------------------------------------------------------------- 第 1 轮修复

    /** 只带 hosts、不带 value 的 secrets.json：沿用上一版的值。 */
    private static final String REUSE_JSON = "{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}";

    /** 上一版有 CRM_TOKEN（值是 TOKEN）。 */
    private static PreviousKeys previousHas(String... names) {
        return new PreviousKeys() {
            @Override
            public Set<String> names() {
                return Set.of(names);
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                Map<String, String> out = new LinkedHashMap<>();
                for (String n : wanted) if (n.equals("CRM_TOKEN")) out.put(n, TOKEN);
                return out;
            }
        };
    }

    private static Map<String, byte[]> reuseSkill(String body) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n" + body));
        files.put("secrets.json", b(REUSE_JSON));
        return files;
    }

    @Test
    @DisplayName("★ 沿用上一版的 key：把上一版的值解密出来并进替换——改进会话里模型又把 key 原文写进脚本，发布后 bundle 里也是 {{名字}}")
    void reusedValuesAreReplacedToo() {
        Map<String, byte[]> files = reuseSkill("带上 {{CRM_TOKEN}} 调接口\n");
        files.put("scripts/call.py", b("TOKEN = '" + TOKEN + "'\n"));
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN"));
        assertThat(new String(out.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\n");
        assertThat(out.secrets()).singleElement().extracting(SkillSecretsFile.Entry::value).isNull();
        assertThat(out.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("沿用的值出现在二进制文件里：同样整次拒绝，文案不带值")
    void reusedValueInBinaryIsRejected() {
        Map<String, byte[]> files = reuseSkill("正文\n");
        files.put("assets/leak.bin", ("\0PNG" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("assets/leak.bin").hasMessageContaining("CRM_TOKEN")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("沿用的值出现在 secrets.json 里别的 key 的 paths 里：拒绝，文案点名位置、不带值")
    void reusedValueInAnotherKeysPathsIsRejected() {
        Map<String, byte[]> files = reuseSkill("正文\n");
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]},"
                + "\"HOOK_KEY\":{\"value\":\"another-secret-value\",\"hosts\":[\"hook.example.com\"],\"paths\":[\"/hook/" + TOKEN + "\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN").hasMessageContaining("HOOK_KEY").hasMessageContaining("paths")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("★ 解不出沿用的值（没配密钥、密文坏了）：拒绝发布，不是跳过核对")
    void unavailablePreviousValuesRejectThePublish() {
        PreviousKeys cannotDecrypt = new PreviousKeys() {
            @Override
            public Set<String> names() {
                return Set.of("CRM_TOKEN");
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                throw new ServiceException(com.jimeng.common.core.enums.ExceptionCode.INVALID_REQUEST, "无法核对沿用的 key：平台没有配置加密密钥");
            }
        };
        assertThatThrownBy(() -> SkillPackageProcessor.process(reuseSkill("正文\n"), SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无法核对沿用的 key");
    }

    @Test
    @DisplayName("★ 上一版有的 key 这一版的 secrets.json 里没了、文件里还在用 {{X}}：拒绝（否则新版本静默丢 key）；留着、不用了、从没声明过的占位符都放行")
    void aDroppedKeyThatIsStillUsedIsRejected() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n带上 {{CRM_TOKEN}} 调接口\n"));
        files.put("secrets.json", b("{\"OTHER_KEY\":{\"hosts\":[\"api.customer.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN", "OTHER_KEY")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("{{CRM_TOKEN}}").hasMessageContaining("SKILL.md")
                .hasMessageContaining("不写值即沿用");
        // 整个 secrets.json 都没了，也一样
        files.remove("secrets.json");
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("{{CRM_TOKEN}}");
        // 这一条保留（不写值）：放行
        assertThat(SkillPackageProcessor.process(reuseSkill("带上 {{CRM_TOKEN}}\n"), SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")).files())
                .containsKey("SKILL.md");
        // 不再用了：放行
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n不用 key 了\n"));
        assertThat(SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")).files()).containsKey("SKILL.md");
        // 从没声明过的占位符：发布时不管（网关运行时会拒绝）
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n写了 {{NEVER_DECLARED}}\n"));
        assertThat(SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")).files()).containsKey("SKILL.md");
    }

    @Test
    @DisplayName("★ 先抹值、再解析：frontmatter 里「token: <值>」会触发 YAML 报错，报错片段里只能有 {{名字}}——"
            + "解析器把报错截成 160 个字，值正好被截断时，「解析之后再抹」会留下值的前半截")
    void yamlErrorsCarryNoValue() {
        // description 里的第二个冒号让 YAML 报错；报错文案是「…line 2, column 15: …a: 字字…sk-live-CR …」，值被 160 字的截断切在了中间
        String truncatedMidValue = "---\nname: refund-flow\ndescription: a: " + "字".repeat(20) + TOKEN + " 调接口\n---\n正文\n";
        Map<String, byte[]> files = skill("正文\n");
        files.put("SKILL.md", b(truncatedMidValue));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("SKILL.md").hasMessageContaining("YAML")
                .hasMessageNotContaining("sk-live").hasMessageNotContaining(TOKEN);
        // 沿用的值也一样
        Map<String, byte[]> reuse = reuseSkill("正文\n");
        reuse.put("SKILL.md", b(truncatedMidValue));
        assertThatThrownBy(() -> SkillPackageProcessor.process(reuse, SkillManifestParser.Mode.STRICT, true, previousHas("CRM_TOKEN")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("SKILL.md").hasMessageNotContaining("sk-live");
        // 最常见的写法：「token: <值>」
        Map<String, byte[]> plain = skill("正文\n");
        plain.put("SKILL.md", b("---\nname: refund-flow\ndescription: 查订单时用 token: " + TOKEN + " 调接口\n---\n正文\n"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(plain, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("{{CRM_TOKEN}}").hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("★ 值出现在文件的路径里：整次发布拒绝（路径会变成对象名、详情页的 path、沙箱的 relPath），文案点名 key，路径里的值已抹")
    void aValueInAFilePathIsRejected() {
        Map<String, byte[]> files = skill("正文\n");
        files.put("scripts/" + TOKEN + ".py", b("print(1)"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN").hasMessageContaining("路径")
                .hasMessageContaining("scripts/{{CRM_TOKEN}}.py").hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("★ 值写进 paths（webhook 写法）：整次发布拒绝，文案点名 key 和 paths，不带值")
    void aValueInPathsIsRejected() {
        Map<String, byte[]> files = skill("正文\n");
        files.put("secrets.json", b("{\"HOOK_KEY\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"hook.example.com\"],\"paths\":[\"/open-apis/bot/v2/hook/" + TOKEN + "\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("HOOK_KEY").hasMessageContaining("paths").hasMessageContaining("通配")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("两个 key 的值相同：拒绝，文案是「key A 和 key B 的值相同」、不带值")
    void twoKeysWithTheSameValueAreRejected() {
        Map<String, byte[]> files = skill("正文\n");
        files.put("secrets.json", b("{\"KEY_A\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"a.example.com\"]},"
                + "\"KEY_B\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"b.example.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                .isInstanceOf(ServiceException.class).hasMessageContaining("key KEY_A 和 key KEY_B 的值相同").hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("根目录有 Secrets.json / SECRETS.JSON 这类只差大小写的文件：拒绝并提示要写成小写（否则当普通文件下发，里面的值是明文）")
    void caseVariantsOfSecretsJsonAreRejected() {
        for (String variant : new String[]{"Secrets.json", "SECRETS.JSON", "secrets.JSON"}) {
            Map<String, byte[]> files = skill("正文\n");
            files.put(variant, b("{}"));
            assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true))
                    .as(variant).isInstanceOf(ServiceException.class).hasMessageContaining(variant).hasMessageContaining("小写的 secrets.json");
        }
        // 子目录里的、以及名字不一样的，不受影响
        Map<String, byte[]> ok = skill("正文\n");
        ok.put("references/Secrets.json", b("{}"));
        ok.put("secrets.json.example", b("{}"));
        assertThat(SkillPackageProcessor.process(ok, SkillManifestParser.Mode.STRICT, true).files())
                .containsKeys("references/Secrets.json", "secrets.json.example");
    }
}
