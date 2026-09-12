-- Skill 评测运行记录。
--
-- 为什么只有一张表：测试用例本身【不】入库，它们躺在 skill 包里的 evals/evals.json
-- （沿用 Anthropic skill-creator 的布局与 schema），跟着 skill 一起分发与版本化。
-- 库里只需要记"哪次跑的、跑成什么样"。
--
-- 评测解决的是一个【静默】故障：skill 的 description 写不好，模型就永远想不起来用它，
-- 请求 200、回复正常、只是那套规矩没生效。所以 mode=RECALL 那一类是重点——
-- 它的提示词【不提 skill 名字】，只给用户原话，看模型会不会自己想到。

CREATE TABLE IF NOT EXISTS `skill_eval_run` (
  `id`              bigint       NOT NULL COMMENT '主键',
  `tenant_id`       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `skill_id`        bigint       DEFAULT NULL COMMENT 'ai_skill.id；评测构建器草稿时为空',
  `conversation_id` bigint       DEFAULT NULL COMMENT '构建器会话 id；评测已发布 skill 时为空',
  `skill_name`      varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '被测 skill 名（冗余，便于查）',
  `mode`            varchar(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'RECALL=不提 skill 名，测模型会不会想到用；CAPABILITY=明确要求用，测用对没用对',
  `status`          varchar(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING | COMPLETED | FAILED',
  `total_cases`     int          NOT NULL DEFAULT '0' COMMENT '用例总数',
  `finished_cases`  int          NOT NULL DEFAULT '0' COMMENT '已跑完数（供前端显示进度）',
  `passed_cases`    int          NOT NULL DEFAULT '0' COMMENT '通过数',
  `pass_rate`       decimal(5,4) DEFAULT NULL COMMENT '通过率 0.0000-1.0000',
  `result_json`     longtext     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '逐用例的 transcript + 评分结果（grading.json 形状）',
  `error`           varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '整轮失败原因',
  `deleted`         tinyint(1)   NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`     varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`     varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  KEY `idx_skill_eval_run_skill` (`skill_id`),
  KEY `idx_skill_eval_run_conv` (`conversation_id`),
  KEY `idx_skill_eval_run_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 评测运行记录';

-- 内容指纹：这一轮评测到底测的是哪份内容。
--
-- 没有它，发布门槛会被"陈旧的好成绩"放行：跑评测拿到 0.9 → 把 skill 改坏 → 发布，
-- 门槛看到的还是那个 0.9。而这种绕过是【无声】的，用户甚至不是故意的。
-- 发布时重算当前草稿的指纹，对不上就当作"没有有效评测"。
ALTER TABLE `skill_eval_run`
  ADD COLUMN `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
  DEFAULT NULL COMMENT '被测内容（body + files）的 sha256，用于判定评测结果是否已过期'
  AFTER `mode`;
