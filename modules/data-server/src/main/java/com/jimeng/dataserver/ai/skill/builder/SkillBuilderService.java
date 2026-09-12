package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Skill 构建器 Agent 懒建 + 元提示词。镜像 {@link com.jimeng.dataserver.ai.agent.builder.AgentBuilderService}
 * 的「构建器 Agent」做法：用一个隐藏的 PUBLISHED Agent 承载构建器自身的强模型 + meta-prompt，
 * 经 agent_id/agent_preview 让 ClaudeService 设置 AgentContext（构建器模式下不会注入其它插件/技能，
 * 仅 draft_skill 在场，由 SkillRuntimeService 据 {@code __skill_builder_mode__} 短路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderService {

    public static final String BUILDER_AGENT_CODE = "__skill_builder__";
    /** 构建器自身用的模型（强模型，与对外可选目录解耦）。 */
    private static final String BUILDER_MODEL = "claude-sonnet-4-6";

    private final AgentMapper agentMapper;

    /** 取（或懒建）当前租户的 Skill 构建器 Agent。 */
    public Agent ensureBuilderAgent() {
        String tenantId = TenantContext.get();
        Agent existing = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId)
                .eq(Agent::getCode, BUILDER_AGENT_CODE)
                .last("limit 1"));
        if (existing != null) {
            // 自愈：构建器 meta-prompt 升级后（如新增「从 GitHub 导入」一节），把已存在的
            // 构建器 Agent 行同步到最新提示词——否则老租户的旧行永远拿不到新指引。
            String latest = builderMetaPrompt();
            if (!latest.equals(existing.getSystemPrompt())) {
                existing.setSystemPrompt(latest);
                agentMapper.updateById(existing);
                log.info("已刷新租户 {} 的 Skill 构建器 Agent 提示词 id={}", tenantId, existing.getId());
            }
            return existing;
        }

        Agent a = new Agent();
        a.setCode(BUILDER_AGENT_CODE);
        a.setName("Skill 构建器");
        a.setDescription("通过对话帮你设计一个可复用的 Skill 的内置助手");
        a.setStatus("PUBLISHED");
        a.setModel(BUILDER_MODEL);
        a.setSystemPrompt(builderMetaPrompt());
        agentMapper.insert(a);
        log.info("已为租户 {} 懒建 Skill 构建器 Agent id={}", tenantId, a.getId());
        return a;
    }

    private String builderMetaPrompt() {
        return """
                你是「Skill 构建器」，通过对话帮用户「新建」或「从 GitHub 导入」一个可复用的 Skill（技能）。
                先判断用户的意图属于哪一种，再走对应流程。

                ## 一、新建 Skill（用户要从零设计一个技能）
                - 先弄清这个 Skill 要解决什么：用途、典型触发场景、输入与产出、是否需要跑脚本处理文件。信息不全时一次只问一个最关键的问题，问题要具体、尽量给选项。
                - 每当从用户处获得新信息，就调用 draft_skill 工具把对应字段写入草稿（只传本轮有变化的字段）；右侧预览会实时展示草稿，正文里不要大段复述。
                - 判断 skillType：纯操作指引 → PROMPT；需要跑脚本处理文件（如 csv→xlsx、批量改图）→ DOER。
                - 生成 DOER 时，files 给出可直接运行的脚本（相对路径 → 文件内容），脚本要自包含、依赖常见库；建议先让用户给一份样例输入文件以便沙箱试跑。
                - 核心信息齐了之后，主动小结要点并提示用户「可以先试跑验证，再点『创建 Skill』」。

                ## 一之二、写测试用例（PROMPT 和 DOER 都要写，不是可选项）

                草稿的 name / description / body 基本成型后，**主动**把测试用例写进
                `files` 的 `evals/evals.json`，然后告诉用户可以跑评测了。不要等用户开口要。

                为什么必须有：description 写得不好时，模型根本不会想到用这个 Skill ——
                而且**完全不报错**，用户只会觉得「这技能好像没生效」，查不出原因。
                测试用例是唯一能提前发现这件事的手段。

                格式（固定，不要改字段名）：

                {
                  "skill_name": "与 name 一致",
                  "evals": [
                    {
                      "id": 1,
                      "prompt": "用户会真实说出来的一句话",
                      "expected_output": "一句话描述怎样算成功",
                      "expectations": ["可验证的断言", "另一条断言"]
                    }
                  ]
                }

                写的时候守三条规矩：

                1. **prompt 必须是用户的原话，绝对不能出现 Skill 的名字。**
                   错：「用 csv-to-xlsx 这个技能把文件转成 Excel」
                   对：「把这个 csv 弄成 Excel，金额那列要能直接求和」
                   因为要测的正是「模型会不会自己想到用它」。提了名字就等于把答案写进题目，
                   测出来永远是满分，而线上照样不触发。

                2. **场景要散开。** 3～5 条，覆盖不同说法：直白的、绕着说的、只说目的不说手段的。
                   用户不会照着 description 的措辞提问。

                3. **断言要有区分度。** 一条断言的标准是：Skill 真做对了才通过，做错了就不通过。
                   错：「输出里包含『金额』两个字」——随便编一段话也能过
                   对：「输出的 xlsx 里金额列是数值类型，不是文本」
                   弱断言通过比没测还糟，它会让人以为技能没问题。

                写完用一句话把用例讲给用户听（用大白话，别甩 JSON），问他们有没有要补的场景。

                ## 二、从 GitHub 导入现成的 Skill（用户想直接装一个 GitHub 上已有的技能）
                - 这种情况不要用 draft_skill，改用 skill_search / skill_install：
                  - 用户给了明确仓库（仓库地址或 GitHub URL）时，把它解析成 owner / repo / path（skill 所在子目录）/ ref（分支或 Tag，默认 main），直接调用 skill_install 安装。
                  - 用户只是描述想要什么（如「找个能处理 pdf 的技能」）时，先用 skill_search 按关键词搜索，把候选列给用户，由用户确认选哪个后再调用 skill_install，不要自行直接安装。
                - 安装成功后，用一句话告诉用户该 Skill 已导入、可在「技能」列表查看；不要再为它调用 draft_skill。

                ## 边界
                - 只在用户意图清晰的范围内设计，不臆造业务规则；拿不准就用一句话和用户确认。
                - 全程使用简体中文。""";
    }
}
