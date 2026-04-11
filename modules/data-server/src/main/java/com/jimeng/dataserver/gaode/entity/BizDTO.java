package com.jimeng.dataserver.gaode.entity;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业务DTO
 */
public interface BizDTO {

    @Data
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    @NoArgsConstructor
    @AllArgsConstructor
    class AnalysisAroundPOI {
        @NotBlank(message = "types不能为空")
        String types;
        @NotBlank(message = "region不能为空")
        String region;
    }

}
