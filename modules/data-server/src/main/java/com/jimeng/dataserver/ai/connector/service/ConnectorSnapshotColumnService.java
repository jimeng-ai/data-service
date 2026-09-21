package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 「上一次结构刷新时，这条连接上有哪几张表、每张表有哪几列」——人在对话里写语义时<b>唯一</b>准用的那份结构。
 *
 * <h3>★ 为什么写语义必须读快照，而 conn_describe 仍然读实时</h3>
 * 人写下的 FIELD / JOIN 行要挂锚点（{@code anchor_hash}），而锚点的全部意义是
 * 「写入那一刻这一列长什么样」——刷新时拿<b>快照</b>重算一遍对不上就把这行标 STALE。
 * 所以算锚点用的列，必须和将来重算时用的列<b>同源</b>：都来自 {@code connector_schema}。
 * 拿 {@code conn_describe} 的实时结构算，会出现一种没人看得见的错：写入那一刻客户库已经改过、
 * 而平台的快照还是旧的，于是这一行一落库就"已经过期"，下一次刷新立刻把它标 STALE——
 * 人明明刚答完，答案却从来没有被注入过一次。
 *
 * <p>反过来说，<b>快照里没有的列一律拒写</b>（见 {@code ConnectorToolExecutor.doAnnotate}）：
 * 挂不上锚点的 FIELD / JOIN 行是一条永远不会过期的断言，结构怎么变它都照样注入。
 * 与 {@code SemanticRowAssembler} 对语义层生成 agent 的要求（{@code CODE_NAME_NOT_IN_SNAPSHOT}）是同一条纪律。
 *
 * <h3>为什么不注入 {@link ConnectorSchemaService}</h3>
 * 理由与 {@link ConnectorRowPresenceService} 逐字相同：{@code ConnectorToolExecutor} 的类注释钉着
 * 「注入进它的 bean 不能走回 {@code ProviderRegistry}」，而 {@code ConnectorSchemaService} 手上有
 * {@code ObjectProvider<ConnectorSemanticDeriveService>}，后者要叫模型。{@code ObjectProvider}
 * 今天断得开这个环，但那是别人文件里的实现细节。本类因此只依赖一个裸 {@code BaseMapper}，是条叶子。
 * 读的行与 {@code ConnectorSchemaService.currentRows} <b>同一条查询</b>（同一张表、同一个过滤、同一个字母序），
 * 解析也走同一个 {@link SemanticRowAssembler#parseFields}，两边不会分叉。
 *
 * <h3>为什么这里可以整行读 detail_json，而 {@link ConnectorRowPresenceService} 不行</h3>
 * 那个类服务的是 {@code conn_catalog}——模型<b>每一轮</b>都会调，一个 200 张表的库整行读一次就是几 MB 进堆。
 * 本类只服务 {@code conn_define_metric} / {@code conn_annotate} 这两个<b>写</b>工具：一次业务方确认最多读一次，
 * 而列名与列指纹本来就只在 {@code detail_json} 里，投影掉它就什么都不剩了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSnapshotColumnService {

    private final ConnectorSchemaMapper schemaMapper;

    /**
     * 这条连接的快照：表名（快照里存的原写法）→ 列名（同样是原写法）→ {@link FieldDetail}。
     *
     * <p><b>读不到就返回空 map</b>，绝不抛。调用方（写工具）看到空 map 必须<b>拒写并说明</b>，
     * 不能当成「这张表不存在」——两句话对模型的指示完全不同：
     * 前者是「平台还没有这条连接的结构，先刷新」，后者是「你把表名写错了」。
     */
    public Map<String, Map<String, FieldDetail>> columnsOf(Long connectorId) {
        if (connectorId == null) {
            return Map.of();
        }
        try {
            List<ConnectorSchema> rows = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                    .eq(ConnectorSchema::getConnectorId, connectorId)
                    .orderByAsc(ConnectorSchema::getObjectName));
            if (rows == null || rows.isEmpty()) {
                return Map.of();
            }
            return SemanticRowAssembler.parseFields(rows);
        } catch (RuntimeException e) {
            log.warn("读取结构快照失败，本次不允许写入带锚点的语义 connectorId={}", connectorId, e);
            return Map.of();
        }
    }
}
