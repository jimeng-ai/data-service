package com.jimeng.dataserver.ai.connector.model;

/**
 * 目录里的一项：一张表 / 一个接口 / 一个目录 / 一个主题。
 *
 * @param name    标识（表名、接口路径、桶内前缀、主题名）
 * @param type    子类型（TABLE / VIEW / ENDPOINT / DIRECTORY / TOPIC…）
 * @param comment 说明。数据库来自表注释；HTTP 来自接口 summary
 */
public record CatalogEntry(String name, String type, String comment) {}
