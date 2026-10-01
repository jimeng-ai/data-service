package com.jimeng.dataserver.ai.connector.graph;

import lombok.Value;

import java.util.List;

/**
 * 数据星图的出网对象（设计文档 §7.2）。
 *
 * <p>项目实际用的 jackson-databind 是 2.11，不认 record，所以一律用 Lombok {@code @Value}。
 * 这里只放给人看的东西：语义层的原文说明 / 依据 / 置信度 / 判别值、业务视图的输入指纹与模型信息、租户、凭据<b>永不出网</b>。
 */
public final class DataGraphViews {

    private DataGraphViews() {
    }

    /**
     * {@code /systems/{connectorId}}：一个库的全部对象卡片 + 通过判定的关系（不含自关联）。
     * {@code semanticStatus}、{@code viewStatus} 只给前端判断空状态和提示，不上屏。
     *
     * <p>{@code truncated}：结构快照达到 200 个对象的上限，按重要性排后面的表没进来。
     * {@code viewStatus}：业务文字的整理状态——{@code RUNNING}（补全链正在跑，或者从没跑完过、但会来跑）/
     * {@code READY} / {@code FAILED}（上次结果）/ {@code null}（不会有人来跑）。
     */
    @Value
    public static class SystemGraph {
        String connectorId;
        String name;
        String displayName;
        String semanticStatus;
        boolean truncated;
        String viewStatus;
        List<TableCard> tables;
        List<Relation> relations;
    }

    @Value
    public static class ColumnRef {
        String name;
        String comment;
    }

    /** 自关联（上下级之类）。{@code role} 是业务视图起的角色名，缺了退回干净的列注释，再缺为 {@code null}。 */
    @Value
    public static class SelfReference {
        String fromColumn;
        String toColumn;
        String tier;
        String confirmedBy;
        String role;
    }

    /**
     * 一个业务对象的卡片。
     *
     * <p>{@code displayName}：业务视图的业务名优先，缺了退回像名称的表注释，再缺为 {@code null}（前端显示表名）；
     * {@code nameSource} 说它来自哪一档：{@code BUSINESS_VIEW} / {@code COMMENT} / {@code PHYSICAL}。
     * {@code summary}、{@code domain} 缺了为 {@code null}（前端显示「未分类」）。
     * {@code keyColumns}、{@code relationColumns}、{@code comment} 只给折叠的技术信息区用。
     */
    @Value
    public static class TableCard {
        String name;
        String displayName;
        String nameSource;
        String summary;
        String domain;
        String comment;
        String objectType;
        boolean related;
        List<SelfReference> selfReferences;
        List<ColumnRef> keyColumns;
        List<ColumnRef> relationColumns;
        int fieldCount;
    }

    /** 一条关系。{@code role}：业务视图的角色名，缺了退回干净的起点列注释，再缺为 {@code null}。 */
    @Value
    public static class Relation {
        String id;
        String fromTable;
        String fromColumn;
        String toTable;
        String toColumn;
        String cardinality;
        String tier;
        String confirmedBy;
        String role;
        String discriminatorColumn;
    }

    @Value
    public static class Field {
        String name;
        String type;
        boolean nullable;
        String comment;
        String key;
        boolean inRelation;
    }

    @Value
    public static class TableDetail {
        String name;
        String displayName;
        String nameSource;
        String summary;
        String domain;
        String comment;
        String objectType;
        List<SelfReference> selfReferences;
        List<Field> fields;
        List<Relation> relations;
    }
}
