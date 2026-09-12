package com.jimeng.dataserver.ai.skill.eval;

/**
 * 评测用的两段提示词。
 *
 * <p>评委提示词的<b>判断标准部分逐条译自 Anthropic skill-creator 的 agents/grader.md</b>，
 * 刻意保留其中三条最容易被写丢的规则：
 * <ul>
 *   <li>表面合规不算通过（文件名对但内容空 = FAIL）；</li>
 *   <li>存疑时举证责任在「期望方」，即判 FAIL；</li>
 *   <li>评委的第二份工作是<b>批评测试本身</b>——弱断言通过比不测还糟，它制造虚假信心。</li>
 * </ul>
 * 原文里读 transcript 文件 / 写 grading.json 那套机制换成了内联输入 + 直接返回 JSON，
 * 因为这里是一次普通的 LLM 调用，不是有文件系统的子 agent。
 */
public final class EvalPrompts {

    private EvalPrompts() {
    }

    /**
     * RECALL 模式的运行提示词：<b>原样使用用户原话，绝不提 skill 名字</b>。
     *
     * <p>这是整套评测里最关键的一条。现有的 testRun 提示词写的是
     * 「用名为「X」的 skill 处理输入」——那测的是"用了之后做得对不对"，
     * 测不出"模型会不会想到用"。而后者才是静默失败的来源：description 写不好，
     * skill 就等于不存在，请求 200、回复正常、只是那套规矩没生效。
     */
    public static String recallPrompt(String userPrompt) {
        return userPrompt;
    }

    /** CAPABILITY 模式：明确要求使用，测"用对没用对"。等价于现有 testRun 的语义。 */
    public static String capabilityPrompt(String skillName, String userPrompt) {
        return "请使用名为「" + skillName + "」的 skill 完成下面这件事。\n\n" + userPrompt;
    }

    /**
     * 评委系统提示词。要求严格返回 JSON，不要 markdown 代码围栏。
     *
     * @param recallMode true 时额外判定「模型是否真的动用了被测 skill」——
     *                   这是 RECALL 模式的核心结论，比断言通过率更重要：
     *                   一个根本没被调用的 skill，断言全过也只说明模型自己凭常识答对了。
     */
    public static String graderSystem(boolean recallMode) {
        StringBuilder s = new StringBuilder();
        s.append("""
                你是评测员。给你一次 agent 执行的完整记录（transcript）和它产出的文件清单，\
                请对照给定的期望逐条判定通过与否。

                你有两份工作：给结果打分，以及**批评这些测试本身**。\
                一条弱断言通过了，比没测还糟——它制造虚假信心。当你发现某条断言轻易就能满足、\
                或某个重要结果根本没有断言在检查，直接说出来。

                ## 判定标准

                判 PASS 的条件：
                - 记录或产物中有明确证据表明该期望成立；
                - 能引用到具体证据；
                - 证据反映的是**真实完成**，不是表面合规（文件存在【且】内容正确，不能只有文件名对）。

                判 FAIL 的条件：
                - 找不到证据；
                - 证据与期望矛盾；
                - 现有信息无法验证该期望；
                - 证据流于表面——断言技术上被满足了，但任务结果是错的或不完整；
                - 看起来像是碰巧满足，而不是真的把活干了。

                **存疑时判 FAIL。举证责任在「期望成立」这一方。**

                ## 除了给定期望，还要抽取并核实隐含主张

                从记录和产物里找出模型自己声称的事实（"表格有 12 个字段"）、过程（"用了 xx 脚本"）、\
                质量（"所有字段都填对了"），逐条核实。无法核实的标为 verified=false 并说明。\
                这能抓到预设断言漏掉的问题。

                ## 评分尺度

                不给部分分：每条期望只有通过或不通过。
                """);
        if (recallMode) {
            s.append("""

                    ## 本次是【召回测试】，额外判定一件更重要的事

                    提示词里**没有**提到 skill 的名字，用的是用户原话。所以首要判定是：
                    **模型到底有没有动用这个 skill？**

                    从记录里找证据：是否调用了 Skill 工具、是否读取了该 skill 目录下的文件、\
                    是否执行了它的脚本。把结论写进 skillInvoked / skillInvokedEvidence。

                    注意区分：如果模型**没有**调用 skill，却凭自身常识把答案答对了，\
                    断言可能全部通过——但这恰恰说明 **description 没能触发它**，是一次失败的召回。\
                    这种情况下 skillInvoked 必须为 false，并在 evalFeedback.overall 里点明。
                    """);
        }
        s.append("""

                ## 输出

                只返回一个 JSON 对象，不要任何解释文字，不要 markdown 代码围栏。结构：

                {
                  "expectations": [{"text": "...", "passed": true, "evidence": "引用具体证据"}],
                  "summary": {"passed": 0, "failed": 0, "total": 0, "passRate": 0.0},
                  "claims": [{"claim": "...", "type": "factual|process|quality", "verified": true, "evidence": "..."}],
                  "evalFeedback": {
                    "suggestions": [{"assertion": "相关断言原文，可省略", "reason": "为什么这条测试需要改进"}],
                    "overall": "整体评价；没有可改进处就写「测试写得扎实，无建议」"
                  }""");
        if (recallMode) {
            s.append(",\n  \"skillInvoked\": true,\n  \"skillInvokedEvidence\": \"记录里的具体证据\"\n}");
        } else {
            s.append("\n}");
        }
        return s.toString();
    }

    /** 评委的用户消息：把这一轮的输入内联进去（原版是让评委自己去读文件，这里没有文件系统）。 */
    public static String graderUser(String skillName, String prompt, String expectedOutput,
                                    java.util.List<String> expectations, String transcript,
                                    java.util.List<String> artifactNames) {
        StringBuilder s = new StringBuilder();
        s.append("被测 skill：").append(skillName).append("\n\n");
        s.append("## 给模型的提示词\n").append(prompt).append("\n\n");
        if (expectedOutput != null && !expectedOutput.isBlank()) {
            s.append("## 期望结果（人读描述）\n").append(expectedOutput).append("\n\n");
        }
        s.append("## 待判定的期望\n");
        if (expectations == null || expectations.isEmpty()) {
            s.append("（本用例没有写断言。请只判定 skillInvoked，并在 evalFeedback 里指出缺少断言。）\n");
        } else {
            for (int i = 0; i < expectations.size(); i++) {
                s.append(i + 1).append(". ").append(expectations.get(i)).append("\n");
            }
        }
        s.append("\n## 执行记录\n").append(transcript == null || transcript.isBlank() ? "（空——本轮没有产生任何事件）" : transcript);
        s.append("\n\n## 产出文件\n");
        if (artifactNames == null || artifactNames.isEmpty()) {
            s.append("（无）");
        } else {
            artifactNames.forEach(n -> s.append("- ").append(n).append("\n"));
        }
        return s.toString();
    }
}
