package com.jimeng.common.core.entity.common;

import com.jimeng.common.core.config.EasyExcelConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/7/14 20:42
 */

public interface ExcelEntity {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    class ExportExcel<T> {
        private HttpServletResponse response;
        private List<T> data;
        private Class<T> clazz;
        private String fileName;
        private String sheetName;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder(builderClassName = "Builder", builderMethodName = "newBuilder", setterPrefix = "set")
    class ImportExcel<T> {
        private String filePath;
        private Class<T> clazz;
        private EasyExcelConfig<T> easyExcelConfig;
    }


}
