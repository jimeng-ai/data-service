package com.jimeng.common.core.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.jimeng.common.core.entity.common.ExcelEntity;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;

import java.io.IOException;
import java.util.List;

/**
 * @Author Moonlight
 * @Description Excel工具类
 * @Date 2024/7/14 20:12
 */

public class ExcelUtil {

    public static <T> void exportExcel(ExcelEntity.ExportExcel<T> excel) throws IOException {

        // 设置响应头
        excel.getResponse().setContentType("application/vnd.ms-excel");
        excel.getResponse().setCharacterEncoding("utf-8");
        excel.getResponse().setHeader("Content-disposition", "attachment;filename=" + excel.getFileName());

        ExcelWriter excelWriter = EasyExcel.write(excel.getResponse().getOutputStream(), excel.getClazz()).build();
        WriteSheet writeSheet = EasyExcel.writerSheet(excel.getSheetName()).build();
        excelWriter.write(excel.getData(), writeSheet);
        excelWriter.finish();
    }

    public static <T> List<T> importExcel(ExcelEntity.ImportExcel<T> excel) {
        try {
            EasyExcel.read(excel.getFilePath(), excel.getClazz(), excel.getEasyExcelConfig())
                    .sheet()//excel中表的名称，默认为第一个sheet
                    .doRead();
            return excel.getEasyExcelConfig().getResult();
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "导入失败");
        }
    }

}
