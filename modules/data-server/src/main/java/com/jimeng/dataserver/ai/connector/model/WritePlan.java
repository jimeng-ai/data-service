package com.jimeng.dataserver.ai.connector.model;

/**
 * 一条写语句「解析完但还没执行」的样子。
 *
 * <p><b>为什么需要这个中间态：</b>写策略是「写需审批」时，平台要把语句<b>放进队列而不是执行</b>，
 * 但队列里的记录必须写清楚「这条语句要对哪张表做什么」——否则审批的人面对的是一段裸 SQL，
 * 而让人在看不懂的东西上点「批准」，审批这一环就只剩形式。
 *
 * <p>解析的结论只产生<b>一次</b>：这里拿到的 {@code effectiveStatement} 就是将来批准后真正执行的那条。
 * 如果改成「入队存原文、批准时再解析一遍」，两次解析结论万一不一致（护栏升级、方言差异），
 * 人看到的和实际执行的就对不上了——而那恰恰是审批唯一要防的事。
 *
 * @param effectiveStatement 护栏校验后、平台将实际执行的语句
 * @param operation          INSERT / UPDATE / DELETE
 * @param targetTable        目标表名
 */
public record WritePlan(String effectiveStatement, String operation, String targetTable) {
}
