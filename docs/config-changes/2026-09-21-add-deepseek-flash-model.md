# 2026-09-21 ai_model 加入 deepseek-flash，并标注 deepseek-chat 是别名

## 一、为什么

实测 DeepSeek 官方 `GET /v1/models`，**现在只有两个真实模型**：

```json
{"data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}
```

`deepseek-chat` 已不在清单里，但**发这个名字不会报错**——上游把它静默映射到 flash。
实测同一个 key、同一段请求：

| 请求 model | HTTP | 响应体的 `model` 字段 |
|---|---|---|
| `deepseek-chat` | 200 | **`deepseek-flash`** |
| `deepseek-flash` | 200 | `deepseek-flash` |

这与仓库里已记录的另一条实测同源：把 `claude-opus-4-7` 发给 DeepSeek 的 anthropic 兼容端点，
**实际跑的是 `deepseek-v4-pro` 且按 Pro 计费、不报错**（见 `ConnectorProperties.SemanticAgentLlm`
的 javadoc，以及 `ConnectorSemanticGeneration.modelsSeen` 的停批保护）。

**结论**：不要依赖别名。模型名要显式、要和上游 `/models` 对得上，否则"跑的是哪个、按什么计费"
全凭上游默默决定，而这一层永远不会报错。

## 二、改了什么（dev 库 `data-server`，生产未执行）

```sql
-- ① 新增 deepseek-flash（id 沿用现有手工编号规律 970010000000000001..6 → 7）
INSERT INTO ai_model
  (id, value, label, description, protocol, provider, upstream_model,
   max_temp, price_input, price_output, price_cache_read, price_cache_write,
   enabled, sort, deleted, create_user, update_user)
VALUES
  (970010000000000007, 'deepseek-flash', 'DeepSeek Flash',
   '上游 /models 目前只有 deepseek-flash 与 deepseek-v4-pro 两个真实模型；上游 openai 协议，入口伪装 anthropic。单价【待填】，0 会让成本统计偏低。',
   'anthropic', 'deepseek', 'deepseek-flash',
   1.00, 0, 0, 0, 0, 1, 95, 0, 'system', 'system');

-- ② 把 deepseek-chat 标注成别名（【不】停用：当前有 Agent 绑着它，停用会让那些 Agent 指向一个禁用模型）
UPDATE ai_model
   SET description = '【别名，实际跑的是 deepseek-flash】上游已无此模型，发这个名字会被静默映射到 flash，不报错。建议改选 DeepSeek Flash。'
 WHERE value = 'deepseek-chat' AND deleted = 0;
```

执行一律带 `--default-character-set=utf8mb4`，否则中文 description 双重编码成乱码。

## 三、★ 直接写库【不会】让前端看到：ModelRegistry 是常驻快照

`ai/model/ModelRegistry` 把整表读成一个不可变快照（`volatile` 引用替换，读无锁），
只在 `ApplicationReadyEvent` 装载一次，之后**只有运营端 CRUD 写库后主动调 `refresh()` 才会重建**。
所以绕过运营端直接 `INSERT` 的后果是：库里有了、下拉框里没有，而且不报错。

两种让它生效的办法，任选其一：

```bash
# A. 走运营端接口（推荐，不打断正在用的人）——任一写操作都会触发 refresh()，
#    对已经是 true 的行再 PATCH 一次 enabled=true 即可，数据不变、只刷新缓存
curl -X PATCH -H "Authorization: <operator JWT>" -H "X-Tenant-Id: platform" \
  "http://localhost:10011/data/admin/operator/models/970010000000000007/enabled?enabled=true"

# B. 重启 data-server
```

确认生效看日志这一行（条数应当加一）：

```
c.j.d.ai.model.ModelRegistry - 模型目录已装载：7 条（启用 7）
```

**更好的做法是一开始就走运营端"模型管理"页新增**，而不是写 SQL——那条路径自带 refresh。
本次用 SQL 是因为要顺带改 description 的措辞。

## 四、已知不足

**单价是 0。**`price_input` / `price_output` 等四项没填，与现有 `deepseek-chat` 行一致。
后果是走 DeepSeek 的调用在 `ai_model_call_log` 里成本记为 0，看板上的花费**偏低**。
官方定价页是 JS 渲染、脚本抓不到，没有编数字。拿到真实单价后补：

```sql
UPDATE ai_model SET price_input = ?, price_output = ?,
       price_cache_read = ?, price_cache_write = ?
 WHERE value IN ('deepseek-flash', 'deepseek-chat');
```

## 五、回滚

```sql
DELETE FROM ai_model WHERE id = 970010000000000007;
UPDATE ai_model SET description = '语义层推导用；上游 openai 协议，入口伪装 anthropic'
 WHERE value = 'deepseek-chat';
```
删完同样要触发一次 refresh（办法见 §三），否则下拉框里那条会一直留到下次重启。
