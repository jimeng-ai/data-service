package com.jimeng.dataserver.ai.skill.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.skill.builder.SkillDraft;
import com.jimeng.dataserver.ai.skill.builder.SkillDraftStore;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillEvalRun;
import com.jimeng.persistence.mapper.AiSkillMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Skill 评测入口。
 *
 * <p>两个来源：构建器里的<b>草稿</b>（还没发布，最需要测），和已入库的 skill。
 * 两者都从各自的文件表里取 {@code evals/evals.json}——用例跟着 skill 包走，不单独入库。
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

    @Data
    public static class StartRequest {
        /** 二选一：构建器会话 id（测草稿）*/
        private Long conversationId;
        /** 二选一：已入库 skill 的 id */
        private Long skillId;
        /** RECALL（默认，测模型会不会想到用）| CAPABILITY（测用对没用对）*/
        private String mode;
    }

    @Operation(summary = "发起一轮评测（立即返回，后台串行跑）")
    @PostMapping("/runs")
    public SkillEvalRun start(@RequestBody StartRequest req) {
        if (req == null || (req.getConversationId() == null && req.getSkillId() == null)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "conversationId 与 skillId 必须给一个");
        }
        String mode = req.getMode() == null ? SkillEvalService.MODE_RECALL : req.getMode().toUpperCase();

        if (req.getConversationId() != null) {
            SkillDraft d = draftStore.current(req.getConversationId());
            if (d == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "该会话没有草稿");
            Map<String, String> files = d.getFiles() == null ? Map.of() : d.getFiles();
            return evalService.start(d.getName(), d.getBody(), files,
                    parseSuite(files.get(EVALS_PATH)), mode, null, req.getConversationId());
        }
        AiSkill s = aiSkillMapper.selectById(req.getSkillId());
        if (s == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "skill 不存在");
        // 已发布 skill：正文在 DB（body）、随包文件在 MinIO bundle。读回文件表拿 evals/evals.json，
        // 再走与草稿同一套 evalService.start（内部会把 body+files 重新物化进沙箱）。
        Map<String, String> files = materializer.readBundleFiles(s.getBundleKey());
        return evalService.start(s.getName(), s.getBody(), files,
                parseSuite(files.get(EVALS_PATH)), mode, s.getId(), null);
    }

    @Operation(summary = "查一轮评测的进度/结果")
    @GetMapping("/runs/{id}")
    public SkillEvalRun get(@PathVariable Long id) {
        return evalService.get(id);
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
