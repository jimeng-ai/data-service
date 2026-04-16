---
name: gaode-poi
description: 高德POI查询与商圈聚类分析。用于地点检索、周边分析、类型聚类和选址建议。
---

1. 先识别用户任务是“关键词检索”“周边检索”“聚类分析”还是“综合分析”。
2. 当需要 region 相关分类信息或类型候选时，优先调用 `gaode.poi.category.dict.search`（对应 `PoiCategoryDictService.list`）；已知字典ID时可调用 `gaode.poi.category.dict.get_by_id`（对应 `PoiCategoryDictService.getById`）。
3. 当需要城市 code、行政区 adcode/citycode 时，优先调用 `gaode.adcode.citycode.dict.search`（对应 `AdcodeCitycodeDictService.list`）；已知字典ID时可调用 `gaode.adcode.citycode.dict.get_by_id`（对应 `AdcodeCitycodeDictService.getById`）。
4. region 缺失且无法从上下文确定时再追问用户，不要盲查全国。
5. 输出优先给结论和建议，再附关键数据。
6. 聚类任务优先调用 `gaode.poi.cluster`，并突出簇中心和簇规模。
7. region 推荐传入市级或区级行政区（如"上海市徐汇区"），types 使用高德 typecode 并可用 `|` 拼接，location 格式为 `lon,lat`。
