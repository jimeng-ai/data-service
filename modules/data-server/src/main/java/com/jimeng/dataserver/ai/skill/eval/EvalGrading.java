package com.jimeng.dataserver.ai.skill.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 评委输出，对齐 skill-creator 的 grading.json（取其中与本平台相关的子集：
 * 去掉 timing / execution_metrics —— 那些在它那边由本地脚本采集，这里无对应来源，
 * 造一个空壳只会让人以为有数据）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class EvalGrading {

    private List<Expectation> expectations;
    private Summary summary;
    private List<Claim> claims;
    private EvalFeedback evalFeedback;

    /** RECALL 模式专用：模型这一轮到底有没有动用被测 skill。 */
    private Boolean skillInvoked;
    private String skillInvokedEvidence;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Expectation {
        private String text;
        private Boolean passed;
        private String evidence;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Summary {
        private Integer passed;
        private Integer failed;
        private Integer total;
        private Double passRate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Claim {
        private String claim;
        /** factual | process | quality */
        private String type;
        private Boolean verified;
        private String evidence;
    }

    /** 评委的第二份工作：批评测试本身写得好不好。弱断言通过比不测还糟——它制造虚假信心。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class EvalFeedback {
        private List<Suggestion> suggestions;
        private String overall;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class Suggestion {
        private String assertion;
        private String reason;
    }
}
