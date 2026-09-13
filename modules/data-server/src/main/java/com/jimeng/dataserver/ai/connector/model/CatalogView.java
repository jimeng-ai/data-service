package com.jimeng.dataserver.ai.connector.model;

import java.util.List;

/**
 * 「列目录」的返回：一份<b>便宜的</b>概览。
 *
 * <p>刻意只给名字和一句注释，不给结构——大库有几百张表，结构全量注入放不下。
 * 细节由 Agent 用「看结构」按需取。
 *
 * @param objectKind 这批对象是什么（TABLE / ENDPOINT / DIRECTORY / TOPIC）
 * @param entries    条目
 * @param truncated  条目是否被截断（对象太多时）
 * @param total      实际总数（截断时用于告知）
 */
public record CatalogView(String objectKind, List<CatalogEntry> entries, boolean truncated, int total) {}
