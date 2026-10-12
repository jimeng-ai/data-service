package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.skill.model.ToolPackage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * 本轮已激活的 skill（设计文档 §4.1「对话平面里指本轮已激活的 skill」）。key、hosts、web 只从这些 skill 里取。
 *
 * <p>记录点只有一个：{@code SkillRuntimeService.injectFullSkillContext}——显式点名、按 requires 提升、activate_skills
 * 三条路都经过它。对话循环在一轮开始、结束时 begin / end。一轮在同一个线程里跑完（工具也在这个线程上串行执行），
 * 所以用线程内的栈；用栈不用单值，是防嵌套调用（工具里再调一次模型）把外层清空。
 */
public final class ActivatedSkills {

    private static final ThreadLocal<Deque<List<ToolPackage>>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private ActivatedSkills() {}

    public static void begin() {
        FRAMES.get().push(new ArrayList<>());
    }

    public static void end() {
        Deque<List<ToolPackage>> frames = FRAMES.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
    }

    /** 没开轮次时是空操作（例如构建器的触发测试只拼请求、不跑循环）。 */
    public static void add(Collection<? extends ToolPackage> packages) {
        Deque<List<ToolPackage>> frames = FRAMES.get();
        if (frames.isEmpty()) {
            FRAMES.remove();
            return;
        }
        frames.peek().addAll(packages);
    }

    public static List<ToolPackage> current() {
        Deque<List<ToolPackage>> frames = FRAMES.get();
        List<ToolPackage> out = frames.isEmpty() ? List.of() : List.copyOf(frames.peek());
        if (frames.isEmpty()) FRAMES.remove();
        return out;
    }
}
