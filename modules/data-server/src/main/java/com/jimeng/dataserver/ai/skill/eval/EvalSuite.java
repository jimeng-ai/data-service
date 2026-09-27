package com.jimeng.dataserver.ai.skill.eval;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * {@code evals/evals.json} 的数据模型。<b>字段逐一对齐 Anthropic skill-creator 的
 * references/schemas.md，刻意不改名</b>——这样用官方那套工具链在本地写出来的评测，
 * 可以原样放进 skill 包上传，不需要任何转换。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EvalSuite {

    /** 与 skill frontmatter 的 name 一致 */
    // skill-creator 的 evals.json 用下划线（skill_name / expected_output），这里原先只认驼峰且 ignoreUnknown，
    // 于是构建器写出来的 expected_output 被静默丢掉、评委永远拿不到「期望结果」。两种写法都认。
    @JsonAlias("skill_name")
    private String skillName;

    private List<EvalCase> evals;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class EvalCase {
        /** 用例内唯一整数 id */
        private Integer id;

        /** 用户原话。RECALL 模式下【原样】作为提示词——不能在这里提 skill 名字。 */
        private String prompt;

        /** 人读的成功描述 */
        @JsonAlias("expected_output")
        private String expectedOutput;

        /** 可选输入文件（相对 skill 根）。当前实现尚未支持，忽略。 */
        private List<String> files;

        /** 可验证的断言列表，交给评委逐条判 */
        private List<String> expectations;
    }
}
