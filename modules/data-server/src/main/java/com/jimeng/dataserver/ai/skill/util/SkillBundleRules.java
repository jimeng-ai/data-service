package com.jimeng.dataserver.ai.skill.util;

import com.jimeng.dataserver.ai.skill.SkillConst;

import java.util.Collection;
import java.util.Set;

/**
 * skill 目录里哪些文件算数、哪些要下发给运行时、它在产品里该用哪种方式运行——三条规则的唯一出处。
 *
 * <p>前两条与 Anthropic skill-creator 的 {@code scripts/package_skill.py} 同源：任意层级的
 * {@code __pycache__/}、{@code node_modules/}、{@code *.pyc}、{@code .DS_Store} 不是 skill 的一部分；
 * 根目录的 {@code evals/} 是测试用例（含断言），<b>存进 bundle，但不下发给任何运行</b>——
 * 下发了就等于把答案放进被测模型能读到的目录（评测时），或者平白发到每一次生产运行里。
 *
 * <p>第三条（运行方式）必须与 jm-agent-sandbox {@code vendor/skill-creator/scripts/run_eval.py} 的
 * {@code _has_bundled_files} 逐条一致：那边据此决定 PROMPT 类触发测试走哪套机制，两边判得不一样，
 * 触发优化就是在为另一种运行方式调 description。改一边必须改另一边。
 */
public final class SkillBundleRules {

    private SkillBundleRules() {}

    private static final Set<String> JUNK_DIRS = Set.of("__pycache__", "node_modules");
    private static final Set<String> JUNK_FILES = Set.of(".DS_Store");
    private static final String EVALS_DIR = "evals";
    public static final String SKILL_MD = "SKILL.md";

    /** 相对 skill 根目录的 posix 路径是否是 skill 的一部分（不是缓存 / 依赖 / 系统垃圾，也没有越界段）。 */
    public static boolean isPartOfSkill(String relPath) {
        if (relPath == null || relPath.isBlank()) return false;
        String[] parts = relPath.split("/");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.equals(".") || p.equals("..")) return false;
            if (i < parts.length - 1 && JUNK_DIRS.contains(p)) return false;
        }
        String name = parts[parts.length - 1];
        return !JUNK_FILES.contains(name) && !name.endsWith(".pyc");
    }

    /** 这个文件要不要下发给运行时（沙箱 DOER 运行、评测运行）：是 skill 的一部分，且不在根目录 evals/ 下。 */
    public static boolean isRuntimeFile(String relPath) {
        return isPartOfSkill(relPath) && !isRootEvalsPath(relPath);
    }

    public static boolean isRootEvalsPath(String relPath) {
        return relPath != null && (relPath.equals(EVALS_DIR) || relPath.startsWith(EVALS_DIR + "/"));
    }

    /**
     * 按附带文件推断运行方式：除 SKILL.md 与根目录 evals/ 之外还有任何文件 → DOER（只能在沙箱执行：
     * 对话平面只注入 SKILL.md 正文，读不到任何附带文件）；否则 PROMPT。
     *
     * @param relPaths skill 目录下全部文件的相对路径
     */
    public static String inferSkillType(Collection<String> relPaths) {
        for (String p : relPaths) {
            if (!isRuntimeFile(p)) continue;
            if (SKILL_MD.equals(p)) continue;
            return SkillConst.TYPE_DOER;
        }
        return SkillConst.TYPE_PROMPT;
    }
}
