package com.jimeng.dataserver.ai.connector.service;

import java.util.Map;

/**
 * 「这张表里有没有行」在 {@code connector_schema.detail_json} 里的<b>落库契约</b>（缺陷 B19）。
 *
 * <h3>为什么单独一个类，而不是写方 / 读方各写一份字面量</h3>
 * 这件事的写方（{@link ConnectorSchemaService} 刷新时探测后盖章）与读方
 * （{@link ConnectorRowPresenceService} 按标记捞出空表）隔着一张表、两个 Spring bean。
 * 两边各写一份 {@code "row_presence"}，改名时只改一边的表现是<b>读方永远捞不到任何空表</b>——
 * 不报错、不报警，只是那个标记从此在模型面前消失。契约就那么几个字节，放在一处、由两边共同引用。
 *
 * <h3>★ 只盖「确认是空的」和「确认有行」，绝不盖「不知道」</h3>
 * 探测失败（没权限 / 超时 / 表刚被删 / 预算用完 / 连接器答不了）一律<b>不写这个键</b>。
 * 键不在 = 不知道。把「没探到」写成 {@link #EMPTY}，等于凭空告诉模型「这张表里一行都没有」，
 * 而模型会照着这句话回答用户——比什么都不说更糟。
 *
 * <h3>★ 这个键为什么可以放心塞进 detail_json</h3>
 * <ul>
 *   <li><b>不进结构指纹。</b>{@link ConnectorSchemaService#structureFingerprint} 只拼对象名、类型和
 *       每列的 {@code name:type:nullable:comment}，本键不在里面。所以一张表从空变成非空
 *       <b>不会</b>被报成结构漂移，挂在它上面的语义一条都不会被标 STALE。行数是波动值，
 *       和目录注释里那句「约 N 行」同一条纪律。</li>
 *   <li><b>不加数据库列。</b>{@code connector_schema} 的列是共享的、要走迁移的；
 *       而这是一条随快照一起整批删了重插的附注，detail_json 正是它该待的地方。</li>
 *   <li><b>观测时刻不另存。</b>就是那一行的 {@code synced_at}（= 本次拉取开始的时刻），
 *       同一次刷新的每一行都是它。再存一遍只会多出一处能和 {@code synced_at} 对不上的地方。</li>
 * </ul>
 */
public final class RowPresence {

    private RowPresence() {
    }

    /** detail_json 顶层的键名。与 {@code name / type / comment / fields / extra} 并排。 */
    public static final String KEY = "row_presence";

    /** 探到了、<b>确认</b>一行都没有。 */
    public static final String EMPTY = "EMPTY";

    /** 探到了、至少有一行。 */
    public static final String NON_EMPTY = "NON_EMPTY";

    /**
     * 读方按它做 SQL 层的 {@code LIKE} 预筛，避免为了一个布尔把整列 longtext 拉回堆里
     * （理由见 {@link ConnectorRowPresenceService}）。
     *
     * <h3>为什么这个子串不会误命中</h3>
     * <ul>
     *   <li>客户的表注释 / 列注释里就算原样写着这几个字，它们在 detail_json 里的引号是<b>转义</b>过的
     *       （{@code \"row_presence\":\"EMPTY\"}），带着反斜杠，配不上这个模式；</li>
     *   <li>{@link #NON_EMPTY} 序列化出来是 {@code :"NON_EMPTY"}，冒号后面第一个字符是 {@code N} 不是 {@code E}，
     *       同样配不上——两个状态不会互相串味；</li>
     *   <li>{@code row_presence} 里的下划线在 LIKE 里是「任意单字符」通配符，所以模式实际也能匹配
     *       {@code "rowXpresence":"EMPTY"}。无害：那要求 JSON 里真有一个这样命名的顶层键，而顶层键由我们自己写。</li>
     * </ul>
     * 依赖 Jackson 的<b>紧凑</b>输出（{@code CommonUtil.getObjectMapper()} 是裸 {@code new ObjectMapper()}，
     * 没开缩进）。哪天有人给那个全局 mapper 开了 {@code INDENT_OUTPUT}，这个预筛会一个都捞不到——
     * 所以 {@code RowPresenceTest} 拿真实序列化结果钉住了这个子串，那时会先红在测试里。
     */
    public static final String EMPTY_JSON_MARKER = "\"" + KEY + "\":\"" + EMPTY + "\"";

    /**
     * 把探测结论盖进一份 detail map。{@code nonEmpty} 为 {@code null}（不知道）时<b>什么都不写</b>。
     *
     * @param detailMap 由 {@code ConnectorSchemaService.toDetailMap} 产出的可变 map
     * @param nonEmpty  {@code TRUE} 至少一行 / {@code FALSE} 确认空 / {@code null} 不知道
     */
    public static void stamp(Map<String, Object> detailMap, Boolean nonEmpty) {
        if (detailMap == null || nonEmpty == null) {
            return;
        }
        detailMap.put(KEY, nonEmpty ? NON_EMPTY : EMPTY);
    }

    /** 一份 detail map 里盖的是不是「确认为空」。解析不出来、键不在，一律 false（= 不知道，不是空）。 */
    public static boolean isEmptyStamped(Map<String, Object> detailMap) {
        return detailMap != null && EMPTY.equals(detailMap.get(KEY));
    }
}
