package com.jimeng.dataserver.ai.connector.model;

import java.util.List;

/**
 * 「列目录」的返回：一份<b>便宜的</b>概览。
 *
 * <p>刻意只给名字和一句注释，不给结构——大库有几百张表，结构全量注入放不下。
 * 细节由 Agent 用「看结构」按需取。
 *
 * @param objectKind 这批对象是什么（TABLE / ENDPOINT / DIRECTORY / TOPIC）
 * @param entries    条目。<b>顺序有含义</b>：它既是给模型看的顺序，也是上游截断时
 *                   「先保留谁」的顺序（见 {@code ConnectorSchemaService.MAX_OBJECTS}）。
 *                   顺序依据由实现决定，写在 {@code ordering} 里。
 * @param truncated  条目是否被截断（对象太多时）
 * @param total      实际总数（截断时用于告知）
 * @param ordering   {@code entries} 按什么排的，一句人话，例如「按估算行数的数量级降序」。
 *                   {@code null} = 这个连接器没有承诺任何顺序。
 *                   <p><b>为什么要把它带出来：</b>截断消息必须说清「被砍掉的是哪一批」，
 *                   否则「500 个里只覆盖了 200 个」会被各人按各人的直觉脑补。
 *                   而排序是<b>实现</b>做的——只有它知道自己按什么排，上层替它写死一句
 *                   「按行数」，换一个实现就变成一句看起来很具体的假话。
 */
public record CatalogView(String objectKind, List<CatalogEntry> entries, boolean truncated, int total,
                          String ordering) {}
