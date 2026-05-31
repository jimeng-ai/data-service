-- =============================================================================
-- 历史 cost_usd 回填脚本（一次性，手动执行）
-- -----------------------------------------------------------------------------
-- 用途：费用计算逻辑上线前产生的 ai_model_call_log 记录，cost_usd 为空（按 0 统计）。
--       这些行仍保留 model / input_tokens / output_tokens，可据此按当前定价估算补算。
--
-- 口径与限制：
--   1) 仅用 input/output token × 单价；历史行没有缓存 token 细分（cache_*_tokens 是后加列，
--      老数据为 NULL），故不含缓存项。属「估算」，与真实结算账单会有出入。
--   2) 下面 CASE 的单价（USD/百万 token）必须与 ModelPricing.java 保持一致；改价后两边都要改。
--      WHEN 顺序刻意与 ModelPricing 的匹配顺序相同（更具体的档位在前），保证同样的命中结果。
--   3) 幂等：只更新 cost_usd 为 NULL 或 0 的行，可安全重复执行；新数据由后端实时计算，无需回填。
--
-- 执行（本项目无 Flyway，需手动）：
--   mysql -h <host> -u <user> -p <data-server库> < backfill_cost_usd.sql
-- 建议先跑「步骤 0 预览」看影响行数与金额，确认后再跑「步骤 1 回填」。
-- =============================================================================

-- ---------- 步骤 0：预览（只读，不改数据）----------
-- 看会被回填多少行、预计补算多少钱。确认无误再执行步骤 1。
SELECT
    COUNT(*)                                   AS rows_to_backfill,
    COALESCE(SUM(
        COALESCE(input_tokens, 0)  / 1000000 * (
            CASE
                WHEN LOWER(model) LIKE '%claude-opus%'    THEN 15.0
                WHEN LOWER(model) LIKE '%claude-haiku%'   THEN 0.80
                WHEN LOWER(model) LIKE '%claude-sonnet%'  THEN 3.0
                WHEN LOWER(model) LIKE '%claude-3-haiku%' THEN 0.25
                WHEN LOWER(model) LIKE '%claude%'         THEN 3.0
                WHEN LOWER(model) LIKE '%gpt-4o-mini%'    THEN 0.15
                WHEN LOWER(model) LIKE '%gpt-4o%'         THEN 2.50
                WHEN LOWER(model) LIKE '%gpt-4.1-mini%'   THEN 0.40
                WHEN LOWER(model) LIKE '%gpt-4.1%'        THEN 2.0
                WHEN LOWER(model) LIKE '%o4-mini%'        THEN 1.10
                WHEN LOWER(model) LIKE '%o3-mini%'        THEN 1.10
                WHEN LOWER(model) LIKE '%o3%'             THEN 2.0
                WHEN LOWER(model) LIKE '%o1%'             THEN 15.0
                WHEN LOWER(model) LIKE '%gpt-4%'          THEN 2.50
                WHEN LOWER(model) LIKE '%gpt-3.5%'        THEN 0.50
                ELSE 3.0
            END
        ) +
        COALESCE(output_tokens, 0) / 1000000 * (
            CASE
                WHEN LOWER(model) LIKE '%claude-opus%'    THEN 75.0
                WHEN LOWER(model) LIKE '%claude-haiku%'   THEN 4.0
                WHEN LOWER(model) LIKE '%claude-sonnet%'  THEN 15.0
                WHEN LOWER(model) LIKE '%claude-3-haiku%' THEN 1.25
                WHEN LOWER(model) LIKE '%claude%'         THEN 15.0
                WHEN LOWER(model) LIKE '%gpt-4o-mini%'    THEN 0.60
                WHEN LOWER(model) LIKE '%gpt-4o%'         THEN 10.0
                WHEN LOWER(model) LIKE '%gpt-4.1-mini%'   THEN 1.60
                WHEN LOWER(model) LIKE '%gpt-4.1%'        THEN 8.0
                WHEN LOWER(model) LIKE '%o4-mini%'        THEN 4.40
                WHEN LOWER(model) LIKE '%o3-mini%'        THEN 4.40
                WHEN LOWER(model) LIKE '%o3%'             THEN 8.0
                WHEN LOWER(model) LIKE '%o1%'             THEN 60.0
                WHEN LOWER(model) LIKE '%gpt-4%'          THEN 10.0
                WHEN LOWER(model) LIKE '%gpt-3.5%'        THEN 1.50
                ELSE 15.0
            END
        )
    ), 0)                                      AS est_total_cost_usd
FROM ai_model_call_log
WHERE deleted = 0
  AND (cost_usd IS NULL OR cost_usd = 0)
  AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL);

-- ---------- 步骤 1：回填（写数据）----------
UPDATE ai_model_call_log
SET cost_usd = ROUND(
    COALESCE(input_tokens, 0)  / 1000000 * (
        CASE
            WHEN LOWER(model) LIKE '%claude-opus%'    THEN 15.0
            WHEN LOWER(model) LIKE '%claude-haiku%'   THEN 0.80
            WHEN LOWER(model) LIKE '%claude-sonnet%'  THEN 3.0
            WHEN LOWER(model) LIKE '%claude-3-haiku%' THEN 0.25
            WHEN LOWER(model) LIKE '%claude%'         THEN 3.0
            WHEN LOWER(model) LIKE '%gpt-4o-mini%'    THEN 0.15
            WHEN LOWER(model) LIKE '%gpt-4o%'         THEN 2.50
            WHEN LOWER(model) LIKE '%gpt-4.1-mini%'   THEN 0.40
            WHEN LOWER(model) LIKE '%gpt-4.1%'        THEN 2.0
            WHEN LOWER(model) LIKE '%o4-mini%'        THEN 1.10
            WHEN LOWER(model) LIKE '%o3-mini%'        THEN 1.10
            WHEN LOWER(model) LIKE '%o3%'             THEN 2.0
            WHEN LOWER(model) LIKE '%o1%'             THEN 15.0
            WHEN LOWER(model) LIKE '%gpt-4%'          THEN 2.50
            WHEN LOWER(model) LIKE '%gpt-3.5%'        THEN 0.50
            ELSE 3.0
        END
    ) +
    COALESCE(output_tokens, 0) / 1000000 * (
        CASE
            WHEN LOWER(model) LIKE '%claude-opus%'    THEN 75.0
            WHEN LOWER(model) LIKE '%claude-haiku%'   THEN 4.0
            WHEN LOWER(model) LIKE '%claude-sonnet%'  THEN 15.0
            WHEN LOWER(model) LIKE '%claude-3-haiku%' THEN 1.25
            WHEN LOWER(model) LIKE '%claude%'         THEN 15.0
            WHEN LOWER(model) LIKE '%gpt-4o-mini%'    THEN 0.60
            WHEN LOWER(model) LIKE '%gpt-4o%'         THEN 10.0
            WHEN LOWER(model) LIKE '%gpt-4.1-mini%'   THEN 1.60
            WHEN LOWER(model) LIKE '%gpt-4.1%'        THEN 8.0
            WHEN LOWER(model) LIKE '%o4-mini%'        THEN 4.40
            WHEN LOWER(model) LIKE '%o3-mini%'        THEN 4.40
            WHEN LOWER(model) LIKE '%o3%'             THEN 8.0
            WHEN LOWER(model) LIKE '%o1%'             THEN 60.0
            WHEN LOWER(model) LIKE '%gpt-4%'          THEN 10.0
            WHEN LOWER(model) LIKE '%gpt-3.5%'        THEN 1.50
            ELSE 15.0
        END
    )
, 8)
WHERE deleted = 0
  AND (cost_usd IS NULL OR cost_usd = 0)
  AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL);

-- ---------- 步骤 2：校验 ----------
-- 回填后还剩多少 cost_usd 仍为空（通常是 token 也为空的失败/进行中调用，属正常）。
SELECT
    COUNT(*)                                                          AS total_rows,
    SUM(CASE WHEN cost_usd IS NULL OR cost_usd = 0 THEN 1 ELSE 0 END) AS still_zero_cost,
    ROUND(SUM(COALESCE(cost_usd, 0)), 4)                             AS total_cost_usd
FROM ai_model_call_log
WHERE deleted = 0;
