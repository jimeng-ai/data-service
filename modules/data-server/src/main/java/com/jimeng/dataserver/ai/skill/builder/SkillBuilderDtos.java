package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.chat.dto.ChatDtos;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Skill 构建器接口的请求 / 响应。id 一律按字符串下发（雪花 id 超出 JS 安全整数）。 */
public final class SkillBuilderDtos {

    private SkillBuilderDtos() {}

    @Data
    public static class CreateSessionRequest {
        /** 改进已有 skill 时传目标 skill id；新建时不传。 */
        private String baseSkillId;
    }

    @Data
    public static class SessionView {
        private String sessionId;
        private String conversationId;
        /** ACTIVE / PUBLISHED / ABANDONED */
        private String status;
        private String baseSkillId;
        private Integer baseVersion;
        /** 改进已有 skill 时的名字（发布时不允许改名）。 */
        private String baseSkillName;
        private String skillTypeOverride;
        private DraftView draft;
        private ReviewMeta review;
        private boolean hasOptimizationReport;
        /** 正在生成的那一轮（刷新页面后据此重连 /runs/{runId}/stream）；没有则为 null。 */
        private String activeRunId;
        private List<ChatDtos.MessageView> messages;
    }

    @Data
    public static class DraftView {
        /** skill 在工作区里的目录名（/work/skill/&lt;dirName&gt;/）。 */
        private String dirName;
        private String name;
        private String description;
        /** SKILL.md 全文（含 frontmatter）。 */
        private String skillMd;
        private String body;
        /** 按附带文件推断的运行方式。 */
        private String inferredType;
        /** 实际生效的运行方式（用户指定优先）。 */
        private String effectiveType;
        private List<DraftFile> files;
        /** 按 skill-creator quick_validate 规则校验出的问题；空 = 可以发布。 */
        private List<String> validationErrors;
        /** 已跑过几轮测试（iteration-N 目录数）。 */
        private int iterations;
        /** 工作区里不止一个 skill 目录时列出全部（发布取最近修改的那个）。 */
        private List<String> otherSkillDirs;
        /**
         * 工作区里有 secrets.json、却读不出来（不是合法 JSON、超过大小上限、key 或候选值太多、有值太长……）：不知道哪些是
         * key 的值，草稿里的文字没法抹值。此时<b>一律不给 skill 文件的原文</b>：name / description 为 null，skillMd / body 为空串
         * （前端靠这两个字段切 frontmatter，不能是 null），每个文件的 text 为 null，secrets.json 也没有预览；
         * 校验结果只有 secrets.json 自己的固定文案（不带值、不回显条目）；DRAFT 行不同步。
         */
        private boolean secretsUnreadable;
        /**
         * SKILL.md 超过预览文件上限（skill.builder.preview-file-max-bytes，默认 512 KB）：不读、不解析、不抹值。
         * 运行时整份 SKILL.md 都会放进模型的上下文，这么大本来就用不了。此时 name / description 为 null，skillMd / body 为空串，
         * 校验结果是一句固定的话；DRAFT 行不同步。
         */
        private boolean skillMdTooLarge;
    }

    @Data
    public static class DraftFile {
        /** 相对 skill 目录的路径。 */
        private String path;
        private long size;
        /** 文本内容；二进制或超过预览上限时为 null。 */
        private String text;
        private boolean binary;
        /** 是否属于 skill 的运行时内容（根目录 evals/ 与缓存文件为 false）。 */
        private boolean runtime;
    }

    @Data
    public static class ReviewMeta {
        private int iteration;
        private String updatedAt;
        private boolean feedbackSubmitted;
    }

    @Data
    public static class ReviewView {
        private int iteration;
        private String html;
        /** 已提交的 feedback.json（原样的对象），没有则为 null。 */
        private Object feedback;
        /** 评审页超过大小上限时为 true（html 为空）。 */
        private boolean tooLarge;
    }

    @Data
    public static class ReportView {
        private String html;
        private boolean tooLarge;
    }

    @Data
    public static class SkillTypeRequest {
        /** PROMPT / DOER；null = 恢复自动推断。 */
        private String skillType;
    }

    @Data
    public static class FeedbackRequest {
        /** 反馈对应的评审轮次。 */
        private Integer iteration;
        /** 评审页提交的原样对象：{"reviews":[{"run_id","feedback","timestamp"}...],"status":"complete|in_progress"} */
        private Map<String, Object> feedback;
    }

    @Data
    public static class PublishResult {
        private String skillId;
        private String name;
        private Integer version;
        private String status;
        private String skillType;
        /** 不拦发布、但值得告诉用户的情况（没跑过测试、最近一轮评审没提交反馈……）。 */
        private List<String> warnings;
    }

    /** 每轮结束后经 SSE 推给前端的 draft-update 事件体。 */
    @Data
    public static class DraftUpdateEvent {
        private DraftView draft;
        private ReviewMeta review;
        private boolean hasOptimizationReport;
        /** 这一轮工作区是否完整写回（边车 summary.workspacePersisted）。 */
        private Boolean workspacePersisted;
    }
}
