package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.gaode.entity.BizDTO;
import com.jimeng.dataserver.gaode.entity.GaoDeDTO;
import com.jimeng.dataserver.gaode.service.GaoDeService;
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

    private static final Set<String> SUPPORTED = Set.of(KEYWORD_SEARCH, AROUND_SEARCH, CLUSTER, ANALYSIS_AROUND);

    private final GaoDeService gaoDeService;

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
}
