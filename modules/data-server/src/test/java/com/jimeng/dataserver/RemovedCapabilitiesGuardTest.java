package com.jimeng.dataserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已下线能力的守卫：删掉的东西不准悄悄回来。
 *
 * <p>依据：工作区 {@code docs/superpowers/specs/2026-10-05-everything-is-skills-design.md} §7.1——
 * 平台里不再有「一个外部服务一段专用代码」的插件，对外调用以后统一走通用 HTTP 网关。
 * 每个测试方法对应一次删除；哪天它红了，先读那份设计，再决定是删掉新代码还是改这里。
 *
 * <p>只扫主代码、主资源和平台 skill 目录。<b>不扫测试</b>（有测试拿旧名字当夹具字符串），
 * <b>不扫迁移脚本</b>（{@code db/migration} 下是已经执行过的历史，不能改）。
 */
class RemovedCapabilitiesGuardTest {

    /** surefire 的工作目录是 modules/data-server。 */
    private static final Path MODULE = Path.of("").toAbsolutePath();
    private static final Path REPO = MODULE.getParent().getParent();

    private static final List<Path> ROOTS = List.of(
            MODULE.resolve("src/main"),
            MODULE.resolve("skills"),
            REPO.resolve("common/common-core/src/main"),
            REPO.resolve("common/common-persistence/src/main"));

    @Test
    @DisplayName("高德已下线：主代码、资源、平台 skill 里不再出现高德的类、配置和 skill")
    void gaodeIsGone() throws IOException {
        assertAbsent(List.of("GaoDe", "Gaode", "gaode", "PoiCategoryDict", "AdcodeCitycodeDict", "PoiClusterAlgorithm"));
    }

    static void assertAbsent(List<String> needles) throws IOException {
        // 工作目录不对时扫描范围是空的，守卫会白白变绿——先把这件事钉死。
        assertTrue(Files.isDirectory(MODULE.resolve("src/main")),
                "工作目录不是 modules/data-server，扫描范围为空：" + MODULE);
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains("/db/migration/"))
                        .toList()) {
                    scanned++;
                    // 按字节读再宽松解码：资源目录里可能有二进制文件，readString 会直接抛。
                    String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    for (String n : needles) {
                        if (text.contains(n)) hits.add(REPO.relativize(f) + " 含「" + n + "」");
                    }
                }
            }
        }
        assertTrue(scanned > 100, "只扫到 " + scanned + " 个文件，扫描范围不对：" + ROOTS);
        assertTrue(hits.isEmpty(), "已下线的能力又出现了：\n" + String.join("\n", hits));
    }
}
