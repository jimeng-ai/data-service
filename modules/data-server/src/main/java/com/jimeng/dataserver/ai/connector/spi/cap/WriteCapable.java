package com.jimeng.dataserver.ai.connector.spi.cap;

import com.jimeng.dataserver.ai.connector.model.WritePlan;
import com.jimeng.dataserver.ai.connector.model.WriteResult;

/**
 * 能写：在客户系统上执行一次<b>会改变数据</b>的操作。
 *
 * <p><b>刻意与 {@link QueryCapable} 分开，而不是把 query 放宽。</b>三个理由：
 * <ul>
 *   <li>放宽 query 意味着{@code ReadOnlySqlGuard} 要长出「有时允许写」的分支，
 *       而那道护栏的价值恰恰在于它<b>没有例外</b>。</li>
 *   <li>工具层因此能分成 {@code conn_query} 和 {@code conn_execute} 两个名字——
 *       模型必须<b>明确选择</b>「我要做一次写操作」，而不是在一个通用工具里不小心传了条 UPDATE。</li>
 *   <li>审计、限流、审批都按能力分派，分开之后不需要再去解析语句判断意图。</li>
 * </ul>
 *
 * <p>实现方必须自己兑现的两件事（方言相关，框架代劳不了）：
 * <b>只允许单条 DML</b>、<b>UPDATE/DELETE 必须带 WHERE</b>。
 * 框架负责的是它之外的一切：写策略闸、审批、影响行数上限与回滚、超时、审计。
 */
public interface WriteCapable {

    /**
     * @param statement 模型写的原始语句
     * @param options   护栏参数
     * @return 结果，含<b>平台实际执行的语句</b>与影响行数
     * @throws com.jimeng.dataserver.ai.connector.error.ConnectorException 已归一的失败
     */
    WriteResult execute(String statement, WriteOptions options);

    /**
     * 只跑护栏、<b>不执行</b>，回答「这条语句要对哪张表做什么」。
     *
     * <p>写策略是「写需审批」时走这条路：平台把结论连同语句一起入队，等人点批准之后
     * 才会调 {@link #execute}。两条路共用同一道护栏，所以<b>不带 WHERE 的 UPDATE 在入队这一步就会被拒</b>——
     * 一条注定执行失败的语句不应该先去占用一个人的审批时间。
     *
     * <p>实现方不得在这里借连接：它可能在没有任何网络可用的情况下被调用，
     * 而且「解析一条语句」本来也不需要目标系统参与。
     *
     * @throws com.jimeng.dataserver.ai.connector.error.ConnectorException 护栏拒绝时抛出，与 execute 同一套错误
     */
    WritePlan plan(String statement);

    /**
     * 估算这条语句<b>会改动多少行</b>——只在「写需审批」入队时调用，给审批的人看。
     *
     * <h3>为什么必须有它</h3>
     * 审批页原本只展示语句原文。但**看语句判不了范围**：
     * {@code UPDATE orders SET status='X' WHERE created_at < '2026-08-01'} 这条语句，
     * 审批的人无从知道它命中 3 行还是 30 万行，而这正是他唯一需要判断的事。
     * 影响行数原本只在<b>执行之后</b>才回填，那时候批已经批完了。
     *
     * <h3>它不是承诺</h3>
     * 估算发生在<b>提交时</b>，执行发生在<b>批准时</b>，中间隔着人的思考时间，数据会变。
     * 所以返回值只能当参考，界面上必须标明「提交时预估」。真正的防线仍是执行时的
     * {@code maxAffectedRows} 整条回滚。
     *
     * <p>估不出来返回 {@code null}（例如 INSERT ... SELECT、或方言不支持），
     * <b>不要抛异常</b>——估不出来不该阻止一条写请求进入审批队列。
     */
    default Integer estimateAffectedRows(String statement) {
        return null;
    }
}
