package com.jimeng.common.core.config;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2024/7/14 20:58
 */

@Configuration
public class EasyExcelConfig<T> extends AnalysisEventListener<T> {
    private List<T> cachedData = new ArrayList<>();
    private List<T> result;

    //读取excel内容，从第二行开始读取（默认第一行是表头）把每行读取到的内容封装到t对象中
    @Override
    public void invoke(T o, AnalysisContext analysisContext) {
        //将读取到的数据放入list集合中
        cachedData.add(o);
    }

    public List<T> getResult() {
        return result;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext analysisContext) {
        result = cachedData;
        cachedData = new ArrayList<>();
    }
}
