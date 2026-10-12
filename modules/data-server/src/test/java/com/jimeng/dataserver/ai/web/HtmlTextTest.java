package com.jimeng.dataserver.ai.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HtmlTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"<script", "<style", "<head", "<!--", "<"})
    void malformedOpenersFinishWithinBoundedProcess(String opener) throws Exception {
        Path output = Files.createTempFile("task7-html-scan-", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), opener).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(3, TimeUnit.SECONDS)).as("2 MB malformed %s conversion must finish", opener).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).startsWith("OK ");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).as("probe must be reaped").isTrue();
            Files.deleteIfExists(output);
        }
    }

    @Test
    void smallNormalAndMalformedInputsPreservePreviousBestEffortConversion() {
        for (String input : List.of("", "<>", "< >", "<<>", "<><p>x</p>", "a <missing", "<script",
                "<scripted>x</script> visible", "<SCRIPT>x\n</SCRIPT><STYLE>css</STYLE><head>title</head><p>ok</p>",
                "<!--one-->x<!--two-->", "<!--missing<p>still visible</p>", "<script>a<script>b</script> c</script>",
                "<head><style>x</style>y</head>z", "x &nbsp; &amp; &lt; &gt; &amp;lt; &amp;nbsp;",
                "\u0000 \t\n\u000b\f\rx\u00a0y \u001f", "<script>missing<style>x</style>tail", "<!---->", "a < b > c")) {
            assertThat(HtmlText.toText(input)).as("input %s", input).isEqualTo(legacy(input));
        }
    }

    @Test
    void compactGeneratedEdgeCasesMatchPreviousOrderedPasses() {
        java.util.Random random = new java.util.Random(731);
        List<String> tokens = List.of("<", ">", "<>", "<script", "</script>", "<STYLE", "</STYLE>",
                "<head", "</head>", "<!--", "-->", "&nbsp;", "&amp;", "&lt;", "&gt;", "x", " ", "\n", "\u00a0");
        for (int sample = 0; sample < 1000; sample++) {
            StringBuilder input = new StringBuilder();
            for (int n = 0, count = random.nextInt(20); n < count; n++) input.append(tokens.get(random.nextInt(tokens.size())));
            assertThat(HtmlText.toText(input.toString())).as("sample %s: %s", sample, input).isEqualTo(legacy(input.toString()));
        }
    }

    @Test
    void expiredAndInFlightConversionBudgetsAbortInTheCallingThread() throws Exception {
        assertThatThrownBy(() -> HtmlText.toText("<p>ok</p>", System.nanoTime() - 1))
                .isInstanceOf(InterruptedIOException.class);
        String large = "x".repeat(2 * 1024 * 1024);
        long start = System.nanoTime();
        assertThatThrownBy(() -> HtmlText.toText(large, start + TimeUnit.MILLISECONDS.toNanos(1)))
                .isInstanceOf(InterruptedIOException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500);
        assertThat(HtmlText.toText("<script>x()</script><p>ok &amp; 再见</p>", System.nanoTime() + TimeUnit.SECONDS.toNanos(1)))
                .isEqualTo("ok & 再见");
    }

    private static String legacy(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<head.*?</head>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    /** 独立进程：超时测试必须终止实际工作，不能把失控正则留在 Surefire 线程里。 */
    public static class Probe {
        public static void main(String[] args) {
            String input = args[0].repeat(2 * 1024 * 1024 / args[0].length());
            long started = System.nanoTime();
            String output = HtmlText.toText(input);
            if (!output.equals(input)) throw new AssertionError("malformed opener content changed");
            System.out.println("OK " + input.length() + " chars " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
        }
    }
}
