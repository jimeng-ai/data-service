package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
