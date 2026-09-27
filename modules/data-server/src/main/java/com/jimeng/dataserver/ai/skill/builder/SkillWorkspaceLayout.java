package com.jimeng.dataserver.ai.skill.builder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建器工作区的目录约定——与 jm-agent-sandbox {@code src/profiles/skillBuilderContract.ts} 写给模型的
 * 约定是<b>同一份契约</b>，改一边必须改另一边（边车的 {@code SKILL_BUILDER_VERSION} 随之加 1）：
 *
 * <pre>
 * {prefix}skill/&lt;name&gt;/                         被构建的 skill（SKILL.md + 附带文件），产品据此读草稿、发布
 * {prefix}skill/&lt;name&gt;-workspace/                skill-creator 的工作区（与 skill 目录同级，skill-creator 自己的约定）
 * {prefix}skill/&lt;name&gt;-workspace/iteration-N/review.html      第 N 轮评审页（generate_review.py --static）
 * {prefix}skill/&lt;name&gt;-workspace/iteration-N/feedback.json    用户在评审页提交的反馈（与服务器模式同一位置）
 * {prefix}skill/&lt;name&gt;-workspace/description-optimization/report.html   触发优化报告（run_loop --report）
 * </pre>
 *
 * {@code prefix} 是 MinIO 上的会话工作区根，对应容器里的 {@code /work/}。
 */
public final class SkillWorkspaceLayout {

    private SkillWorkspaceLayout() {}

    public static final String SKILL_ROOT = "skill/";
    public static final String WORKSPACE_SUFFIX = "-workspace";
    public static final String REVIEW_FILE = "review.html";
    public static final String FEEDBACK_FILE = "feedback.json";
    public static final String OPTIMIZATION_REPORT = "description-optimization/report.html";

    /** skill/&lt;dir&gt;-workspace/iteration-&lt;N&gt;/review.html */
    private static final Pattern REVIEW = Pattern.compile(
            "^skill/([^/]+)" + Pattern.quote(WORKSPACE_SUFFIX) + "/iteration-(\\d{1,4})/" + Pattern.quote(REVIEW_FILE) + "$");

    public static String workspacePrefix(String root, String tenantId, Long sessionId) {
        return root + "/" + tenantId + "/" + sessionId + "/ws/";
    }

    /** 相对工作区根的路径是否是某个 skill 目录下的 SKILL.md（skill/&lt;dir&gt;/SKILL.md，dir 不是工作区）。返回 dir 或 null。 */
    public static String skillDirOfSkillMd(String rel) {
        if (!rel.startsWith(SKILL_ROOT) || !rel.endsWith("/SKILL.md")) return null;
        String dir = rel.substring(SKILL_ROOT.length(), rel.length() - "/SKILL.md".length());
        if (dir.isEmpty() || dir.contains("/") || dir.endsWith(WORKSPACE_SUFFIX)) return null;
        return dir;
    }

    public static String skillDirPrefix(String dir) {
        return SKILL_ROOT + dir + "/";
    }

    public static String workspaceDirPrefix(String dir) {
        return SKILL_ROOT + dir + WORKSPACE_SUFFIX + "/";
    }

    /** 匹配评审页路径，返回 [skillDir, iteration]；不匹配返回 null。 */
    public static Object[] matchReview(String rel) {
        Matcher m = REVIEW.matcher(rel);
        if (!m.matches()) return null;
        return new Object[]{m.group(1), Integer.parseInt(m.group(2))};
    }

    public static String reviewPath(String dir, int iteration) {
        return workspaceDirPrefix(dir) + "iteration-" + iteration + "/" + REVIEW_FILE;
    }

    public static String feedbackPath(String dir, int iteration) {
        return workspaceDirPrefix(dir) + "iteration-" + iteration + "/" + FEEDBACK_FILE;
    }

    public static String optimizationReportPath(String dir) {
        return workspaceDirPrefix(dir) + OPTIMIZATION_REPORT;
    }
}
