package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「上次结构刷新时，这条连接上哪几张表一行数据都没有」——给 {@code conn_catalog} / {@code conn_describe} 用（缺陷 B19）。
 *
 * <h3>它要挡住的那种错</h3>
 * POC 环境里 {@code D1_COMPANYCODE} 和 {@code EMM_PURCHASEORDERCONFIRM} 是 <b>0 行</b>。
 * 清账一旦按公司代码过滤或分组，join 到空维表就<b>安静地返回空集</b>：查询成功、没有报错，
 * 模型把「没有数据」当成业务答案报给用户。结构本身（列名、类型、注释）一个字都没错，
 * 错的是模型不知道这张表是空的。所以标记必须出现在它<b>选表和写 SQL 的那一刻</b>，
 * 而不是等它拿到 0 行之后自己去猜。
 *
 * <h3>★ 为什么这里可以读快照，而结构本身仍然读实时</h3>
 * {@link ConnectorSchemaService} 的类注释写得很清楚：结构走实时，不读快照，因为一层带有效期的缓存
 * 等于新开一个「悄悄给出过期结构」的失败面。这条纪律在这里<b>不适用也不被破坏</b>：
 * <ul>
 *   <li>实时返回的结构一个字节都没被替换——本类只<b>额外</b>加一个标记；</li>
 *   <li>「有没有行」压根不在 {@code describe} 的返回里，实时那条路根本拿不到它。
 *       要实时拿，就得在每次 {@code conn_describe} 上再往客户库发一条语句，
 *       而这是每一轮对话都会调的工具；</li>
 *   <li>过期的方向是<b>安全的那一头</b>：空表变非空，模型照常查得到数据，标记最多是一句多余的提醒
 *       （返回里带着观测时刻，模型能自己判断）；非空表变空，本轮没有标记，退回到今天的行为。
 *       两个方向都不会让模型编出一个它本来不会说的结论。</li>
 * </ul>
 *
 * <h3>★ 绝不整行读 detail_json</h3>
 * {@code detail_json} 是 longtext，一个 200 张表的库整行读一次就是几 MB 进堆
 * （{@code ConnectorOverviewService.loadObjects} 那段注释同一个理由）。而 {@code conn_catalog}
 * 是模型每次探索都会调的工具。所以这里：<b>投影只取 object_name / synced_at</b>，
 * 过滤下推给 MySQL 做——{@code detail_json LIKE '%"row_presence":"EMPTY"%'}。
 * 空表是少数，所以回到堆里的通常是零行到几行，而且一个字节的 longtext 都没读出来。
 * 代价是这条连接的 ≤200 行上的一次 LIKE 扫描，那是行数极少的一张表，没有索引可用也无所谓。
 *
 * <h3>为什么不注入 {@link ConnectorSchemaService}</h3>
 * {@code ConnectorToolExecutor} 的类注释钉着一条判据：注入进它的 bean <b>不能走回 ProviderRegistry</b>，
 * 否则启动期构造循环。而 {@code ConnectorSchemaService} 手上有 {@code ObjectProvider<ConnectorSemanticDeriveService>}，
 * 而后者要叫模型。{@code ObjectProvider} 今天确实断得开这个环，但那是<b>别人文件里的一个实现细节</b>，
 * 哪天它改成直接注入，环就在工具执行器上闭合、启动即失败，而失败信息指向的是这里。
 * 本类因此只依赖一个裸 {@code BaseMapper}，与 {@code ConnectorSemanticMapper} 同一档，是条叶子。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorRowPresenceService {

    /** 观测时刻给模型看的格式。与结构刷新那几句说明里的写法一致。 */
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final ConnectorSchemaMapper schemaMapper;

    /**
     * 这条连接上<b>确认为空</b>的对象：对象名（原样，按快照里存的写法）→ 观测时刻。
     *
     * <p><b>不在这个 map 里 = 有行，或者没探到。</b>两者都不构成「这张表是空的」的证据，
     * 所以调用方对它们一个字都不该说——沉默是对的，编一句「可能是空的」不是。
     *
     * <p>读失败（库抖了、列不存在）返回<b>空 map</b> 并记一条 WARN：少一个提醒，不影响 conn_catalog 出结果。
     * 让一个叠加增强去否决一次正常的工具调用是不划算的。
     */
    public Map<String, Date> emptyObjects(Long connectorId) {
        if (connectorId == null) {
            return Map.of();
        }
        try {
            List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                    .select(ConnectorSchema::getObjectName, ConnectorSchema::getSyncedAt)
                    .eq(ConnectorSchema::getConnectorId, connectorId)
                    .like(ConnectorSchema::getDetailJson, RowPresence.EMPTY_JSON_MARKER));
            if (rows == null || rows.isEmpty()) {
                return Map.of();
            }
            Map<String, Date> out = new LinkedHashMap<>();
            for (ConnectorSchema r : rows) {
                if (r == null || r.getObjectName() == null || r.getObjectName().isBlank()) {
                    continue;
                }
                out.put(r.getObjectName().trim(), r.getSyncedAt());
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("读取空表标记失败，本次不给模型加这个提醒 connectorId={}", connectorId, e);
            return Map.of();
        }
    }

    /**
     * 单个对象是不是空的。{@code conn_describe} 用。
     *
     * <p>名字<b>大小写敏感地</b>先试一次，不中再按大小写折叠比一次：客户库的
     * {@code lower_case_table_names} 决定了同名不同壳算不算一张表，而模型传进来的名字常常跟快照差一个壳。
     * 折叠只可能让标记多出现，不会让它落到别的表上——快照里两个只差大小写的对象名，在任何一个库上都指同一张表
     * 或者根本不共存。
     *
     * @return 观测时刻；{@code null} = 有行，或者没探到（两者调用方都不该说话）
     */
    public Date emptySince(Long connectorId, String objectName) {
        if (connectorId == null || objectName == null || objectName.isBlank()) {
            return null;
        }
        Map<String, Date> empties = emptyObjects(connectorId);
        String name = objectName.trim();
        if (empties.containsKey(name)) {
            return empties.get(name);
        }
        for (Map.Entry<String, Date> e : empties.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 观测时刻的文本。{@code null} 时给 {@code null}——宁可没有这一栏，也不要写一个编出来的时间。 */
    public static String formatObservedAt(Date at) {
        return at == null ? null : new SimpleDateFormat(TIME_PATTERN).format(at);
    }
}
