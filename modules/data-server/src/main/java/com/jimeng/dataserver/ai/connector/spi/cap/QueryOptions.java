package com.jimeng.dataserver.ai.connector.spi.cap;

/**
 * 查询护栏参数。<b>由框架强制下发，实现方无法绕过</b>——我们是在客户的生产系统上跑模型写的语句，
 * 这是事故防线而不是可调优项。
 *
 * @param maxRows      行数上限，超出即截断并明确标记
 * @param maxBytes     结果字节上限
 * @param timeoutSec   语句超时
 */
public record QueryOptions(int maxRows, int maxBytes, int timeoutSec) {}
