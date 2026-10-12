package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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

    /**
     * 上一版有这些 key：CRM_TOKEN 的值是 TOKEN，别的各有一个不会出现在文件里的旧值。每个名字都要给值——在 names() 里、却拿不到值的
     * key 就是解不开的（第 4 轮 3），那是另一种情形，见 {@link #crmTokenCannotBeOpened}。
     */
    private static PreviousKeys previousHas(String... names) {
        Map<String, String> all = new LinkedHashMap<>();
        for (String n : names) all.put(n, n.equals("CRM_TOKEN") ? TOKEN : n.toLowerCase() + "-old-value-1");
        return PreviousKeys.decrypted(all);
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
                throw new ServiceException(com.jimeng.common.core.enums.ExceptionCode.INVALID_REQUEST, "加密密钥没有配置（CONNECTION_CREDENTIAL_KEY），请联系管理员");
            }
        };
        assertThatThrownBy(() -> SkillPackageProcessor.process(reuseSkill("正文\n"), SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("加密密钥没有配置");
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
                .isInstanceOf(ServiceException.class).hasMessageNotContaining(TOKEN);
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
    // ---------------------------------------------------------------- 第 2 轮：上一版的值全部解密，所有出口都用

    private static final String OLD = "693a91f6-7a2b-4c1d-9e8f-0123456789ab";   // 上一版 WECOM_HOOK 的值

    /** 上一版有 CRM_TOKEN（值是 TOKEN）和 WECOM_HOOK（值是 OLD）两个 key。 */
    private static PreviousKeys previousHasTwo() {
        return PreviousKeys.decrypted(Map.of("CRM_TOKEN", TOKEN, "WECOM_HOOK", OLD));
    }

    private static Map<String, byte[]> plainSkill() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n正文\n"));
        return files;
    }

    @Test
    @DisplayName("★ 上一版的值最先全部解密：secrets.json 不在 / 不合规 / 这一条写没写 value 都不影响；解不出来就整次拒绝，哪怕别的都对")
    void everyPreviousValueIsDecryptedFirst() {
        int[] calls = {0};
        PreviousKeys counting = new PreviousKeys() {
            @Override
            public Set<String> names() {
                return Set.of("CRM_TOKEN");
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                calls[0]++;
                return Map.of("CRM_TOKEN", TOKEN);
            }
        };
        // 没有 secrets.json、也没有任何要沿用的条目：照样解密
        SkillPackageProcessor.process(plainSkill(), SkillManifestParser.Mode.STRICT, true, counting);
        assertThat(calls[0]).isEqualTo(1);

        PreviousKeys cannotDecrypt = new PreviousKeys() {
            @Override
            public Set<String> names() {
                return Set.of("CRM_TOKEN");
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                throw new ServiceException(com.jimeng.common.core.enums.ExceptionCode.INVALID_REQUEST, "加密密钥没有配置（CONNECTION_CREDENTIAL_KEY），请联系管理员");
            }
        };
        Map<String, byte[]> noSecretsFile = plainSkill();
        assertThatThrownBy(() -> SkillPackageProcessor.process(noSecretsFile, SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("加密密钥没有配置");
        Map<String, byte[]> everyKeyHasANewValue = plainSkill();
        everyKeyHasANewValue.put("secrets.json", b("{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(everyKeyHasANewValue, SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("加密密钥没有配置");
    }

    @Test
    @DisplayName("★ 探针 B：secrets.json 校验不过（paths 里带 ?key=<旧值>），回显的条目里的旧值照样被抹")
    void anEchoedPathCarriesNoPreviousValueEvenWhenSecretsJsonIsInvalid() {
        Map<String, byte[]> files = plainSkill();
        files.put("secrets.json", b("{\"WECOM_HOOK\":{\"hosts\":[\"qyapi.weixin.qq.com\"],\"paths\":[\"/cgi-bin/webhook/send?key=" + OLD + "\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("{{WECOM_HOOK}}")
                .hasMessageNotContaining(OLD).hasMessageNotContaining(OLD.substring(0, 12));
    }

    @Test
    @DisplayName("★ 探针 C：secrets.json 另有一条错误，同时 description 里「key: <旧值>」触发 YAML 报错——旧值是已经从 secrets.json 里删掉的 key 的也一样，片段里连前半截都不能有")
    void aYamlErrorCarriesNoPartOfAPreviousValueOfADroppedKey() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 通知时用 key: " + OLD + " 发消息\n---\n正文\n"));
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"],\"methods\":[\"GTE\"]}}"));   // WECOM_HOOK 已经没了
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("GTE")
                .hasMessageNotContaining(OLD).hasMessageNotContaining(OLD.substring(0, 10));
        // secrets.json 合规、WECOM_HOOK 被删了，YAML 照样出错：这时报错来自 SKILL.md 的解析，旧值也不在里面
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class)
                .hasMessageNotContaining(OLD).hasMessageNotContaining(OLD.substring(0, 10));
    }

    @Test
    @DisplayName("★ 残留：key 删了、旧值又出现在脚本里——拒绝，点名 key 和文件，不带值，告诉作者怎么改")
    void thePreviousValueOfADroppedKeyInAScriptIsRejected() {
        Map<String, byte[]> files = plainSkill();
        files.put("scripts/call.py", b("TOKEN = '" + TOKEN + "'\n"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("scripts/call.py").hasMessageContaining("CRM_TOKEN")
                .hasMessageContaining("上一版的值").hasMessageContaining("{{CRM_TOKEN}}").hasMessageContaining("删掉")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    @DisplayName("★ 残留：key 给了新值、旧值还留在脚本里——拒绝；新值照样被替换")
    void aNewValueDoesNotExcuseTheOldOneStillInAScript() {
        Map<String, byte[]> files = plainSkill();
        files.put("scripts/call.py", b("OLD = '" + TOKEN + "'\nNEW = 'sk-live-NEW-9876543210'\n"));
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("scripts/call.py").hasMessageContaining("CRM_TOKEN")
                .hasMessageNotContaining(TOKEN).hasMessageNotContaining("sk-live-NEW");
        // 旧值不在文件里了：放行，新值被替换
        files.put("scripts/call.py", b("NEW = 'sk-live-NEW-9876543210'\n"));
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo());
        assertThat(new String(out.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("NEW = '{{CRM_TOKEN}}'\n");
    }

    @Test
    @DisplayName("残留：旧值出现在文件名里、二进制文件里、任何 key 的 paths / hosts 里——都拒绝，文案里的位置已抹值")
    void thePreviousValueInANameInABinaryOrInADeclarationIsRejected() {
        Map<String, byte[]> named = plainSkill();
        named.put("scripts/" + TOKEN + ".py", b("print(1)"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(named, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("路径").hasMessageContaining("scripts/{{CRM_TOKEN}}.py")
                .hasMessageNotContaining(TOKEN);

        Map<String, byte[]> binary = plainSkill();
        binary.put("assets/leak.bin", ("\0PNG" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SkillPackageProcessor.process(binary, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("assets/leak.bin").hasMessageContaining("上一版的值")
                .hasMessageNotContaining(TOKEN);

        Map<String, byte[]> inPaths = plainSkill();
        inPaths.put("secrets.json", b("{\"HOOK_KEY\":{\"value\":\"another-secret-1\",\"hosts\":[\"hook.example.com\"],\"paths\":[\"/hook/" + TOKEN + "\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(inPaths, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN 上一版的值").hasMessageContaining("HOOK_KEY").hasMessageContaining("paths")
                .hasMessageNotContaining(TOKEN);

        Map<String, byte[]> inHosts = plainSkill();
        inHosts.put("secrets.json", b("{\"HOOK_KEY\":{\"value\":\"another-secret-1\",\"hosts\":[\"" + TOKEN.toLowerCase() + ".example.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(inHosts, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN 上一版的值").hasMessageContaining("hosts")
                .hasMessageNotContaining(TOKEN.toLowerCase());
    }

    @Test
    @DisplayName("不算残留：沿用的条目（旧值已按它换成占位符）；旧值同时也是这一版某个 key 的值（key 改了名、值没变，已按新 key 换掉）")
    void replacedPreviousValuesAreNotResidue() {
        Map<String, byte[]> reuse = plainSkill();
        reuse.put("scripts/call.py", b("TOKEN = '" + TOKEN + "'\n"));
        reuse.put("secrets.json", b("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"));
        SkillPackageProcessor.Output kept = SkillPackageProcessor.process(reuse, SkillManifestParser.Mode.STRICT, true, previousHasTwo());
        assertThat(new String(kept.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\n");

        Map<String, byte[]> renamed = plainSkill();
        renamed.put("scripts/call.py", b("TOKEN = '" + TOKEN + "'\n"));
        renamed.put("secrets.json", b("{\"CRM_API_KEY\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}"));
        SkillPackageProcessor.Output moved = SkillPackageProcessor.process(renamed, SkillManifestParser.Mode.STRICT, true, previousHasTwo());
        assertThat(new String(moved.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("TOKEN = '{{CRM_API_KEY}}'\n");
    }

    // ---------------------------------------------------------------- 第 2 轮 C：值集合确定不了

    @Test
    @DisplayName("★ R22：值集合确定不了（写到一半 / key 超过 32 个 / 候选值超过 64 个 / 值超过 8192 个字符 / 文件超过 512 KB / 嵌套太深）："
            + "发布只报 secrets.json 自己的那一句固定文案，不往下逐条校验、不回显条目，SKILL.md 的解析错误也不报")
    void anUndeterminableSecretsJsonIsRejectedWithOnlyTheFixedSentence() {
        String marker = "MARKER-do-not-echo";
        for (Map.Entry<String, String> broken : SecretsJsonFixtures.undeterminable(TOKEN, marker).entrySet()) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", b("---\nname: refund-flow\ndescription: a: " + "字".repeat(20) + TOKEN + " 调接口\n---\n正文\n"));
            files.put("scripts/call.py", b("TOKEN = '" + TOKEN + "'\n"));
            files.put("secrets.json", b(broken.getValue()));

            assertThatThrownBy(() -> SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previousHasTwo()))
                    .as(broken.getKey())
                    .isInstanceOf(ServiceException.class)
                    .satisfies(e -> {
                        String m = e.getMessage();
                        assertThat(m).startsWith("secrets.json 不符合规则：");
                        assertThat(m.substring("secrets.json 不符合规则：".length())).doesNotContain("；");   // 就一句
                        assertThat(m).doesNotContain(marker).doesNotContain(TOKEN).doesNotContain(OLD).doesNotContain("YAML").doesNotContain("SKILL.md");
                    });
        }
    }

    // ---------------------------------------------------------------- 第 3 轮 1：残留检查在原文上做，按「每个字符都被某次替换盖住」判

    private static final String AWS_ID = "AKIAIOSFODNN7EXAMPLE";
    private static final String AWS_COMBINED = AWS_ID + ":wJalrXUtnFEMIK7MDENG";   // 上一版 AWS_CRED 的值：access key id + secret 拼在一起
    private static final String MD = "---\nname: refund-flow\ndescription: x\n---\n正文\n";

    private static Map<String, byte[]> skillWith(String scriptText, String secretsJson) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b(MD));
        files.put("scripts/call.py", b(scriptText));
        files.put("secrets.json", b(secretsJson));
        return files;
    }

    private static ServiceException publishing(Map<String, byte[]> files, PreviousKeys previous) {
        try {
            SkillPackageProcessor.process(files, SkillManifestParser.Mode.STRICT, true, previous);
        } catch (ServiceException e) {
            return e;
        }
        throw new AssertionError("本该拒绝发布，却发布成功了");
    }

    @Test
    @DisplayName("★ 复审探针 R1：上一版 AWS_CRED 是「id:secret」拼在一起的，这一版只留了 AK_ID（前一半），脚本里还是整串旧值——"
            + "替换之后旧值不再整段出现，但后一半（secret）会原样留在 bundle 里：拒绝，点名 key 和文件，不带值")
    void aCurrentValueThatOnlyCoversTheFirstHalfOfAPreviousValueIsRejected() {
        ServiceException e = publishing(
                skillWith("CRED = '" + AWS_COMBINED + "'\n", "{\"AK_ID\":{\"value\":\"" + AWS_ID + "\",\"hosts\":[\"s3.amazonaws.com\"]}}"),
                PreviousKeys.decrypted(Map.of("AWS_CRED", AWS_COMBINED)));
        assertThat(e.getMessage()).contains("scripts/call.py").contains("AWS_CRED").contains("上一版的值")
                .doesNotContain("wJalrXUtnFEMIK7MDENG").doesNotContain(AWS_ID);
    }

    @Test
    @DisplayName("★ 复审探针 R2：首尾相接——当前值的尾巴就是旧值的头：旧值露出来的那一截同样拒绝")
    void aCurrentValueAdjacentToAPreviousValueIsRejected() {
        ServiceException e = publishing(
                skillWith("X = 'tenant-0042-ABCD-1234-wxyz-5678'\n", "{\"TENANT_KEY\":{\"value\":\"tenant-0042-ABCD\",\"hosts\":[\"a.example.com\"]}}"),
                PreviousKeys.decrypted(Map.of("OLD_KEY", "ABCD-1234-wxyz-5678")));
        assertThat(e.getMessage()).contains("scripts/call.py").contains("OLD_KEY").contains("上一版的值")
                .doesNotContain("1234-wxyz-5678").doesNotContain("tenant-0042");
    }

    @Test
    @DisplayName("★ 两个当前值在文本里互相重叠：靠左的先换，被抢掉的那个剩下的部分会原样留下——拒绝，点名被抢掉的 key 和文件")
    void twoCurrentValuesThatOverlapInTheTextAreRejected() {
        ServiceException e = publishing(
                skillWith("v = 'xxxx-AAAA-1111-yyyy'\n", "{\"KEY_A\":{\"value\":\"xxxx-AAAA-1111\",\"hosts\":[\"a.example.com\"]},"
                        + "\"KEY_B\":{\"value\":\"AAAA-1111-yyyy\",\"hosts\":[\"b.example.com\"]}}"),
                PreviousKeys.NONE);
        assertThat(e.getMessage()).contains("scripts/call.py").contains("KEY_B").doesNotContain("xxxx-AAAA").doesNotContain("1111-yyyy");
        // 各自单独出现没有问题
        SkillPackageProcessor.Output ok = SkillPackageProcessor.process(
                skillWith("a = 'xxxx-AAAA-1111'\nb = 'AAAA-1111-yyyy'\n", "{\"KEY_A\":{\"value\":\"xxxx-AAAA-1111\",\"hosts\":[\"a.example.com\"]},"
                        + "\"KEY_B\":{\"value\":\"AAAA-1111-yyyy\",\"hosts\":[\"b.example.com\"]}}"),
                SkillManifestParser.Mode.STRICT, true, PreviousKeys.NONE);
        assertThat(new String(ok.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("a = '{{KEY_A}}'\nb = '{{KEY_B}}'\n");
    }

    @Test
    @DisplayName("★ 当前值包含旧值（旧 sk-abc12345，新 Bearer sk-abc12345）：旧值整段落在新值的替换里，不拒绝；旧值单独出现在替换之外才拒绝")
    void aCurrentValueContainingAPreviousValueIsFine() {
        String json = "{\"AUTH_HEADER\":{\"value\":\"Bearer sk-abc12345\",\"hosts\":[\"api.example.com\"]}}";
        PreviousKeys previous = PreviousKeys.decrypted(Map.of("API_KEY", "sk-abc12345"));
        SkillPackageProcessor.Output ok = SkillPackageProcessor.process(skillWith("h = 'Bearer sk-abc12345'\n", json),
                SkillManifestParser.Mode.STRICT, true, previous);
        assertThat(new String(ok.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("h = '{{AUTH_HEADER}}'\n");

        ServiceException e = publishing(skillWith("h = 'Bearer sk-abc12345'\nk = 'sk-abc12345'\n", json), previous);
        assertThat(e.getMessage()).contains("scripts/call.py").contains("API_KEY").contains("上一版的值").doesNotContain("abc12345");
    }

    @Test
    @DisplayName("旧值同时也是这一版某个 key 的值（key 改了名、值没变）：按新名字换，不算残留；SKILL.md 里一样")
    void aRenamedKeyIsStillNotResidue() {
        SkillPackageProcessor.Output ok = SkillPackageProcessor.process(
                skillWith("id = '" + AWS_ID + "'\n", "{\"AWS_KEY_ID\":{\"value\":\"" + AWS_ID + "\",\"hosts\":[\"s3.amazonaws.com\"]}}"),
                SkillManifestParser.Mode.STRICT, true, PreviousKeys.decrypted(Map.of("OLD_NAME", AWS_ID)));
        assertThat(new String(ok.files().get("scripts/call.py"), StandardCharsets.UTF_8)).isEqualTo("id = '{{AWS_KEY_ID}}'\n");
    }

    // ---------------------------------------------------------------- 第 3 轮 2：key 名里不能出现任何已知的值

    @Test
    @DisplayName("★ 复审探针 R3 / R3b / R4：上一版的值写成 key 名（带新值）、当前的值写成它自己的名字、上一版的值写成不带 value 的 key 名——都拒绝，文案里没有值"
            + "（R4 以前是处理器放行，到 SkillSecretService.prepare 抛「key <值> 没有给值」，那条异常没抹值，进了 ERROR 日志）")
    void aValueUsedAsAKeyNameIsRejectedWhereverItComesFrom() {
        PreviousKeys previous = PreviousKeys.decrypted(Map.of("AWS_KEY_ID", AWS_ID));
        for (String secrets : new String[]{
                "{\"" + AWS_ID + "\":{\"value\":\"brand-new-value-1\",\"hosts\":[\"s3.amazonaws.com\"]}}",   // R3
                "{\"" + AWS_ID + "\":{\"hosts\":[\"s3.amazonaws.com\"]}}"}) {                                // R4
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("SKILL.md", b(MD));
            files.put("secrets.json", b(secrets));
            ServiceException e = publishing(files, previous);
            assertThat(e.getMessage()).contains("第 1 个 key 的名字").doesNotContain(AWS_ID).doesNotContain("AKIA");
        }
        Map<String, byte[]> own = new LinkedHashMap<>();
        own.put("SKILL.md", b(MD));
        own.put("secrets.json", b("{\"" + AWS_ID + "\":{\"value\":\"" + AWS_ID + "\",\"hosts\":[\"s3.amazonaws.com\"]}}"));   // R3b：没有上一版
        assertThat(publishing(own, PreviousKeys.NONE).getMessage()).contains("第 1 个 key 的名字").doesNotContain(AWS_ID).doesNotContain("AKIA");
    }

    // ---------------------------------------------------------------- 第 3 轮 3（R27）：上一版有密文解不开，按 key 处理，不能把 skill 永久卡死

    /** 上一版有 CRM_TOKEN，配了密钥、但它的密文解不开（密钥换过 / 密文坏了）。 */
    private static PreviousKeys crmTokenCannotBeOpened() {
        return PreviousKeys.decrypted(Map.of("WECOM_HOOK", OLD), java.util.Set.of("CRM_TOKEN"));
    }

    @Test
    @DisplayName("★ R27：解不开的 key 在新的 secrets.json 里没写 value——不能沿用，拒绝，文案说清楚原因和两条出路（重新写 value，或者去掉它），不带值")
    void anUndecryptableKeyCannotBeReused() {
        ServiceException e = publishing(skillWith("print(1)\n", "{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"), crmTokenCannotBeOpened());
        assertThat(e.getMessage()).contains("key CRM_TOKEN 上一版的值解不开").contains("加密密钥可能换过")
                .contains("给 CRM_TOKEN 重新写 value").contains("去掉 CRM_TOKEN").doesNotContain(OLD).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ R27：解不开的 key 给了新值、或者去掉了——照常发布；解得开的 key 的旧值照样参与抹值和残留检查")
    void anUndecryptableKeyWithANewValueOrDroppedPublishesAsUsual() {
        // 给了新值
        SkillPackageProcessor.Output withNewValue = SkillPackageProcessor.process(
                skillWith("TOKEN = 'sk-live-FRESH-0123456789'\nHOOK = '" + OLD + "'\n",
                        "{\"CRM_TOKEN\":{\"value\":\"sk-live-FRESH-0123456789\",\"hosts\":[\"api.customer.com\"]},"
                                + "\"WECOM_HOOK\":{\"hosts\":[\"qyapi.weixin.qq.com\"]}}"),
                SkillManifestParser.Mode.STRICT, true, crmTokenCannotBeOpened());
        assertThat(new String(withNewValue.files().get("scripts/call.py"), StandardCharsets.UTF_8))
                .isEqualTo("TOKEN = '{{CRM_TOKEN}}'\nHOOK = '{{WECOM_HOOK}}'\n");   // WECOM_HOOK 解得开：沿用并替换
        assertThat(withNewValue.secrets()).extracting(SkillSecretsFile.Entry::name).containsExactly("CRM_TOKEN", "WECOM_HOOK");

        // 去掉了（secrets.json 里只剩解得开的那个）
        SkillPackageProcessor.Output dropped = SkillPackageProcessor.process(
                skillWith("print(1)\n", "{\"WECOM_HOOK\":{\"hosts\":[\"qyapi.weixin.qq.com\"]}}"),
                SkillManifestParser.Mode.STRICT, true, crmTokenCannotBeOpened());
        assertThat(dropped.secrets()).extracting(SkillSecretsFile.Entry::name).containsExactly("WECOM_HOOK");

        // 解得开的旧值还在脚本里、key 却被删了：照样拒绝（残留检查没有因为另一个 key 解不开而放松）
        ServiceException e = publishing(skillWith("HOOK = '" + OLD + "'\n", "{\"CRM_TOKEN\":{\"value\":\"sk-live-FRESH-0123456789\",\"hosts\":[\"api.customer.com\"]}}"),
                crmTokenCannotBeOpened());
        assertThat(e.getMessage()).contains("WECOM_HOOK").contains("上一版的值").doesNotContain(OLD);
    }

    @Test
    @DisplayName("R27：解不开的 key 被去掉了、文件里却还在用 {{它}}：拒绝，出路写成「重新写 value，或者把 {{它}} 删掉」（不写值即沿用走不通）")
    void aDroppedUndecryptableKeyThatIsStillUsedGetsATailoredMessage() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: x\n---\n带上 {{CRM_TOKEN}} 调接口\n"));
        ServiceException e = publishing(files, crmTokenCannotBeOpened());
        assertThat(e.getMessage()).contains("{{CRM_TOKEN}}").contains("解不开").contains("重新写 value").doesNotContain("不写值即沿用");

        // 没人用它：去掉就去掉了
        SkillPackageProcessor.process(plainSkill(), SkillManifestParser.Mode.STRICT, true, crmTokenCannotBeOpened());
    }

    @Test
    @DisplayName("★ 第 4 轮 3：处理器不靠实现自己声明「解不开」——上一版有（在 names() 里）、却拿不到值的 key 就是解不开的：没写 value 一律拒绝沿用；"
            + "给了新值、或者去掉了，照常发布")
    void aPreviousKeyWithoutAValueIsUndecryptableWhateverTheImplementationDeclares() {
        PreviousKeys silent = new PreviousKeys() {   // 只实现 names / values：CRM_TOKEN 在上一版里，值却拿不到；没有声明 undecryptable()
            @Override
            public Set<String> names() {
                return Set.of("CRM_TOKEN", "WECOM_HOOK");
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                return wanted.contains("WECOM_HOOK") ? Map.of("WECOM_HOOK", OLD) : Map.of();
            }
        };
        ServiceException e = publishing(skillWith("print(1)\n", "{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"), silent);
        assertThat(e.getMessage()).contains("key CRM_TOKEN 上一版的值解不开").contains("重新写 value").doesNotContain(OLD);

        PreviousKeys lying = new PreviousKeys() {   // 同上，还覆写了 undecryptable()、说「没有解不开的」：处理器照样不信
            @Override
            public Set<String> names() {
                return silent.names();
            }

            @Override
            public Map<String, String> values(Collection<String> wanted) {
                return silent.values(wanted);
            }

            @Override
            public Set<String> undecryptable() {
                return Set.of();
            }
        };
        assertThat(publishing(skillWith("print(1)\n", "{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"), lying).getMessage())
                .contains("key CRM_TOKEN 上一版的值解不开");

        SkillPackageProcessor.Output withNewValue = SkillPackageProcessor.process(
                skillWith("print(1)\n", "{\"CRM_TOKEN\":{\"value\":\"sk-live-FRESH-0123456789\",\"hosts\":[\"api.customer.com\"]}}"),
                SkillManifestParser.Mode.STRICT, true, silent);
        assertThat(withNewValue.secrets()).extracting(SkillSecretsFile.Entry::name).containsExactly("CRM_TOKEN");
        SkillPackageProcessor.process(plainSkill(), SkillManifestParser.Mode.STRICT, true, silent);   // 去掉了：照常发布
    }

    @Test
    @DisplayName("R27：解不开的 key 的名字里含有这一版的值——名字会出现在文案里：拒绝，不回显")
    void anUndecryptableKeyNameCarryingACurrentValueIsRejected() {
        PreviousKeys previous = PreviousKeys.decrypted(Map.of(), java.util.Set.of("CRM_TOKEN_PROD"));
        ServiceException e = publishing(skillWith("print(1)\n", "{\"NEW_KEY\":{\"value\":\"CRM_TOKEN_PROD\",\"hosts\":[\"a.example.com\"]}}"), previous);
        assertThat(e.getMessage()).contains("名字").doesNotContain("CRM_TOKEN_PROD");
    }

    // ---------------------------------------------------------------- 第 4 轮（续）A（R33）：解析之后再核对一遍

    private static final String CRM_SECRETS = "{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}";

    private static Map<String, byte[]> skillMdWith(String skillMd, String secretsJson) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", b(skillMd));
        files.put("secrets.json", b(secretsJson));
        return files;
    }

    @Test
    @DisplayName("★ 第 4 轮（续）R33：先替换、再解析只管得住原文。description 里用 YAML 双引号的 \\x43、或者 \\ 续行把 key 拼出来——原文里没有这个值、"
            + "替换不掉，解码之后 description 就是它：解析之后再核对一遍，拒绝；文案点名字段和 key，不带值，教作者写 {{名字}}")
    void aValueRebuiltInTheDescriptionIsRejectedAfterParsing() {
        String escaped = "---\nname: refund-flow\ndescription: \"key sk-live-\\x43RM-0123456789\"\n---\n正文\n";
        String continued = "---\nname: refund-flow\ndescription: \"key sk-live-CRM-\\\n  0123456789\"\n---\n正文\n";
        for (String md : new String[]{escaped, continued}) {
            assertThat(md).doesNotContain(TOKEN);   // 原文里确实没有
            ServiceException e = publishing(skillMdWith(md, CRM_SECRETS), PreviousKeys.NONE);
            assertThat(e.getMessage()).as(md).contains("description").contains("CRM_TOKEN").contains("{{CRM_TOKEN}}").contains("转义")
                    .doesNotContain(TOKEN).doesNotContain("sk-live").doesNotContain("0123456789");
        }
    }

    @Test
    @DisplayName("★ 第 4 轮（续）R33：拼进 metadata.jm.hosts——域名会被归一成小写再存进 manifest（sk-live-crm-…），manifest 里的域名按小写比，同样拒绝")
    void aValueRebuiltInTheDeclaredHostsIsRejectedAfterParsing() {
        String md = "---\nname: refund-flow\ndescription: 退款\nmetadata:\n  jm:\n    hosts: [\"sk-live-\\x43RM-0123456789.example.com\"]\n---\n正文\n";
        ServiceException e = publishing(skillMdWith(md, CRM_SECRETS), PreviousKeys.NONE);
        assertThat(e.getMessage()).contains("metadata.jm").contains("CRM_TOKEN").doesNotContain("sk-live").doesNotContain("0123456789");

        // 原文就写成小写（不经过解码）：替换按原文、区分大小写，换不掉；解码出来的也是小写，按原样比也查不出——只有「域名按小写比」这一道拦得住
        String lower = "---\nname: refund-flow\ndescription: 退款\nmetadata:\n  jm:\n    hosts: [sk-live-crm-0123456789.example.com]\n---\n正文\n";
        assertThat(publishing(skillMdWith(lower, CRM_SECRETS), PreviousKeys.NONE).getMessage())
                .contains("metadata.jm").contains("CRM_TOKEN").doesNotContain("sk-live").doesNotContain("0123456789");
    }

    @Test
    @DisplayName("第 4 轮（续）R33：拼进 name（值本身是小写、符合 name 的写法时）、拼出上一版的值，同样拒绝")
    void aValueRebuiltInTheNameOrFromThePreviousVersionIsRejected() {
        String lowerSecrets = "{\"LOWER_KEY\":{\"value\":\"sk-live-crm-0123456789\",\"hosts\":[\"api.customer.com\"]}}";
        String name = "---\nname: \"sk-live-\\x63rm-0123456789\"\ndescription: 退款\n---\n正文\n";
        assertThat(publishing(skillMdWith(name, lowerSecrets), PreviousKeys.NONE).getMessage())
                .contains("name").contains("LOWER_KEY").doesNotContain("sk-live").doesNotContain("0123456789");

        Map<String, byte[]> previousOnly = new LinkedHashMap<>();   // 上一版的 WECOM_HOOK（OLD），这一版删了；description 用 \x33（3）拼出它
        String previousMd = "---\nname: refund-flow\ndescription: \"hook 69\\x33a91f6-7a2b-4c1d-9e8f-0123456789ab\"\n---\n正文\n";
        assertThat(previousMd).doesNotContain(OLD);   // 原文里确实没有（不然原文上的残留检查先拦下了）
        previousOnly.put("SKILL.md", b(previousMd));
        assertThat(publishing(previousOnly, PreviousKeys.decrypted(Map.of("WECOM_HOOK", OLD))).getMessage())
                .contains("description").contains("WECOM_HOOK").doesNotContain(OLD).doesNotContain("0123456789ab");
    }

    // ---------------------------------------------------------------- 第 4 轮（再续）R34：解析之后的核对扩到 frontmatter 解码出来的所有字符串

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：license 用 \\x43 拼出 key 的值——它不入库，可转义原样留在 bundle 的 SKILL.md 里，运行时模型读得到、也解得开："
            + "拒绝，文案点名 license 和 key，不带值")
    void aValueRebuiltInLicenseIsRejected() {
        String md = "---\nname: refund-flow\ndescription: 退款\nlicense: \"MIT sk-live-\\x43RM-0123456789\"\n---\n正文\n";
        assertThat(md).doesNotContain(TOKEN);
        ServiceException e = publishing(skillMdWith(md, CRM_SECRETS), PreviousKeys.NONE);
        assertThat(e.getMessage()).contains("license").contains("CRM_TOKEN").contains("{{CRM_TOKEN}}")
                .doesNotContain("sk-live").doesNotContain("0123456789");
    }

    private static ServiceException lenientPublishing(Map<String, byte[]> files) {
        try {
            SkillPackageProcessor.process(files, SkillManifestParser.Mode.LENIENT, true);
        } catch (ServiceException e) {
            return e;
        }
        throw new AssertionError("本该拒绝发布，却发布成功了");
    }

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：别的解码写法和位置同样拒绝——宽松模式下被忽略的键的值和键名本身、metadata 里别的字段和键名、!!binary（YAML 自带的 base64）、"
            + "十六进制数字（拼出一个全是数字的 key 值）；文案点名位置，不带值")
    void everyOtherDecodedStringIsCheckedToo() {
        String escaped = "\"sk-live-\\x43RM-0123456789\"";
        assertThat(lenientPublishing(skillMdWith("---\nname: refund-flow\ndescription: 退款\nauthor: " + escaped + "\n---\n正文\n", CRM_SECRETS))
                .getMessage()).contains("author").contains("CRM_TOKEN").doesNotContain("sk-live");
        assertThat(lenientPublishing(skillMdWith("---\nname: refund-flow\ndescription: 退款\n" + escaped + ": x\n---\n正文\n", CRM_SECRETS))
                .getMessage()).contains("顶层的键名").contains("CRM_TOKEN").doesNotContain("sk-live");
        assertThat(publishing(skillMdWith("---\nname: refund-flow\ndescription: 退款\nmetadata:\n  owner: " + escaped + "\n---\n正文\n", CRM_SECRETS),
                PreviousKeys.NONE).getMessage()).contains("metadata").contains("CRM_TOKEN").doesNotContain("sk-live");
        assertThat(publishing(skillMdWith("---\nname: refund-flow\ndescription: 退款\nmetadata:\n  " + escaped + ": x\n---\n正文\n", CRM_SECRETS),
                PreviousKeys.NONE).getMessage()).contains("metadata 里的键名").contains("CRM_TOKEN").doesNotContain("sk-live");

        String base64 = java.util.Base64.getEncoder().encodeToString(b(TOKEN));
        assertThat(publishing(skillMdWith("---\nname: refund-flow\ndescription: 退款\nmetadata:\n  blob: !!binary " + base64 + "\n---\n正文\n", CRM_SECRETS),
                PreviousKeys.NONE).getMessage()).contains("metadata").contains("CRM_TOKEN").doesNotContain("sk-live");

        String numeric = "{\"NUM_KEY\":{\"value\":\"1234567890\",\"hosts\":[\"api.customer.com\"]}}";   // 0x499602D2 = 1234567890
        assertThat(publishing(skillMdWith("---\nname: refund-flow\ndescription: 退款\nmetadata:\n  build: 0x499602D2\n---\n正文\n", numeric),
                PreviousKeys.NONE).getMessage()).contains("metadata").contains("NUM_KEY").doesNotContain("1234567890");
    }

    @Test
    @DisplayName("第 4 轮（续）R33：正常的 SKILL.md 不误报——用 {{名字}}、转义拼出来的不是值、域名正常，照常发布；manifest 的 JSON 就是存进库的那一份")
    void anOrdinarySkillMdIsNotAFalsePositive() {
        String md = "---\nname: refund-flow\ndescription: \"\\x41BC 用 {{CRM_TOKEN}} 调接口\"\nmetadata:\n  jm:\n    hosts: [api.customer.com]\n---\n"
                + "带上 {{CRM_TOKEN}} 调接口\n";
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(skillMdWith(md, CRM_SECRETS), SkillManifestParser.Mode.STRICT, true);
        assertThat(out.skill().description()).isEqualTo("ABC 用 {{CRM_TOKEN}} 调接口");
        assertThat(out.manifestJson()).isEqualTo(SkillManifest.of(out.skill().jm(), SkillSecretsFile.decls(out.secrets())).toJson());
        assertThat(SkillManifest.fromJson(out.manifestJson()).hosts()).containsExactly("api.customer.com");
        assertThat(SkillManifest.fromJson(out.manifestJson()).keys()).extracting(SkillManifest.KeyDecl::name).containsExactly("CRM_TOKEN");
    }
    @Test
    void sourceScalarsSurviveDuplicateMergeAndLenientRetryValidation() {
        String escaped = "\"sk-live-\\x43RM-0123456789\"";
        for (String extra : List.of("license: " + escaped + "\nlicense: safe\n",
                "metadata:\n  <<: {hidden: " + escaped + "}\n  hidden: safe\n",
                "argument-hint: [" + escaped + "] [b]\n",
                "argument-hint: `bad` [" + escaped + "] [b]\n",
                "argument-hint: [!!binary " + java.util.Base64.getEncoder().encodeToString(b(TOKEN)) + "] [b]\n")) {
            var mode = extra.startsWith("argument") ? SkillManifestParser.Mode.LENIENT : SkillManifestParser.Mode.STRICT;
            String md = "---\nname: refund-flow\ndescription: safe\n" + extra + "---\nbody\n";
            for (boolean previous : List.of(false, true)) {
                var files = previous ? Map.of("SKILL.md", b(md)) : skillMdWith(md, CRM_SECRETS);
                var old = previous ? PreviousKeys.decrypted(Map.of("CRM_TOKEN", TOKEN)) : PreviousKeys.NONE;
                assertThatThrownBy(() -> SkillPackageProcessor.process(files, mode, true, old))
                        .isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN")
                        .hasMessageNotContaining(TOKEN).hasMessageNotContaining("sk-live");
            }
        }
    }

}
