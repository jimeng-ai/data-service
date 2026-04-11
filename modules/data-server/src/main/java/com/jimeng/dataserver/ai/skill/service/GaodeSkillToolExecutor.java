package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.gaode.entity.BizDTO;
import com.jimeng.dataserver.gaode.entity.GaoDeDTO;
import com.jimeng.dataserver.gaode.service.AdcodeCitycodeDictService;
import com.jimeng.dataserver.gaode.service.GaoDeService;
import com.jimeng.dataserver.gaode.service.PoiCategoryDictService;
import com.jimeng.persistence.entity.AdcodeCitycodeDict;
import com.jimeng.persistence.entity.PoiCategoryDict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class GaodeSkillToolExecutor implements SkillToolExecutor {

    private static final String KEYWORD_SEARCH = "gaode.poi.keyword.search";
    private static final String AROUND_SEARCH = "gaode.poi.around.search";
    private static final String CLUSTER = "gaode.poi.cluster";
    private static final String ANALYSIS_AROUND = "gaode.poi.analysis.around";
    private static final String POI_CATEGORY_DICT_SEARCH = "gaode.poi.category.dict.search";
    private static final String POI_CATEGORY_DICT_GET_BY_ID = "gaode.poi.category.dict.get_by_id";
    private static final String ADCODE_CITYCODE_DICT_SEARCH = "gaode.adcode.citycode.dict.search";
    private static final String ADCODE_CITYCODE_DICT_GET_BY_ID = "gaode.adcode.citycode.dict.get_by_id";

    private static final Set<String> SUPPORTED = Set.of(
            KEYWORD_SEARCH,
            AROUND_SEARCH,
            CLUSTER,
            ANALYSIS_AROUND,
            POI_CATEGORY_DICT_SEARCH,
            POI_CATEGORY_DICT_GET_BY_ID,
            ADCODE_CITYCODE_DICT_SEARCH,
            ADCODE_CITYCODE_DICT_GET_BY_ID
    );

    private final GaoDeService gaoDeService;
    private final PoiCategoryDictService poiCategoryDictService;
    private final AdcodeCitycodeDictService adcodeCitycodeDictService;

    @Override
    public boolean supports(String toolName) {
        return SUPPORTED.contains(canonicalName(toolName));
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        String canonical = canonicalName(toolName);
        if (KEYWORD_SEARCH.equals(canonical)) {
            GaoDeDTO.KeyworkPOI request = CommonUtil.getObjectMapper().convertValue(input, GaoDeDTO.KeyworkPOI.class);
            return gaoDeService.getPOIByKeyword(request);
        }
        if (AROUND_SEARCH.equals(canonical)) {
            GaoDeDTO.AroundPOI request = CommonUtil.getObjectMapper().convertValue(input, GaoDeDTO.AroundPOI.class);
            return gaoDeService.getPOIByAround(request);
        }
        if (CLUSTER.equals(canonical)) {
            BizDTO.AnalysisAroundPOI request = CommonUtil.getObjectMapper().convertValue(input, BizDTO.AnalysisAroundPOI.class);
            return gaoDeService.getPOICluster(request);
        }
        if (ANALYSIS_AROUND.equals(canonical)) {
            BizDTO.AnalysisAroundPOI request = CommonUtil.getObjectMapper().convertValue(input, BizDTO.AnalysisAroundPOI.class);
            return gaoDeService.analysisAroundPOI(request);
        }
        if (POI_CATEGORY_DICT_SEARCH.equals(canonical)) {
            PoiCategoryDict request = CommonUtil.getObjectMapper().convertValue(input, PoiCategoryDict.class);
            return poiCategoryDictService.list(request);
        }
        if (POI_CATEGORY_DICT_GET_BY_ID.equals(canonical)) {
            Long id = parseLong(input == null ? null : input.get("id"));
            return poiCategoryDictService.getById(id);
        }
        if (ADCODE_CITYCODE_DICT_SEARCH.equals(canonical)) {
            AdcodeCitycodeDict request = CommonUtil.getObjectMapper().convertValue(input, AdcodeCitycodeDict.class);
            return adcodeCitycodeDictService.list(request);
        }
        if (ADCODE_CITYCODE_DICT_GET_BY_ID.equals(canonical)) {
            Long id = parseLong(input == null ? null : input.get("id"));
            return adcodeCitycodeDictService.getById(id);
        }
        throw new IllegalArgumentException("暂不支持该tool: " + toolName);
    }

    private String canonicalName(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (SUPPORTED.contains(normalized)) {
            return normalized;
        }
        String converted = normalized.replace('-', '.').replace('_', '.');
        if (SUPPORTED.contains(converted)) {
            return converted;
        }
        return normalized;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("id必须是数字");
        }
    }
}
