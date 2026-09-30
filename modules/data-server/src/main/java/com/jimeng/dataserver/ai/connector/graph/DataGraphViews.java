package com.jimeng.dataserver.ai.connector.graph;

import lombok.Value;

import java.util.List;

/**
 * 数据星图的出网对象（设计文档 §5.1）。
 *
 * <p>项目实际用的 jackson-databind 是 2.11，不认 record，所以一律用 Lombok {@code @Value}。
 * 这里只放客户能看的东西：语义层的 gloss / 依据 / 置信度 / 判别值、租户、凭据<b>永不出网</b>。
 */
public final class DataGraphViews {

    private DataGraphViews() {
    }

    /** {@code /systems} 的一项。{@code semanticStatus} 只给前端判断空状态，不上屏。 */
    @Value
    public static class SystemSummary {
        String connectorId;
        String name;
        String displayName;
        String kind;
        String status;
        String semanticStatus;
        int tableCount;
    }

    /** {@code /systems/{connectorId}}：全部表卡片 + 通过判定的关系（不含自关联）。 */
    @Value
    public static class SystemGraph {
        String connectorId;
        String name;
        String displayName;
        String semanticStatus;
        List<TableCard> tables;
        List<Relation> relations;
    }

    @Value
    public static class ColumnRef {
        String name;
        String comment;
    }

    @Value
    public static class SelfReference {
        String fromColumn;
        String toColumn;
    }

    @Value
    public static class TableCard {
        String name;
        String displayName;
        String comment;
        String objectType;
        boolean related;
        List<SelfReference> selfReferences;
        List<ColumnRef> keyColumns;
        List<ColumnRef> relationColumns;
        int fieldCount;
    }

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
        String label;
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
        String comment;
        String objectType;
        List<SelfReference> selfReferences;
        List<Field> fields;
        List<Relation> relations;
    }
}
