package com.jimeng.dataserver.ai.skill.manifest;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class YamlResourceBudgetTest {
    public static class Probe {
        public static void main(String[] args) {
            String data = Base64.getEncoder().encodeToString("a".repeat(8192).getBytes(StandardCharsets.UTF_8));
            for (String tag : new String[]{"!!binary ", ""}) {
                String md = "---\nname: demo\ndescription: safe\nmetadata:\n  scalar: &s " + tag + data
                        + "\n  list: [" + String.join(", ", Collections.nCopies(18000, "*s")) + "]\n---\nbody\n";
                for (var mode : SkillManifestParser.Mode.values()) {
                    var result = SkillManifestParser.parse(md, mode);
                    if (result.valid()) throw new AssertionError("alias budget accepted");
                }
            }
            System.out.println("bounded-rejection");
        }
    }
    @Test
    void scalarAndBinaryAliasesAreBoundedIn128MbProcess() throws Exception {
        Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx128m", "-cp",
                System.getProperty("java.class.path"), Probe.class.getName()).redirectErrorStream(true).start();
        boolean exited = p.waitFor(15, TimeUnit.SECONDS);
        if (!exited) p.destroyForcibly();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(exited).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        assertThat(out).contains("bounded-rejection").doesNotContain("OutOfMemoryError");
    }
    @Test
    void ordinaryAnchorsArePreservedWithoutRepeatedDecodedBinary() {
        String data = Base64.getEncoder().encodeToString("safe".repeat(2048).getBytes(StandardCharsets.UTF_8));
        for (var mode : SkillManifestParser.Mode.values()) {
            var parsed = SkillManifestParser.parse("---\nname: demo\ndescription: safe\nmetadata:\n  source: &s !!binary "
                    + data + "\n  copies: [*s, *s, *s]\n---\nbody\n", mode);
            assertThat(parsed.valid()).isTrue();
            assertThat(parsed.decoded().stream().filter(x -> x.text().equals("safe".repeat(2048))).count()).isEqualTo(1);
        }
    }
}
