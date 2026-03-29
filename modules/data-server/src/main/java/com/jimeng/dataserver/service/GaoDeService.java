package com.jimeng.dataserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.entity.BizDTO;
import com.jimeng.dataserver.entity.GaoDeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GaoDeService {

    @Value("${GaoDe.api-key}")
    private String apiKey;

    @Value("${GaoDe.base-Url}")
    private String baseUrl;

    private final RequestService requestService;
    private final PoiClusterAlgorithmService poiClusterAlgorithmService;

    /**
     * 分析指定地区和类型的POI
     *
     * @param analysisAroundPOI
     * @return
     */
    public Map<String, Object> analysisAroundPOI(BizDTO.AnalysisAroundPOI analysisAroundPOI) {
        // 获取聚类结果
        GaoDeDTO.PoiClusterByTypecodeResp poiCluster = getPOICluster(analysisAroundPOI);
        Map<String, Object> resultMap = new HashMap<>();
        // 获取簇中心的周边POI
        if (poiCluster != null && poiCluster.getTypecodeClusters() != null) {

            for (GaoDeDTO.TypecodeClusterResult typecodeCluster : poiCluster.getTypecodeClusters()) {
                if (typecodeCluster == null || typecodeCluster.getClusterResult() == null) {
                    continue;
                }
                GaoDeDTO.PoiClusterResp clusterResult = typecodeCluster.getClusterResult();
                if (clusterResult.getClusterCount() == null || clusterResult.getClusterCount() <= 0) {
                    continue;
                }
                if (clusterResult.getClusters() == null || clusterResult.getClusters().isEmpty()) {
                    continue;
                }
                GaoDeDTO.PoiCluster firstCluster = clusterResult.getClusters().get(0);
                if (firstCluster == null || firstCluster.getCenterLocation() == null || firstCluster.getCenterLocation().isBlank()) {
                    continue;
                }

                GaoDeDTO.AroundPOI aroundPOI = GaoDeDTO.AroundPOI.newBuilder().build();
                aroundPOI.setRegion(analysisAroundPOI.getRegion());
                aroundPOI.setTypes(typecodeCluster.getTypecode());
                aroundPOI.setLocation(firstCluster.getCenterLocation());

                GaoDeDTO.AroundPOI buildPOI = GaoDeDTO.AroundPOI.newBuilder().build();
                buildPOI.setLocation(firstCluster.getCenterLocation());
                buildPOI.setRegion(analysisAroundPOI.getRegion());
                buildPOI.setTypes("120201|120203|120000|120100");

                // 获取商用写字楼
                List<GaoDeDTO.POI> build = getPOIByAround(buildPOI).getPois();
                // 调用周边搜索
                List<GaoDeDTO.POI> around = getPOIByAround(aroundPOI).getPois();

                Map<String, List<GaoDeDTO.POI>> map = new HashMap<>();
                map.put("周边POI", around);
                map.put("商用写字楼", build);
                resultMap.put(typecodeCluster.getTypecode(), map);
                break;
            }
        }
        return resultMap;
    }

    /**
     * 商圈聚类算法
     *
     * @param analysisAroundPOI
     * @return
     */
    public GaoDeDTO.PoiClusterByTypecodeResp getPOICluster(BizDTO.AnalysisAroundPOI analysisAroundPOI) {
        // 先请求高德POI数据，作为后续分类聚类的输入。
        GaoDeDTO.KeyworkPOI poi = GaoDeDTO.KeyworkPOI.newBuilder().build();
        poi.setRegion(analysisAroundPOI.getRegion());
        poi.setTypes(analysisAroundPOI.getTypes());
        GaoDeDTO.KeywordPOIResp poiByKeyword = getPOIByKeyword(poi);
        List<GaoDeDTO.POI> pois = poiByKeyword == null || poiByKeyword.getPois() == null
                ? Collections.emptyList()
                : poiByKeyword.getPois();
        log.info("【POI聚类】开始按typecode分类，原始POI数量={}", pois.size());

        // 按typecode拆分分组；一个POI包含多个typecode时会进入多个分组。
        Map<String, List<GaoDeDTO.POI>> groupedByTypecode = new LinkedHashMap<>();
        for (GaoDeDTO.POI sourcePoi : pois) {
            if (sourcePoi == null) {
                continue;
            }
            List<String> typecodes = splitTypeField(sourcePoi.getTypecode());
            if (typecodes.isEmpty()) {
                typecodes = List.of("UNKNOWN");
            }
            List<String> types = splitTypeField(sourcePoi.getType());
            for (int i = 0; i < typecodes.size(); i++) {
                String typecode = typecodes.get(i);
                String type = i < types.size() ? types.get(i) : sourcePoi.getType();
                GaoDeDTO.POI poiForTypecode = new GaoDeDTO.POI();
                poiForTypecode.setLocation(sourcePoi.getLocation());
                poiForTypecode.setType(type);
                poiForTypecode.setTypecode(typecode);
                poiForTypecode.setAddress(sourcePoi.getAddress());
                poiForTypecode.setChildren(sourcePoi.getChildren());
                poiForTypecode.setBusiness(sourcePoi.getBusiness());
                groupedByTypecode.computeIfAbsent(typecode, key -> new ArrayList<>()).add(poiForTypecode);
            }
        }

        // 每个typecode分组独立执行同一套DBSCAN算法，并按分类汇总返回。
        List<GaoDeDTO.TypecodeClusterResult> typecodeClusters = new ArrayList<>();
        for (Map.Entry<String, List<GaoDeDTO.POI>> entry : groupedByTypecode.entrySet()) {
            GaoDeDTO.PoiClusterResp clusterResult = poiClusterAlgorithmService.clusterPois(entry.getValue());
            typecodeClusters.add(new GaoDeDTO.TypecodeClusterResult(entry.getKey(), clusterResult));
        }
        log.info("【POI聚类】按typecode分类完成，分类数={}", groupedByTypecode.size());
        return new GaoDeDTO.PoiClusterByTypecodeResp(pois.size(), groupedByTypecode.size(), typecodeClusters);
    }

    private List<String> splitTypeField(String rawField) {
        if (rawField == null || rawField.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = rawField.split("\\|");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    public GaoDeDTO.KeywordPOIResp getPOIByKeyword(GaoDeDTO.KeyworkPOI poi) {
        String url = baseUrl + "/v5/place/text";
        Map<String, Object> params = new HashMap<>();
        params.put("key", apiKey);
        CommonUtil.putIfNotEmpty(params, "keywords", poi.getKeywords());
        CommonUtil.putIfNotEmpty(params, "types", poi.getTypes());
        CommonUtil.putIfNotEmpty(params, "region", poi.getRegion());
        CommonUtil.putIfNotEmpty(params, "city_limit", poi.getCityLimit());
        CommonUtil.putIfNotEmpty(params, "show_fields", poi.getShowFields());
        CommonUtil.putIfNotEmpty(params, "page_size", poi.getPageSize());
        CommonUtil.putIfNotEmpty(params, "page_num", poi.getPageNum());
        Object obj = requestService.get(url, null, params);
        log.info("【高德地图】关键词搜索POI请求结果：{}", obj);
        try {
            return CommonUtil.getObjectMapper().readValue(String.valueOf(obj), GaoDeDTO.KeywordPOIResp.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException(ExceptionCode.JSON_PARSE_ERROR, "高德地图关键词搜索POI返回结果解析失败", e.toString());
        }
    }

    public GaoDeDTO.AroundPOIResp getPOIByAround(GaoDeDTO.AroundPOI poi) {
        String url = baseUrl + "/v5/place/around";
        Map<String, Object> params = new HashMap<>();
        params.put("key", apiKey);
        CommonUtil.putIfNotEmpty(params, "keywords", poi.getKeywords());
        CommonUtil.putIfNotEmpty(params, "types", poi.getTypes());
        CommonUtil.putIfNotEmpty(params, "region", poi.getRegion());
        CommonUtil.putIfNotEmpty(params, "location", poi.getLocation());
        CommonUtil.putIfNotEmpty(params, "radius", poi.getRadius());
        CommonUtil.putIfNotEmpty(params, "sortrule", poi.getSortRule());
        CommonUtil.putIfNotEmpty(params, "city_limit", poi.getCityLimit());
        CommonUtil.putIfNotEmpty(params, "show_fields", poi.getShowFields());
        CommonUtil.putIfNotEmpty(params, "page_size", poi.getPageSize());
        CommonUtil.putIfNotEmpty(params, "page_num", poi.getPageNum());
        Object obj = requestService.get(url, null, params);
        log.info("【高德地图】周边搜索POI请求结果：{}", obj);
        try {
            return CommonUtil.getObjectMapper().readValue(String.valueOf(obj), GaoDeDTO.AroundPOIResp.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException(ExceptionCode.JSON_PARSE_ERROR, "高德地图周边搜索POI返回结果解析失败", e.toString());
        }
    }
}
