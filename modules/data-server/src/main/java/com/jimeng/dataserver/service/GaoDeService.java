package com.jimeng.dataserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.entity.GaoDeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            return OBJECT_MAPPER.readValue(String.valueOf(obj), GaoDeDTO.KeywordPOIResp.class);
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
            return OBJECT_MAPPER.readValue(String.valueOf(obj), GaoDeDTO.AroundPOIResp.class);
        } catch (JsonProcessingException e) {
            throw new ServiceException(ExceptionCode.JSON_PARSE_ERROR, "高德地图周边搜索POI返回结果解析失败", e.toString());
        }
    }

}
