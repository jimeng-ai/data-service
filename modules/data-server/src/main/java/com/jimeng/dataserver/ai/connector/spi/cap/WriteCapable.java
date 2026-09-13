package com.jimeng.dataserver.ai.connector.spi.cap;

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
}
