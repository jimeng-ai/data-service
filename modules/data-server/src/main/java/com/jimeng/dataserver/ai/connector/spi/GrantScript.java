package com.jimeng.dataserver.ai.connector.spi;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一段可以直接复制执行的授权脚本，外加给人看的提醒。
 *
 * <p><b>刻意不是 record。</b>本仓库实际生效的 jackson-databind 是 2.11.1
 * （由 logstash-logback-encoder:7.4 传递带入），record 序列化要 2.12+，
 * 直接回传会报「No serializer found … no properties discovered」——
 * 而这个类是要跨 HTTP 出去给管理台渲染的。同样的坑见 {@code ConnectorSchemaView}。
 *
 * <p>{@link #notes} 不是装饰。脚本本身只能表达「授了什么」，表达不了
 * 「为什么不该改成 {@code *.*}」「{@code @'%'} 意味着什么」「这个账号能写意味着什么」——
 * 而这三件事恰恰是客户 IT 最需要在执行<b>之前</b>看到的。
 */
@Data
@Builder
public class GrantScript {

    /** 完整脚本，含换行，可整段复制执行。 */
    private String sql;

    /** 给人看的提醒，<b>每条一句话</b>，按重要性排序。前端逐条列出，不要折叠。 */
    private List<String> notes;
}
