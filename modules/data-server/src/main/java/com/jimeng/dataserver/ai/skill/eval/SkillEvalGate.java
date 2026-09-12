package com.jimeng.dataserver.ai.skill.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.SkillEvalRun;
import com.jimeng.persistence.mapper.SkillEvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 发布门槛：skill 发布前检查它有没有通过评测。
 *
 * <h3>三态开关，默认 WARN 而不是 ENFORCE 或 OFF</h3>
 * <ul>
 *   <li>{@code OFF} —— 完全不查。</li>
 *   <li>{@code WARN}（默认）—— 查，把结论<b>放进接口返回</b>，但放行。</li>
 *   <li>{@code ENFORCE} —— 不达标直接拒绝发布。</li>
 * </ul>
 * 默认取 WARN 是权衡过的：默认 ENFORCE 会在上线那一刻把所有人的发布路径堵死
 * （此时还没有任何 skill 有用例）；默认 OFF 则是又造一个"配了才生效、不配就静默无用"的开关——
 * 这套系统已经栽过太多次。WARN 从第一天就产出信号、不挡任何人，等覆盖率上来再拧到 ENFORCE。
 *
 * <h3>为什么只认 RECALL 模式的结果</h3>
 * 门槛存在的目的就是拦住"不工作的 skill"，而最主要的失效方式是<b>压根不被触发</b>。
 * CAPABILITY 模式在提示词里直接点了 skill 名字，它能证明"用了之后做得对"，
 * 但恰恰证明不了"会被用到"。只认 CAPABILITY 的话，门槛会放行它本该拦住的那种缺陷。
 *
 * <h3>陈旧结果</h3>
 * 评测记录上带 {@code content_hash}。发布时重算当前内容的指纹，对不上就当没测过——
 * 否则"跑出 0.9 → 改坏 → 发布"会被旧成绩放行，而且用户甚至不是故意的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillEvalGate {

    public static final String OFF = "OFF";
    public static final String WARN = "WARN";
    public static final String ENFORCE = "ENFORCE";

    private final SkillEvalRunMapper evalRunMapper;

    /** OFF | WARN | ENFORCE。见类注释：默认 WARN。 */
    @Value("${skill.eval.gate:WARN}")
    private String gateMode;

    /** 通过率下限。1.0 表示必须全过。 */
    @Value("${skill.eval.min-pass-rate:1.0}")
    private double minPassRate;

    /** 门槛判定结果。{@code warning} 非空时前端应当显示出来——只写日志等于没提示。 */
    public record Verdict(boolean allowed, String warning, BigDecimal passRate, Long evalRunId) {
    }

    /**
     * @param conversationId 构建器会话 id（草稿评测按它关联）
     * @param contentHash    当前草稿内容的指纹，由 {@link #contentHash} 计算
     */
    public Verdict check(Long conversationId, String contentHash) {
        if (OFF.equalsIgnoreCase(gateMode)) {
            return new Verdict(true, null, null, null);
        }
        SkillEvalRun latest = latestRecallRun(conversationId);

        String problem = null;
        if (latest == null) {
            problem = "尚未做过召回评测（RECALL）。该 skill 可能因为 description 写得不够明确而从不被触发——"
                    + "这种失效不会报错，只会表现为「技能好像没生效」。建议先跑一轮评测。";
        } else if (contentHash != null && latest.getContentHash() != null
                && !contentHash.equals(latest.getContentHash())) {
            problem = "最近一次评测（通过率 " + fmt(latest.getPassRate()) + "）测的不是当前内容——"
                    + "评测之后 skill 又改过了。请对改动后的版本重新跑一轮。";
        } else if (latest.getPassRate() == null
                || latest.getPassRate().doubleValue() < minPassRate) {
            problem = "召回评测通过率 " + fmt(latest.getPassRate()) + "，低于要求的 " + minPassRate
                    + "。未通过的用例说明模型在那些说法下想不到用这个 skill，通常要改 description。";
        }

        if (problem == null) {
            return new Verdict(true, null, latest.getPassRate(), latest.getId());
        }
        BigDecimal rate = latest == null ? null : latest.getPassRate();
        Long runId = latest == null ? null : latest.getId();
        if (ENFORCE.equalsIgnoreCase(gateMode)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "发布被拦下：" + problem);
        }
        log.info("skill 发布门槛（WARN）conversationId={} 未达标：{}", conversationId, problem);
        return new Verdict(true, problem, rate, runId);
    }

    private SkillEvalRun latestRecallRun(Long conversationId) {
        if (conversationId == null) return null;
        return evalRunMapper.selectOne(new LambdaQueryWrapper<SkillEvalRun>()
                .eq(SkillEvalRun::getConversationId, conversationId)
                .eq(SkillEvalRun::getMode, SkillEvalService.MODE_RECALL)
                .eq(SkillEvalRun::getStatus, "COMPLETED")
                .orderByDesc(SkillEvalRun::getCreateTime)
                .last("limit 1"));
    }

    private String fmt(BigDecimal r) {
        return r == null ? "未知" : String.valueOf(r.doubleValue());
    }

    /**
     * 内容指纹：SKILL.md 正文 + 全部文件。文件按路径排序后参与，保证与 Map 迭代顺序无关——
     * 否则同一份内容会算出不同的 hash，门槛就会莫名其妙地说"内容变了"。
     */
    public static String contentHash(String body, Map<String, String> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            if (files != null) {
                for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
                    md.update((byte) 0);            // 分隔符：防止 ("ab","c") 与 ("a","bc") 撞 hash
                    md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    md.update((e.getValue() == null ? "" : e.getValue()).getBytes(StandardCharsets.UTF_8));
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;    // 算不出就返回 null，调用方会当作"无法判定陈旧"，不因此拦人
        }
    }
}
