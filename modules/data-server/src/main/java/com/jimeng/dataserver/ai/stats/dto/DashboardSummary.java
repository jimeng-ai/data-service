package com.jimeng.dataserver.ai.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 看板计数快照：资产 / 组织 / 互动量。全部按当前租户聚合。 */
@Schema(description = "看板计数快照")
@Data
public class DashboardSummary {

    private Assets assets = new Assets();
    private Org org = new Org();
    private Engagement engagement = new Engagement();

    @Data
    public static class Assets {
        private Counts agents = new Counts();
        private SkillCounts skills = new SkillCounts();
        private KbCounts kb = new KbCounts();
    }

    @Data
    public static class Counts {
        private long total;
        private long published;
        private long draft;
    }

    @Data
    public static class SkillCounts {
        private long total;
        private long privateCount;
        private long shared;
        private long enabled;
        private long disabled;
    }

    @Data
    public static class KbCounts {
        private long kbTotal;
        private long docTotal;
        private long docIngesting;
        private long docSuccess;
        private long docFailed;
    }

    @Data
    public static class Org {
        private long members;
        private long membersEnabled;
        private long membersDisabled;
        private long activeMembers7d;
        private long roles;
    }

    @Data
    public static class Engagement {
        private long conversations;
        private long messages;
    }
}
