---
name: gaode-poi
description: 高德POI查询与商圈聚类分析。当用户需要地点检索、周边分析、类型聚类、选址建议、商圈分析时必须使用此skill。触发词包括：高德、POI查询、周边检索、商圈分析、选址、地点聚类、地图搜索、附近、adcode、行政区划查询等。只要用户涉及地理位置检索或空间分析需求，即使未明确说"高德"，也应触发此skill。
---

# 高德POI查询与商圈聚类分析 Skill

## 概述

本 skill 用于调用高德相关 MCP 工具，完成 POI 检索、周边分析、聚类分析和选址建议任务。

---

## 第一步：识别任务类型

在调用任何工具之前，先判断用户意图属于哪种类型：

| 任务类型 | 判断依据 | 主要工具 |
|---|---|---|
| **关键词检索** | 用户给出地名/商户名称，想找具体地点 | `gaode.poi.search` |
| **周边检索** | 用户有坐标或地址，想找附近的某类场所 | `gaode.poi.nearby` |
| **聚类分析** | 用户想了解某类POI的空间分布、热点区域 | `gaode.poi.cluster` |
| **综合分析** | 选址建议、商圈评估、多维度对比 | 组合调用上述工具 |

---

## 第二步：补全必要参数

### 2.1 确定行政区划（region / adcode）

- **优先从用户描述中提取**：如"上海徐汇区""北京朝阳区"
- **需要精确 adcode/citycode 时**：调用 `gaode.adcode.citycode.dict.search`
    - 参数：传入城市或区名称关键词
    - 已知字典ID时用 `gaode.adcode.citycode.dict.get_by_id`
- **region 缺失且无法推断时**：追问用户，不要默认查全国

> region 推荐传市级或区级，如"上海市徐汇区"、"北京市朝阳区"

### 2.2 确定 POI 类型（types / typecode）

- **优先从用户描述中映射**：如"咖啡厅"→ 餐饮类
- **需要精确 typecode 时**：调用 `gaode.poi.category.dict.search`
    - 参数：传入类型关键词，如"餐饮""医院""商场"
    - 已知字典ID时用 `gaode.poi.category.dict.get_by_id`
- **多类型并查**：types 用 `|` 拼接，如 `050000|060000`

### 2.3 坐标格式

- location 格式统一为：`经度,纬度`（lon,lat）
- 示例：`121.472644,31.231706`

---

## 第三步：调用工具

### 关键词检索

```
工具：gaode.poi.search（或对应的关键词检索接口）
参数：
  - keywords: 搜索词
  - region: 城市/区名（如"上海市徐汇区"）
  - types: typecode（可选，精确过滤）
  - city_limit: true（限制在 region 内）
```

### 周边检索

```
工具：gaode.poi.nearby（或对应的周边检索接口）
参数：
  - location: "lon,lat"
  - radius: 搜索半径（米），默认1000
  - types: typecode
  - region: 城市名（辅助限定）
```

### 聚类分析

```
工具：gaode.poi.cluster
参数：
  - region 或 bbox：分析范围
  - types：POI类型
优先调用此工具用于热点分析，输出时突出：
  - 簇中心坐标
  - 每个簇的POI数量（簇规模）
  - 簇间距离/分布特征
```

---

## 第四步：输出规范

**输出顺序**：结论和建议 → 关键数据 → 详细列表（如有）

### 检索结果模板

```
## 检索结果：[关键词] · [区域]

**找到 N 个结果**，以下为重点推荐：

1. **[POI名称]**
   - 地址：xxx
   - 类型：xxx
   - 评分/热度：xxx（如有）

2. ...

> 💡 建议：[基于结果给出的分析或建议]
```

### 聚类分析模板

```
## 聚类分析：[POI类型] · [区域]

共识别出 N 个热点簇：

| 簇编号 | 中心位置 | POI数量 | 特征描述 |
|--------|----------|---------|---------|
| 1 | 经度,纬度 | XX个 | 商业密集区 |
| 2 | ... | ... | ... |

**选址建议**：[基于聚类结果的具体建议]
```

---

## 常见错误处理

| 情况 | 处理方式 |
|---|---|
| region 未提供 | 追问用户："请告诉我您想查询哪个城市或区域？" |
| typecode 不确定 | 调用 `gaode.poi.category.dict.search` 查字典 |
| 返回结果为空 | 扩大 radius 或放宽 types 限制后重试，并告知用户 |
| 坐标格式错误 | 统一转为 `lon,lat` 格式再调用 |
| 工具返回错误码 | 向用户说明原因，提供替代方案 |

---

## 调用顺序速查

```
用户请求
  │
  ├─ 需要 adcode/citycode？ → gaode.adcode.citycode.dict.search
  ├─ 需要 typecode？        → gaode.poi.category.dict.search  
  │
  ├─ 关键词检索            → gaode.poi.search
  ├─ 周边检索              → gaode.poi.nearby
  └─ 聚类/热点分析         → gaode.poi.cluster
                                │
                                └─ 输出：结论+建议 → 簇数据 → 详细列表
```