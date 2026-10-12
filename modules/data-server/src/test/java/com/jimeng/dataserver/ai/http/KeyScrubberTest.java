package com.jimeng.dataserver.ai.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeyScrubberTest {

    private static final String TOKEN = "sk-live/CRM+0123456789";

    private static KeyScrubber scrubber() {
        return new KeyScrubber(List.of(new SkillKey("CRM_TOKEN", 1L, "crm", List.of("a.example.com"), null, null, TOKEN)));
    }

    @Test
    @DisplayName("原文、百分号编码（大小写两种）、表单编码、JSON 转义（含 \\/）都抹回 {{名字}}")
    void scrubsCommonEncodings() {
        String pct = Placeholders.percentEncode(TOKEN);
        String text = String.join(" | ", TOKEN, pct, pct.replace("%2F", "%2f").replace("%2B", "%2b"),
                URLEncoder.encode(TOKEN, StandardCharsets.UTF_8), TOKEN.replace("/", "\\/"));
        assertThat(scrubber().scrub(text)).isEqualTo(String.join(" | ", "{{CRM_TOKEN}}".repeat(1),
                "{{CRM_TOKEN}}", "{{CRM_TOKEN}}", "{{CRM_TOKEN}}", "{{CRM_TOKEN}}"));
    }

    @Test
    @DisplayName("按字节抹：二进制正文里的 key 也抹掉，其余字节不动")
    void scrubsBytes() {
        byte[] in = ("\0\1" + TOKEN + "\2").getBytes(StandardCharsets.UTF_8);
        assertThat(new String(scrubber().scrub(in), StandardCharsets.UTF_8)).isEqualTo("\0\1{{CRM_TOKEN}}\2");
    }

    @Test
    @DisplayName("没有 key 时原样返回同一个数组；maxLength 是最长那种写法的字节数")
    void noKeys() {
        byte[] in = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(KeyScrubber.NONE.scrub(in)).isSameAs(in);
        assertThat(scrubber().maxLength()).isGreaterThanOrEqualTo(Placeholders.percentEncode(TOKEN).length());
    }
    @Test
    void adversarialFiftyMegabytesIsLinear() {
        byte[] body = new byte[50 * 1024 * 1024];
        java.util.Arrays.fill(body, (byte)'a');
        KeyScrubber k = new KeyScrubber(List.of(new SkillKey("TRAP_KEY", 1L, "crm", null, null, null, "a".repeat(8000) + "/b")));
        long t = System.nanoTime();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(8), () -> assertThat(k.scrub(body)).isSameAs(body));
        System.out.println("TASK6 adversarial50MB ms=" + (System.nanoTime()-t)/1_000_000);
    }

    @Test
    void thirtyTwoKeysTwoMegabytesAndBoundedExpansion() {
        java.util.ArrayList<SkillKey> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) keys.add(new SkillKey("KEY_" + i, 1L, "crm", null, null, null, "secret-" + i + "/+ x\"\\"));
        byte[] body = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(body, (byte)'a');
        KeyScrubber k = new KeyScrubber(keys);
        long t = System.nanoTime();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> assertThat(k.scrub(body)).isSameAs(body));
        System.out.println("TASK6 keys32body2MB ms=" + (System.nanoTime()-t)/1_000_000);
        KeyScrubber expanded = new KeyScrubber(List.of(new SkillKey("K" + "X".repeat(63), 1L, "crm", null, null, null, "exactly8")));
        KeyScrubber.Scrubbed out = expanded.scrub("exactly8".repeat(10000).getBytes(StandardCharsets.UTF_8), 1000);
        assertThat(out.bytes().length).isLessThanOrEqualTo(1000);
        assertThat(out.stopped()).isTrue();
        assertThat(new String(k.scrub("secret-1/+ x\"\\".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)).isEqualTo(k.scrub("secret-1/+ x\"\\"));
    }
}
