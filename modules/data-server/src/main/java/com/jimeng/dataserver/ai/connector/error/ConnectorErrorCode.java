package com.jimeng.dataserver.ai.connector.error;

/**
 * 统一错误语义。<b>所有连接器的失败必须归到这十类里的一类。</b>
 *
 * <p>（这句话本身曾经是错的：加了 {@link #GUARD_BLOCKED} 之后这里仍写着「九类」，
 * 而 {@code skills/connector/SKILL.md} 照抄了那个数字，于是教给模型的错误码清单里
 * 根本没有 guard_blocked 这一项——模型收到一个它没见过的值。改枚举时记得连同 SKILL.md 一起改。）
 *
 * <p>它比看上去重要，有两个理由：
 *
 * <p><b>一是给人看的。</b>没有统一分类，用户会看到一段底层异常堆栈，既不知道是自己填错了
 * 还是对方挂了，也不知道该找谁。
 *
 * <p><b>二是给模型看的。</b>模型拿到的错误也必须是这一套：面对 {@link #FORBIDDEN} 它该说明情况，
 * 面对 {@link #TIMEOUT} 它可以缩小范围重试，面对 {@link #RESULT_TOO_LARGE} 它该加限制条件——
 * 而面对一段堆栈，它只会瞎猜甚至编一个答案。
 *
 * <p><b>安全约束：</b>{@code modelHint} 是唯一允许进入模型上下文的文案。原始异常
 * （JDBC / HTTP 栈）常带 SQL 片段、主机名、连接参数，而工具结果会被完整写进
 * {@code ai_model_call_content} 落库——所以原始信息只准进日志，绝不准进这里。
 */
public enum ConnectorErrorCode {

    UNREACHABLE("连接器离线", "无法连接到目标系统，请联系管理员检查连接配置或网络"),
    AUTH_FAILED("凭据无效", "凭据无效或已过期，请重新填写"),
    FORBIDDEN("权限不足", "这个账号没有访问该对象的权限"),
    /**
     * ★ 与 {@link #FORBIDDEN} 的区别不是「谁拒绝的」这种分类学问题，而是<b>模型下一步该干什么</b>：
     * 这一档意味着语句/请求<b>根本没发到客户系统</b>，是平台自己的规则在本地拦下的，
     * <b>改写之后就能过</b>；而 FORBIDDEN 意味着改写没用，得去找人改授权或配置。
     *
     * <p><b>它是被真实事故逼出来的。</b>一条不带 WHERE 的 UPDATE 被护栏拦下时，原本回给模型的
     * 标题是「权限不足」、提示是「这个账号没有访问该对象的权限」。实跑时模型自己识破了并在回复里
     * 写道：「补充两点说明，免得你被那句『权限不足』误导：这不是账号权限的问题……不用去找管理员
     * 开权限，改写语句就行」。那一次它判对了，但我们给它的指向是<b>反的</b>——而「去要更宽的
     * 数据库权限」正是整套只读设计要防的那件事。不能指望每个模型每次都替我们纠错。
     */
    GUARD_BLOCKED("护栏拦截",
            "这条语句/请求被平台的安全护栏在发出前拦下了，目标系统没有收到它。"
                    + "具体原因见 detail —— 按它改写后重试即可，这不是权限问题，不需要申请权限"),
    NOT_FOUND("目标不存在", "找不到指定的表 / 接口 / 路径，请先用自描述工具确认它存在"),
    TIMEOUT("查询超时", "查询超时，可尝试缩小范围（加时间条件、减少返回列、降低 limit）后重试"),
    RATE_LIMITED("被限流", "请求过于频繁，请稍后再试"),
    /** 必须明说，不能静默截断。 */
    RESULT_TOO_LARGE("结果过大", "结果已被截断，只返回了前若干行；请缩小范围或加聚合后重试"),
    UPSTREAM_ERROR("对方报错", "目标系统返回了错误"),
    CONFIG_ERROR("配置错误", "连接配置有误，请检查后重新保存");

    private final String title;
    private final String modelHint;

    ConnectorErrorCode(String title, String modelHint) {
        this.title = title;
        this.modelHint = modelHint;
    }

    /** 给人看的短标题。 */
    public String title() {
        return title;
    }

    /** 给模型看的、<b>已脱敏</b>的行动建议。 */
    public String modelHint() {
        return modelHint;
    }
}
