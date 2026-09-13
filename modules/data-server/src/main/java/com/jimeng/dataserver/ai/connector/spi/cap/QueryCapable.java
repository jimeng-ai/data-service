package com.jimeng.dataserver.ai.connector.spi.cap;

import com.jimeng.dataserver.ai.connector.model.QueryResult;

/**
 * 能查：执行 Agent 自己写的查询语句。
 *
 * <p><b>生成查询的是 Agent，不是平台。</b>平台不看懂那条语句要做什么，只判断它安不安全、
 * 会不会拖垮客户的库。这条分工让平台不需要理解任何一种查询语言的语义——加一种新数据库时，
 * 护栏要适配，但「怎么问」的智力不用重写。
 *
 * <p>实现方<b>必须</b>自己兑现的两件事（框架代劳不了，因为它们是方言相关的）：
 * 只读静态校验、强制结果上限。框架负责的是它之外的一切：授权、限流、并发、超时、审计、错误归一。
 */
public interface QueryCapable {

    /**
     * @param statement 模型写的原始查询语句
     * @param options   护栏参数（行数上限、字节上限、超时）
     * @return 结果，含<b>平台实际执行的语句</b>与截断标记
     * @throws com.jimeng.dataserver.ai.connector.error.ConnectorException 已归一的失败
     */
    QueryResult query(String statement, QueryOptions options);
}
