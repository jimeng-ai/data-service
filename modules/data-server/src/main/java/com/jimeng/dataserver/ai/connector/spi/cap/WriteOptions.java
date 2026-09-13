package com.jimeng.dataserver.ai.connector.spi.cap;

/**
 * 写操作的护栏参数。<b>由框架强制下发，实现方拿不到绕过它们的口子。</b>
 *
 * @param maxAffectedRows 影响行数上限。<b>超了必须回滚整条语句</b>——
 *                        这是防「一条 UPDATE 改掉整张表」的最后一道闸，而模型写漏 WHERE
 *                        的概率并不低。注意它只能在<b>执行之后、提交之前</b>判定：
 *                        事前估算不可靠（估算和实际不一致时，不一致的那次正是要防的那次）。
 * @param timeoutSec      语句超时
 */
public record WriteOptions(int maxAffectedRows, int timeoutSec) {}
