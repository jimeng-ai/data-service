package com.jimeng.dataserver.ai.connector.spi.cap;

import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;

/**
 * 能自描述：让 Agent 知道「这里面有什么」。
 *
 * <p>刻意分成两段，因为大库有几百张表、结构全量注入放不下，而让 Agent 从零探索又浪费轮次：
 * {@link #catalog()} 给一份<b>便宜的</b>目录级概览，{@link #describe} 按需取某个对象的细节。
 */
public interface DescribeCapable {

    /** 目录概览：有哪些表 / 接口 / 目录 / 主题。只给名字和一句注释。 */
    CatalogView catalog();

    /**
     * 某个对象的细节。
     *
     * @param object 对象标识，取自 {@link CatalogView} 里的条目名
     */
    ObjectDetail describe(String object);
}
