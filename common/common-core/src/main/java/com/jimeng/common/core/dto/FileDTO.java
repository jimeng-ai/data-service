package com.jimeng.common.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author Moonlight
 * @Description 文件接口实体类
 * @Date 2024/8/4 11:43
 */

public interface FileDTO {
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    class FileReq {
        private String bucketName;
        private String fullFileName;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    class FileStreamReq {
        private String base64Image;
        private String objectName;
        private String bucketName;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    class FileResp {
        private String fileUrl;
    }
}
