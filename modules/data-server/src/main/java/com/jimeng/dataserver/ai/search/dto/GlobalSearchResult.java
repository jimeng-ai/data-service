package com.jimeng.dataserver.ai.search.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 全局搜索（⌘K 命令面板）结果：按实体类型分组，每类只返回少量命中用于快速跳转。
 */
@Data
public class GlobalSearchResult {

    /** Agent 命中（按名称模糊）。 */
    private List<AgentHit> agents = new ArrayList<>();

    /** 文档命中（按标题模糊，限当前用户可见的知识库下）。 */
    private List<DocumentHit> documents = new ArrayList<>();

    /** 插件命中（按 name/description 模糊，复用 PLUGIN RBAC 可见性）。 */
    private List<PluginHit> plugins = new ArrayList<>();

    /** 技能命中（按 name/description 模糊，scope=TENANT 或 owner==当前用户）。 */
    private List<SkillHit> skills = new ArrayList<>();

    /** Trace 命中（按 trace_id / Agent 名 / 用户消息模糊，按人私有）。 */
    private List<TraceHit> traces = new ArrayList<>();

    @Data
    public static class AgentHit {
        private Long id;
        private String name;
        private String status;
    }

    @Data
    public static class DocumentHit {
        private Long id;
        private String title;
        private Long kbId;
        private String kbName;
        private String sourceType;
    }

    @Data
    public static class PluginHit {
        private Long id;
        private String name;
        private String description;
        private String status;
    }

    @Data
    public static class SkillHit {
        private Long id;
        private String name;
        private String description;
        private String skillType; // PROMPT / DOER
        private String status;     // DRAFT / ACTIVE / DISABLED
    }

    @Data
    public static class TraceHit {
        private String traceId;
        private String agentName;
        private String status;
        private Date createTime;
    }
}
