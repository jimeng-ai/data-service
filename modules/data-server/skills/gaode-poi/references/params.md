# 参数约束

- region: 推荐传入市级或区级行政区，例如 `上海市徐汇区`。
- types: 高德 typecode，可用 `|` 拼接，例如 `050000|050100`。
- location: 必须是 `lon,lat`，例如 `121.43652,31.19042`。
- radius: 单位米，建议在 1000~5000 之间。

# 字典工具建议

- `gaode.poi.category.dict.search`
  - 用于查询 POI 分类字典（`newType`、中英文类目）。
  - 可传：`newType`、`bigCategoryCn`、`midCategoryCn`、`subCategoryCn`。
- `gaode.poi.category.dict.get_by_id`
  - 用于按主键精确查询 POI 分类字典详情。
  - 必传：`id`（正整数）。

- `gaode.adcode.citycode.dict.search`
  - 用于查询行政区编码字典（城市 code、行政区 adcode/citycode）。
  - 可传：`nameCn`（如 `徐汇区`）、`adcode`、`citycode`。
- `gaode.adcode.citycode.dict.get_by_id`
  - 用于按主键精确查询行政区编码字典详情。
  - 必传：`id`（正整数）。

# 输出建议

- 聚类场景优先展示：簇数量、每簇中心点、每簇代表POI。
- 选址场景需要补充：商业密度、办公人群覆盖、潜在竞品密度。
