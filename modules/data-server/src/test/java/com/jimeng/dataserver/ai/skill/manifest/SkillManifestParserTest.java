package com.jimeng.dataserver.ai.skill.manifest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser.Mode.LENIENT;
import static com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser.Mode.STRICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillManifestParserTest {

    private static final String OK = "---\nname: refund-flow\ndescription: 处理退款\n---\n\n## 流程\n先查订单。\n";

    /** 不写成字面字符或 unicode 转义：编辑器 / 格式化工具 / 写文件的工具链都可能把它吃掉或还原成看不见的字符。 */
    private static final char BOM = (char) 0xFEFF;

    private final Logger parserLogger = (Logger) LoggerFactory.getLogger(SkillManifestParser.class);
    private final Logger serviceExceptionLogger = (Logger) LoggerFactory.getLogger(ServiceException.class);
    private final ListAppender<ILoggingEvent> parserLogs = new ListAppender<>();
    private final ListAppender<ILoggingEvent> serviceExceptionLogs = new ListAppender<>();
    private Level previousParserLevel;
    private Level previousServiceExceptionLevel;

    @BeforeEach
    void captureLogs() {
        previousParserLevel = parserLogger.getLevel();
        previousServiceExceptionLevel = serviceExceptionLogger.getLevel();
        parserLogger.setLevel(Level.TRACE);
        serviceExceptionLogger.setLevel(Level.TRACE);
        parserLogs.start();
        serviceExceptionLogs.start();
        parserLogger.addAppender(parserLogs);
        serviceExceptionLogger.addAppender(serviceExceptionLogs);
    }

    @AfterEach
    void releaseLogs() {
        parserLogger.detachAppender(parserLogs);
        serviceExceptionLogger.detachAppender(serviceExceptionLogs);
        parserLogs.stop();
        serviceExceptionLogs.stop();
        parserLogger.setLevel(previousParserLevel);
        serviceExceptionLogger.setLevel(previousServiceExceptionLevel);
    }

    /** 拼一份最小的 SKILL.md：frontmatter 各行由调用方给，正文固定。 */
    private static String skill(String frontmatterLines) {
        return "---\n" + frontmatterLines + "\n---\nb\n";
    }

    @Test
    @DisplayName("取出 name / description / 正文（正文开头的空行去掉）")
    void extractsFields() {
        ParsedSkill p = SkillManifestParser.parse(OK, STRICT);
        assertThat(p.valid()).isTrue();
        assertThat(p.name()).isEqualTo("refund-flow");
        assertThat(p.description()).isEqualTo("处理退款");
        assertThat(p.body()).isEqualTo("## 流程\n先查订单。\n");
        assertThat(p.jm()).isEqualTo(JmMetadata.EMPTY);
    }

    @Test
    @DisplayName("CRLF和BOM都能解析（Windows 上编辑过的文件），两种模式都一样")
    void CRLF和BOM都能解析() {
        String crlf = BOM + OK.replace("\n", "\r\n");
        // 输入开头真的是 BOM：它被吃掉的话，这条会退化成只测 CRLF，却仍然是绿的
        assertThat(crlf.charAt(0)).isEqualTo((char) 0xFEFF);
        for (SkillManifestParser.Mode mode : SkillManifestParser.Mode.values()) {
            ParsedSkill p = SkillManifestParser.parse(crlf, mode);
            assertThat(p.errors()).as(mode.name()).isEmpty();
            assertThat(p.name()).isEqualTo("refund-flow");
            assertThat(p.description()).isEqualTo("处理退款");
        }
    }

    @Test
    @DisplayName("宽松模式：不认识的顶层键忽略并记下来，不报错；严格模式报错")
    void lenientIgnoresUnknownTopKeys() {
        String md = "---\nname: pdf\ndescription: d\nversion: 2\nauthor: x\n---\nb\n";
        ParsedSkill lenient = SkillManifestParser.parse(md, LENIENT);
        assertThat(lenient.valid()).isTrue();
        assertThat(lenient.ignoredKeys()).containsExactly("author", "version");
        ParsedSkill strict = SkillManifestParser.parse(md, STRICT);
        assertThat(strict.errors()).anyMatch(e -> e.contains("不允许的键") && e.contains("author") && e.contains("version"));
    }

    @Test
    @DisplayName("宽松模式：名字沿用上传 / 导入一直接受的形状（可大写、可下划线、字母开头、≤64）")
    void lenientNameShape() {
        assertThat(SkillManifestParser.parse("---\nname: PDF_Tool\ndescription: d\n---\nb\n", LENIENT).valid()).isTrue();
        assertThat(SkillManifestParser.parse("---\nname: 1pdf\ndescription: d\n---\nb\n", LENIENT).valid()).isFalse();
        assertThat(SkillManifestParser.parse("---\nname: PDF_Tool\ndescription: d\n---\nb\n", STRICT).valid()).isFalse();
    }

    @Test
    @DisplayName("宽松模式：正文不能为空（单文件上传一直这么要求）；严格模式不查正文")
    void lenientRequiresBody() {
        String md = "---\nname: a\ndescription: d\n---\n\n";
        assertThat(SkillManifestParser.parse(md, LENIENT).errors()).contains("SKILL.md 正文不能为空");
        assertThat(SkillManifestParser.parse(md, STRICT).valid()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------
    // 宽松模式：YAML 失败后按 Claude Code 加载器的规则补引号重试一次（设计 #6 经核实不成立，控制者裁定推翻）
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("宽松：值里有「冒号 + 空格」，补引号后能解析，description 一字不差")
    void lenientQuotesColonValue() {
        ParsedSkill p = SkillManifestParser.parse(skill("name: a\ndescription: Use when: x"), LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.description()).isEqualTo("Use when: x");
    }

    @Test
    @DisplayName("宽松：值以反引号开头（YAML 里不能做标量开头），补引号后能解析")
    void lenientQuotesBacktickValue() {
        ParsedSkill p = SkillManifestParser.parse(skill("name: a\ndescription: `code` helper"), LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.description()).isEqualTo("`code` helper");
    }

    @Test
    @DisplayName("宽松：写坏在一个反正要被忽略的键上（argument-hint: [a] [b]），整份 skill 不该被拒")
    void lenientDoesNotRejectBecauseOfAnIgnoredKey() {
        ParsedSkill p = SkillManifestParser.parse(skill("name: a\ndescription: d\nargument-hint: [a] [b]"), LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.name()).isEqualTo("a");
        assertThat(p.ignoredKeys()).containsExactly("argument-hint");
    }

    @Test
    @DisplayName("宽松：值里同时有双引号和反斜杠，转义后原样还原")
    void lenientEscapesQuotesAndBackslashes() {
        ParsedSkill p = SkillManifestParser.parse(skill("name: a\ndescription: 用法: 输入 \"a\\b\" 即可"), LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.description()).isEqualTo("用法: 输入 \"a\\b\" 即可");
    }

    @Test
    @DisplayName("宽松：缩进的行不被改动——metadata.jm 照样解析，hosts 恰好是写的那一个")
    void lenientLeavesIndentedLinesAlone() {
        String md = skill("name: a\ndescription: Use when: x\nmetadata:\n  jm:\n    hosts: [api.example.com]");
        ParsedSkill p = SkillManifestParser.parse(md, LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.jm().hosts()).containsExactly("api.example.com");
    }

    @Test
    @DisplayName("宽松：别处有一行要补引号时，单行 flow 风格的 metadata 原样保留——hosts、web 不能被悄悄丢掉")
    void lenientKeepsFlowStyleMetadataIntact() {
        String md = skill("name: a\ndescription: Use when: x\nmetadata: {jm: {hosts: [api.example.com], web: true}}");
        ParsedSkill p = SkillManifestParser.parse(md, LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.description()).isEqualTo("Use when: x");
        assertThat(p.jm().hosts()).containsExactly("api.example.com");
        assertThat(p.jm().web()).isTrue();
    }

    @Test
    @DisplayName("宽松：块风格的 metadata 行尾带注释，别处要补引号时也不能被当成「带 # 的值」改写（改写后下面缩进的 jm 整段会解析失败）")
    void lenientKeepsBlockMetadataWithTrailingComment() {
        String md = skill("name: a\ndescription: Use when: x\nmetadata:  # 平台扩展\n  jm:\n    hosts: [api.example.com]");
        ParsedSkill p = SkillManifestParser.parse(md, LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.jm().hosts()).containsExactly("api.example.com");
    }

    @Test
    @DisplayName("宽松：metadata 自己写坏了照样拒绝，不能被补引号吞成一个字符串而静默通过（不管别处有没有要补引号的行）")
    void lenientStillRejectsBrokenMetadata() {
        String brokenMetadata = "metadata: {jm: {hosts: [api.example.com}}";
        // 别处也有一行要补引号：第二次解析仍然失败 → 拒绝，报作者原文第一次的错误
        ParsedSkill withBadDescription = SkillManifestParser.parse(skill("name: a\ndescription: Use when: x\n" + brokenMetadata), LENIENT);
        assertThat(withBadDescription.valid()).isFalse();
        assertThat(withBadDescription.errors()).singleElement().asString().startsWith("frontmatter 不是合法的 YAML");
        // 只有 metadata 自己写坏：补引号规则什么也不改，所以不重试、也不附「用引号」的提示
        ParsedSkill onlyMetadata = SkillManifestParser.parse(skill("name: a\ndescription: d\n" + brokenMetadata), LENIENT);
        assertThat(onlyMetadata.valid()).isFalse();
        assertThat(onlyMetadata.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").doesNotContain("引号");
    }

    @Test
    @DisplayName("宽松：CRLF 的文件同样能补引号（Windows 上编辑的 SKILL.md）")
    void lenientQuotesCrlfFiles() {
        String md = skill("name: a\ndescription: Use when: x").replace("\n", "\r\n");
        ParsedSkill p = SkillManifestParser.parse(md, LENIENT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.description()).isEqualTo("Use when: x");
    }

    @Test
    @DisplayName("宽松：补引号也救不回来的（缩进错误）照旧拒绝，而且不附「用引号」的提示")
    void lenientStillRejectsWhatQuotingCannotFix() {
        String md = "---\nname: a\n  description: x\n---\nb\n";
        ParsedSkill lenient = SkillManifestParser.parse(md, LENIENT);
        assertThat(lenient.valid()).isFalse();
        assertThat(lenient.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").doesNotContain("引号");
        assertThat(lenient.errors()).as("没有可补的地方，和严格模式报的是同一个错误").isEqualTo(SkillManifestParser.parse(md, STRICT).errors());
    }

    @Test
    @DisplayName("宽松：补了引号仍然解析不了时，报的是作者原文第一次的错误，并带提示（文本确实被改动过）")
    void lenientReportsTheFirstErrorWhenTheRetryFailsToo() {
        String md = "---\nname: a\ndescription: use when: x\n  extra: y\n---\nb\n";
        ParsedSkill lenient = SkillManifestParser.parse(md, LENIENT);
        assertThat(lenient.valid()).isFalse();
        assertThat(lenient.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").contains("引号");
        // 严格模式不重试，它的错误就是「第一次的错误」；宽松模式重试失败后必须是同一条（行号对应作者的文件）
        assertThat(lenient.errors()).isEqualTo(SkillManifestParser.parse(md, STRICT).errors());
    }

    @Test
    @DisplayName("严格模式不重试：没加引号的冒号照旧拒绝，并提示用引号括起来（对拍用例 yaml-unquoted-colon 同类）")
    void strictNeverRetries() {
        ParsedSkill p = SkillManifestParser.parse(skill("name: a\ndescription: use when: x"), STRICT);
        assertThat(p.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").contains("引号");
    }

    @Test
    @DisplayName("「用引号括起来」的提示只在补引号规则确实会改动文本时才附上：缩进错误、嵌套过深都不附")
    void hintOnlyWhenQuotingWouldChangeTheText() {
        ParsedSkill indent = SkillManifestParser.parse("---\nname: a\n  description: x\n---\nb\n", STRICT);
        assertThat(indent.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").doesNotContain("引号");

        // 块映射嵌套 61 层：SnakeYAML 报 Nesting Depth exceeded；这些行都没有「键: 值」，补引号什么也改不了
        StringBuilder deep = new StringBuilder("name: a\ndescription: d\nx:\n");
        for (int i = 1; i <= 60; i++) {
            deep.append(" ".repeat(i)).append('k').append(i).append(i == 60 ? ": v" : ":").append('\n');
        }
        ParsedSkill nested = SkillManifestParser.parse(skill(deep.toString().stripTrailing()), STRICT);
        assertThat(nested.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").doesNotContain("引号");
    }

    @Test
    @DisplayName("metadata 行不参与「会不会改动文本」的判断：只有它带特殊字符、错误出在别处（缩进）时，不重试，也不附「用引号」的提示")
    void hintIgnoresTheMetadataLine() {
        String frontmatter = "name: a\n  description: x\nmetadata: {jm: {web: true}}";
        assertThat(SkillManifestParser.quoteSpecialValues(frontmatter)).isEqualTo(frontmatter);
        for (SkillManifestParser.Mode mode : SkillManifestParser.Mode.values()) {
            ParsedSkill p = SkillManifestParser.parse(skill(frontmatter), mode);
            assertThat(p.errors()).as(mode.name()).singleElement().asString()
                    .startsWith("frontmatter 不是合法的 YAML").doesNotContain("引号");
        }
    }

    @Test
    @DisplayName("重试成功记一条 INFO（带 skill 名）；本来就合法的 YAML 不记")
    void retryIsLoggedOnce() {
        SkillManifestParser.parse(skill("name: needs-quotes\ndescription: Use when: x"), LENIENT);
        assertThat(parserLogs.list).filteredOn(e -> e.getLevel() == Level.INFO).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).contains("needs-quotes").contains("补引号"));

        parserLogs.list.clear();
        SkillManifestParser.parse(skill("name: fine\ndescription: plain"), LENIENT);
        assertThat(parserLogs.list).isEmpty();
    }

    @Test
    @DisplayName("补引号规则逐条照搬 Claude Code 的加载器：该动的动，不该动的原样")
    void quoteRulesFollowClaudeCode() {
        // 改：值里有「冒号 + 空格」，或 {}[]*&#!|>%@` 之一 —— 整个值加双引号
        assertQuoted("description: Use when: x", "description: \"Use when: x\"");
        assertQuoted("description: `code` helper", "description: \"`code` helper\"");
        assertQuoted("k: a #b", "k: \"a #b\"");
        assertQuoted("k: *x", "k: \"*x\"");
        assertQuoted("k: 100%", "k: \"100%\"");
        assertQuoted("k: a|b", "k: \"a|b\"");
        assertQuoted("k: {a}", "k: \"{a}\"");
        // 值里的反斜杠、双引号先转义再包
        assertQuoted("k: say \"hi\" \\o/ x: y", "k: \"say \\\"hi\\\" \\\\o/ x: y\"");
        // 冒号后的多个空白归一成一个空格（只发生在被改动的行）
        assertQuoted("k:   a: b", "k: \"a: b\"");
        // 不改：已经被双引号 / 单引号包住（即使里面有冒号）
        assertUnchanged("k: \"a: b\"");
        assertUnchanged("k: 'a: b'");
        // 不改：合法的 flow 序列；不合法的（[a] [b]）当成普通值补引号
        assertUnchanged("k: [a, b]");
        assertQuoted("k: [a] [b]", "k: \"[a] [b]\"");
        // 不改：没有特殊字符、冒号后面没有空格、没有值、缩进的行、键里有数字、注释行
        assertUnchanged("k: plain text");
        assertUnchanged("k: a:b");
        assertUnchanged("k:");
        assertUnchanged("  k: a: b");
        assertUnchanged("k2: a: b");
        assertUnchanged("# a: b");
        // 行数不变；CRLF 当作 LF 处理
        assertThat(SkillManifestParser.quoteSpecialValues("a: x: y\r\nb: 1\r\n")).isEqualTo("a: \"x: y\"\nb: 1\n");
        assertThat(SkillManifestParser.quoteSpecialValues("a: x: y\nb: 1\nc: z: w").split("\n", -1)).hasSize(3);
    }

    @Test
    @DisplayName("补引号规则跳过的只有键恰好是 metadata 的行（相对 Claude Code 的唯一偏离）：别的键、大小写不同的键照常补引号")
    void quoteSkipsOnlyTheMetadataKey() {
        assertUnchanged("metadata: {jm: {hosts: [api.example.com], web: true}}");
        assertUnchanged("metadata:   {jm: {web: true}}");
        assertUnchanged("metadata:  # 平台扩展");
        assertQuoted("Metadata: {a: b}", "Metadata: \"{a: b}\"");
        assertQuoted("metadata-x: {a: b}", "metadata-x: \"{a: b}\"");
        assertQuoted("meta: {a: b}", "meta: \"{a: b}\"");
    }

    private static void assertQuoted(String line, String expected) {
        assertThat(SkillManifestParser.quoteSpecialValues(line)).isEqualTo(expected);
    }

    private static void assertUnchanged(String line) {
        assertThat(SkillManifestParser.quoteSpecialValues(line)).isEqualTo(line);
    }

    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("metadata.jm 五个字段都能取出；hosts 去重并规范化")
    void extractsJm() {
        String md = "---\nname: a\ndescription: d\nmetadata:\n  author: x\n  jm:\n"
                + "    hosts: [API.example.com, \"*.cdn.example.com\", api.example.com]\n"
                + "    web: true\n    timeout-seconds: 120\n    requires: connections\n    binding: always\n---\nb\n";
        ParsedSkill p = SkillManifestParser.parse(md, STRICT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.jm().hosts()).containsExactly("api.example.com", "*.cdn.example.com");
        assertThat(p.jm().web()).isTrue();
        assertThat(p.jm().timeoutSeconds()).isEqualTo(120);
        assertThat(p.jm().requires()).isEqualTo(SkillRequirement.CONNECTIONS);
        assertThat(p.jm().binding()).isEqualTo(JmMetadata.BINDING_ALWAYS);
    }

    @Test
    @DisplayName("metadata.jm 缺省：hosts 空、web=false、超时 60、无 requires、binding=optional")
    void jmDefaults() {
        ParsedSkill p = SkillManifestParser.parse("---\nname: a\ndescription: d\nmetadata:\n  jm: {}\n---\nb\n", STRICT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.jm()).isEqualTo(new JmMetadata(java.util.List.of(), false, 60, null, JmMetadata.BINDING_OPTIONAL));
    }

    @Test
    @DisplayName("metadata.jm 写错在宽松模式下也报错：那是平台自己的命名空间，拼错不能静默")
    void jmErrorsInBothModes() {
        String md = "---\nname: a\ndescription: d\nmetadata:\n  jm:\n    timeout: 60\n    web: 1\n    binding: sometimes\n---\nb\n";
        ParsedSkill p = SkillManifestParser.parse(md, LENIENT);
        assertThat(p.errors()).anyMatch(e -> e.startsWith("metadata.jm 里有不认识的键") && e.contains("timeout"));
        assertThat(p.errors()).anyMatch(e -> e.startsWith("metadata.jm.web"));
        assertThat(p.errors()).anyMatch(e -> e.startsWith("metadata.jm.binding"));
    }

    @Test
    @DisplayName("hosts 太多或写成字符串都报错")
    void hostsShape() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) many.append(i == 0 ? "" : ", ").append("h").append(i).append(".example.com");
        many.append("]");
        assertThat(SkillManifestParser.parse("---\nname: a\ndescription: d\nmetadata:\n  jm:\n    hosts: " + many + "\n---\nb\n", STRICT)
                .errors()).anyMatch(e -> e.startsWith("metadata.jm.hosts"));
        assertThat(SkillManifestParser.parse("---\nname: a\ndescription: d\nmetadata:\n  jm:\n    hosts: api.example.com\n---\nb\n", STRICT)
                .errors()).anyMatch(e -> e.startsWith("metadata.jm.hosts"));
    }

    @Test
    @DisplayName("hosts 里写错一项：原因只说一次，不出现「不是合法的域名：不是合法的域名」；有具体说法的原因照样带上")
    void hostsErrorStatesTheReasonOnce() {
        String head = "---\nname: a\ndescription: d\nmetadata:\n  jm:\n    hosts: ";
        ParsedSkill generic = SkillManifestParser.parse(head + "[api..example.com]\n---\nb\n", STRICT);
        assertThat(generic.errors()).containsExactly("metadata.jm.hosts 里的「api..example.com」不是合法的域名");

        ParsedSkill scheme = SkillManifestParser.parse(head + "[\"https://api.example.com\"]\n---\nb\n", STRICT);
        assertThat(scheme.errors()).singleElement().asString()
                .startsWith("metadata.jm.hosts 里的「https://api.example.com」").contains("只写域名");
    }

    @Test
    @DisplayName("requires 写错：报错指向 metadata.jm.requires、列出允许的值、回显收到的值；不带 [5007]，也不再提顶层的 frontmatter requires")
    void requiresTypoMessage() {
        ParsedSkill p = SkillManifestParser.parse(skill("name: a\ndescription: d\nmetadata:\n  jm:\n    requires: connection"), STRICT);
        assertThat(p.errors()).singleElement().asString()
                .startsWith("metadata.jm.requires 只能是 ")
                .contains("knowledge_bases").contains("connections").contains("收到：connection")
                .doesNotContain("[5007]").doesNotContain("frontmatter 的 requires");
    }

    @Test
    @DisplayName("★ parse() 本身不构造 ServiceException：它一构造就 log.error，草稿预览反复解析会刷出一串 ERROR 告警")
    void parseDoesNotLogErrors() {
        String typo = skill("name: a\ndescription: d\nmetadata:\n  jm:\n    requires: connection");
        SkillManifestParser.parse(typo, STRICT);
        SkillManifestParser.parse(typo, LENIENT);
        SkillManifestParser.parse(skill("name: A_B\ndescription: <x>\nmetadata:\n  jm:\n    web: 1"), STRICT);
        assertThat(serviceExceptionLogs.list).isEmpty();
    }

    @Test
    @DisplayName("orThrow 把每条错误都列出来；合规时原样返回；requires 的错误里没有 [5007][5007]")
    void orThrowListsErrors() {
        assertThat(SkillManifestParser.parse(OK, STRICT).orThrow().name()).isEqualTo("refund-flow");

        // 一次 orThrow（ServiceException 一构造就 log.error，所以整个测试输出里只有这一条预期的 ERROR）里带三类错误：
        // kebab-case、尖括号、requires 写错。内层文案不带错误码，外层只有 ServiceException 自己加的那一个。
        String md = skill("name: A_B\ndescription: <x>\nmetadata:\n  jm:\n    requires: connection");
        assertThatThrownBy(() -> SkillManifestParser.parse(md, STRICT).orThrow())
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("kebab-case").hasMessageContaining("尖括号")
                .hasMessageContaining("metadata.jm.requires 只能是")
                .hasMessageNotContaining("[5007][5007]")
                .satisfies(t -> assertThat(((ServiceException) t).getRespMsg()).doesNotContain("[5007]"));
    }
}
