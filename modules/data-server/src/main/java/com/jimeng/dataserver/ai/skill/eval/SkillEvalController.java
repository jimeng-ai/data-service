package com.jimeng.dataserver.ai.skill.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.skill.builder.SkillDraft;
import com.jimeng.dataserver.ai.skill.builder.SkillDraftStore;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import com.jimeng.dataserver.ai.skill.util.SkillMarkdownParser;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillEvalRun;
import com.jimeng.persistence.mapper.AiSkillMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Skill 评测入口。
 *
 * <p>三个来源：构建器里的<b>草稿</b>（还没发布，最需要测）、已入库的 skill，以及
 * <b>磁盘上的平台技能</b>（{@code skills/&lt;name&gt;/}，{@code connector} 就是这一类——它不在
 * {@code ai_skill} 表里，少了这条来源它的 {@code evals/evals.json} 永远跑不起来）。
 * 三者都从各自的文件表里取 {@code evals/evals.json}——用例跟着 skill 包走，不单独入库。
 */
@Tag(name = "Skill 评测", description = "拿真沙箱跑用例 + 模型评委判定")
@RestController
@RequestMapping("/data/skills/eval")
@RequiredArgsConstructor
public class SkillEvalController {

    /** 用例在 skill 包里的固定位置，沿用 Anthropic skill-creator 的布局。 */
    private static final String EVALS_PATH = "evals/evals.json";

    private final SkillEvalService evalService;
    private final SkillDraftStore draftStore;
    private final AiSkillMapper aiSkillMapper;
    private final SkillMaterializer materializer;
    private final SkillPackageLoaderService skillPackageLoaderService;

    @Data
    public static class StartRequest {
        /** 三选一：构建器会话 id（测草稿）*/
        private Long conversationId;
        /** 三选一：已入库 skill 的 id */
        private Long skillId;
        /** 三选一：磁盘上的平台技能名（如 {@code connector}），它不在 ai_skill 表里 */
        private String skillName;
        /** RECALL（默认，测模型会不会想到用）| CAPABILITY（测用对没用对）*/
        private String mode;
        /**
         * 这一轮沙箱 run 挂在哪个 Agent 身上。
         *
         * <p><b>评测 {@code connector} 时必填</b>：沙箱里的 {@code conn_*} 工具钉在一个真实 Agent 上
         * （回调 token 的 {@code aid} claim + {@code agent_connection} 实时授权），凭空造一个会绕过授权。
         * 缺了 {@code SkillEvalService.start} 会直接报错，而不是静默跑一个没有工具的 run——
         * 那样每条用例都会因为「模型调不到工具」而失败，得到的是一个假红灯。
         *
         * <p>其它 skill 可以不填（不填就不下发连接器工具，与改动前一致）。
         */
        private Long agentId;
    }

    @Operation(summary = "发起一轮评测（立即返回，后台串行跑）")
    @PostMapping("/runs")
    public SkillEvalRun start(@RequestBody StartRequest req) {
        if (req == null || (req.getConversationId() == null && req.getSkillId() == null
                && (req.getSkillName() == null || req.getSkillName().isBlank()))) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "conversationId / skillId / skillName 必须给一个");
        }
        String mode = req.getMode() == null ? SkillEvalService.MODE_RECALL : req.getMode().toUpperCase();

        if (req.getConversationId() != null) {
            SkillDraft d = draftStore.current(req.getConversationId());
            if (d == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "该会话没有草稿");
            Map<String, String> files = d.getFiles() == null ? Map.of() : d.getFiles();
            return evalService.start(d.getName(), d.getBody(), files,
                    parseSuite(files.get(EVALS_PATH)), mode, null, req.getConversationId(), req.getAgentId());
        }
        if (req.getSkillId() == null) {
            // 磁盘平台技能（connector / rag-knowledge…）：正文与随包文件都在 skills/<name>/ 下。
            String name = req.getSkillName().trim();
            SkillPackage pkg = skillPackageLoaderService.findByName(
                    skillPackageLoaderService.loadSkillPackages(), name);
            if (pkg == null) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "平台技能不存在: " + name);
            }
            Map<String, String> files = readPackageFiles(pkg);
            // loader 已经把 frontmatter 剥掉了，这里按规范重建一份再物化——缺 frontmatter 的 SKILL.md
            // 沙箱里的 SDK 读不到 description，技能等于没装上，而这恰恰是 RECALL 模式要测的那个信号。
            String body = SkillMarkdownParser.render(pkg.getName(), pkg.getDescription(), pkg.getBody());
            return evalService.start(pkg.getName(), body, files,
                    parseSuite(files.get(EVALS_PATH)), mode, null, null, req.getAgentId());
        }
        AiSkill s = aiSkillMapper.selectById(req.getSkillId());
        if (s == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "skill 不存在");
        // 已发布 skill：正文在 DB（body）、随包文件在 MinIO bundle。读回文件表拿 evals/evals.json，
        // 再走与草稿同一套 evalService.start（内部会把 body+files 重新物化进沙箱）。
        Map<String, String> files = materializer.readBundleFiles(s.getBundleKey());
        return evalService.start(s.getName(), s.getBody(), files,
                parseSuite(files.get(EVALS_PATH)), mode, s.getId(), null, req.getAgentId());
    }

    @Operation(summary = "查一轮评测的进度/结果")
    @GetMapping("/runs/{id}")
    public SkillEvalRun get(@PathVariable Long id) {
        return evalService.get(id);
    }

    /**
     * 把磁盘技能包里除 {@code SKILL.md} 之外的文件读成「相对路径 → 内容」表，
     * 形状与草稿的 {@code files} / 已发布 skill 的 bundle 一致，于是三条来源之后的代码完全相同。
     *
     * <p>{@code SKILL.md} 单独排除：它由调用方用 {@link SkillMarkdownParser#render} 重建 frontmatter
     * 之后当作 {@code body} 传，再混进 files 会在沙箱里被覆盖成没有 frontmatter 的那一份。
     */
    private Map<String, String> readPackageFiles(SkillPackage pkg) {
        Map<String, String> out = new LinkedHashMap<>();
        Path root = pkg.getRootPath();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(f).toString().replace("\\", "/");
                if ("SKILL.md".equals(rel)) continue;
                out.put(rel, Files.readString(f, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR,
                    "读取平台技能目录失败: " + e.getMessage());
        }
        return out;
    }

    private EvalSuite parseSuite(String json) {
        if (json == null || json.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "没有 " + EVALS_PATH + "：请先为该 skill 写测试用例（构建器可自动生成）");
        }
        try {
            return CommonUtil.getObjectMapper().readValue(json, new TypeReference<EvalSuite>() {});
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, EVALS_PATH + " 不是合法 JSON: " + e.getMessage());
        }
    }
}
