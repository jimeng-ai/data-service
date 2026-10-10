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
                throw new ServiceException(com.jimeng.common.core.enums.ExceptionCode.INVALID_REQUEST, "无法核对上一版的 key：平台没有配置加密密钥");
            }
        };
        assertThatThrownBy(() -> SkillPackageProcessor.process(reuseSkill("正文\n"), SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无法核对上一版的 key");
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
                throw new ServiceException(com.jimeng.common.core.enums.ExceptionCode.INVALID_REQUEST, "无法核对上一版的 key CRM_TOKEN：解密失败");
            }
        };
        Map<String, byte[]> noSecretsFile = plainSkill();
        assertThatThrownBy(() -> SkillPackageProcessor.process(noSecretsFile, SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无法核对上一版的 key");
        Map<String, byte[]> everyKeyHasANewValue = plainSkill();
        everyKeyHasANewValue.put("secrets.json", b("{\"CRM_TOKEN\":{\"value\":\"sk-live-NEW-9876543210\",\"hosts\":[\"api.customer.com\"]}}"));
        assertThatThrownBy(() -> SkillPackageProcessor.process(everyKeyHasANewValue, SkillManifestParser.Mode.STRICT, true, cannotDecrypt))
                .isInstanceOf(ServiceException.class).hasMessageContaining("无法核对上一版的 key");
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
}
