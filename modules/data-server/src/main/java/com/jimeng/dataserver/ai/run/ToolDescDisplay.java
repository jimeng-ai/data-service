package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;

/**
 * 工具描述的「给人看」版本：只取首句。
 *
 * <h3>为什么需要它：一个字段两个读者，方向相反</h3>
 * 工具定义里的 {@code description} 是<b>写给模型的</b>——它要的是负向约束、失败模式、决策程序，
 * 越显式越好。connector 那 7 条描述合计 <b>5724 字</b>，{@code conn_describe} 一条就 2457 字，
 * 其中真正说清「这工具在干什么」的只有各自第一句、合计约 250 字。
 *
 * <p>而这个字段会随 SSE 的 {@code progress} 事件原样下发给前端、并折叠进
 * {@code chat_message.segments} 落库。两个后果：
 *
 * <ul>
 *   <li><b>写给模型的话被渲染给了客户企业管理员</b>：「请如实告知用户，不要编造数据」
 *       「绝不能说『已完成』『已修改』」「这是在改<b>客户的</b>生产数据」——最后一句里的
 *       「客户」正是读到它的那个人。</li>
 *   <li><b>每条助手消息 × 每次工具调用多存 0.2–2.4KB 模型指令</b>，而同一条折叠路径上
 *       「输出过大就只存状态不进 segments」是既定原则（见 {@code RunSegmentAssembler#foldToolResult}）。
 *       同一个文件里两套标准。</li>
 * </ul>
 *
 * <h3>为什么截在这里，而不是等前端截</h3>
 * 前端 {@code stepKind.ts#shortenDesc} 确实也截，但那是<b>渲染期</b>止血：全量原文仍然过了网络、
 * 仍然落了库，展开详情仍能看到。真正的边界在<b>发送前</b>——没发出去的东西不需要被信任。
 *
 * <h3>只按「。」和换行切</h3>
 * 本仓库的描述里「【】」是<b>句中强调</b>（{@code 执行一条【会改变数据】的语句}），不是段落标记，
 * 拿它当分隔符会把句子腰斩。按「。」+ 换行切，现有 17 个工具都能得到 11~47 字的完整短句。
 * 切分口径与前端 {@code shortenDesc} 保持一致，两边对同一个 desc 得到同一个首句。
 */
public final class ToolDescDisplay {

    /**
     * 首句的硬上限。前端标题上限是 48 字，这里放宽到 {@value}：给前端将来调整留余量，
     * 同时把 2457 字那种彻底挡在外面。首句本身也可能很长，不设上限等于把边界
     * 交给写描述的人，而那正是当初出问题的方式。
     */
    static final int MAX_LEN = 120;

    private ToolDescDisplay() {
    }

    /**
     * 取描述的首句，超长则截断。
     *
     * @param desc 原始工具描述；null / 空白原样返回（调用方据此决定是否下发该字段）
     */
    public static String firstSentence(String desc) {
        if (StrUtil.isBlank(desc)) {
            return desc;
        }
        String trimmed = desc.trim();
        int cut = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '。' || ch == '\n' || ch == '\r') {
                cut = i;
                break;
            }
        }
        String first = trimmed.substring(0, cut).trim();
        // 首句切出来是空的（desc 以句号/换行开头）时退回整段，再交给下面的长度闸。
        String base = first.isEmpty() ? trimmed : first;
        return base.length() <= MAX_LEN ? base : base.substring(0, MAX_LEN);
    }
}
