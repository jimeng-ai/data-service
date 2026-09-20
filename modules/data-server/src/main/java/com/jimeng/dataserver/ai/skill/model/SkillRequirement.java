package com.jimeng.dataserver.ai.skill.model;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;

/**
 * 一个 Skill 声明的<b>前置资源</b>（SKILL.md frontmatter 的 {@code requires:}）。
 *
 * <h3>它解决的问题</h3>
 * 在此之前，Skill 只能声明「我是谁、我能干什么」（{@code name} / {@code description}），
 * 不能声明<b>「我什么时候该出现」</b>。后者被硬编码在 {@code SkillRuntimeService} 里，
 * 于是每加一个有前置资源的平台 Skill 就得改一次运行时——而这件事已经出过一次代价：
 *
 * <ul>
 *   <li>{@code rag-knowledge} 先撞上：绑了知识库才该直注，没绑就该隐藏。当时的办法是加一段
 *       {@code resolveRagSkillVisibility} 硬编码 if。</li>
 *   <li>{@code connector} 第二个撞上：绑了连接才该直注。<b>这一段没有人写</b>，于是它一直走
 *       discovery——模型每一轮都得先自己调一次 {@code activate_skills} 才拿得到 {@code conn_*}，
 *       而表清单概览却在同一轮无条件塞进了 system。</li>
 * </ul>
 *
 * 同一个形状出现第二次就该抽象。本枚举把「什么时候出现」交还给 Skill 自己声明。
 *
 * <h3>★ 未知值必须当场报错，不能静默忽略</h3>
 * frontmatter 的解析对未知键一向是静默跳过的（{@code argument-hint:} 今天就被默默丢着）。
 * 但 {@code requires} 不能照办：写成 {@code requires: connection}（少个 s）而被忽略，
 * 后果正是<b>今天这个 bug 的形状</b>——该直注的 Skill 安静地回退成走发现，没有任何报错，
 * 表现只是「模型有时候会用、有时候不会」。所以 {@link #parse} 对认不出来的值直接抛。
 */
public enum SkillRequirement {

    /** 需要当前 Agent 绑定了知识库（{@code agent.kbIds} 非空）。 */
    KNOWLEDGE_BASES("knowledge_bases"),

    /** 需要当前 Agent 被授权了外部连接（{@code agent_connection} 里有行）。 */
    CONNECTIONS("connections");

    private final String token;

    SkillRequirement(String token) {
        this.token = token;
    }

    /** frontmatter 里写的字面量。 */
    public String token() {
        return token;
    }

    /**
     * 解析 frontmatter 的 {@code requires} 值。
     *
     * @param raw 原始字面量；null / 空白表示<b>没有前置资源</b>（走发现，即改动前的行为）
     * @return 对应枚举；{@code raw} 为空时返回 null
     * @throws ServiceException 认不出来的值——理由见类注释，这里绝不能兜底成 null
     */
    public static SkillRequirement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        for (SkillRequirement r : values()) {
            if (r.token.equalsIgnoreCase(v)) {
                return r;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (SkillRequirement r : values()) {
            if (allowed.length() > 0) allowed.append(" / ");
            allowed.append(r.token);
        }
        throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                "SKILL.md frontmatter 的 requires 只能是 " + allowed + "，收到：" + v);
    }
}
